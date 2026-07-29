import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Multi-Channel Notification System — ONE FILE (interview style)
 * Patterns: Facade + Strategy (channel) + provider fallback chain
 *
 * Fixes vs naive draft:
 * - Channels = Strategy/registry (NOT Observer)
 * - Email fallback = SendGrid → SES provider list (not a fake if inside one class)
 * - Simple per-user+channel rate limit before enqueue
 * - Priority + seq for stable ordering; clean shutdown drain
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum ChannelType {
    EMAIL,
    SMS,
    PUSH
}

enum Priority {
    CRITICAL(1),
    HIGH(2),
    LOW(3);

    private final int level;
    Priority(int level) { this.level = level; }
    int getLevel() { return level; }
}

class NotificationRequest implements Comparable<NotificationRequest> {
    private final String requestId;
    private final String userId;
    private final ChannelType channelType;
    private final Priority priority;
    private final String templateId;
    private final Map<String, String> templateParams;
    private final long seq; // tie-break: earlier enqueue wins among same priority

    NotificationRequest(String userId, ChannelType channelType, Priority priority,
                        String templateId, Map<String, String> templateParams, long seq) {
        this.requestId = "REQ_" + UUID.randomUUID().toString().substring(0, 8);
        this.userId = userId;
        this.channelType = channelType;
        this.priority = priority;
        this.templateId = templateId;
        this.templateParams = templateParams;
        this.seq = seq;
    }

    String getRequestId() { return requestId; }
    String getUserId() { return userId; }
    ChannelType getChannelType() { return channelType; }
    Priority getPriority() { return priority; }
    String getTemplateId() { return templateId; }
    Map<String, String> getTemplateParams() { return templateParams; }

    @Override
    public int compareTo(NotificationRequest other) {
        int byPriority = Integer.compare(this.priority.getLevel(), other.priority.getLevel());
        if (byPriority != 0) return byPriority;
        return Long.compare(this.seq, other.seq);
    }
}

// ========== TEMPLATE & PREFERENCES ==========

class TemplateEngine {
    private final Map<String, String> templates = new ConcurrentHashMap<>();

    void registerTemplate(String templateId, String templateText) {
        templates.put(templateId, templateText);
    }

    String render(String templateId, Map<String, String> params) {
        String template = templates.get(templateId);
        if (template == null) throw new IllegalArgumentException("Template not found: " + templateId);
        String rendered = template;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            rendered = rendered.replace("{" + entry.getKey() + "}", entry.getValue());
        }
        return rendered;
    }
}

class UserPreferenceService {
    private final Map<String, Set<ChannelType>> optOuts = new ConcurrentHashMap<>();

    void optOut(String userId, ChannelType channel) {
        optOuts.computeIfAbsent(userId, k -> ConcurrentHashMap.newKeySet()).add(channel);
    }

    boolean isAllowed(String userId, ChannelType channel) {
        Set<ChannelType> userOptOuts = optOuts.get(userId);
        return userOptOuts == null || !userOptOuts.contains(channel);
    }
}

/** Tiny fixed-window limiter: max N sends per user+channel per windowMs. */
class NotificationRateLimiter {
    private final int maxPerWindow;
    private final long windowMs;
    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    NotificationRateLimiter(int maxPerWindow, long windowMs) {
        this.maxPerWindow = maxPerWindow;
        this.windowMs = windowMs;
    }

    synchronized boolean allow(String userId, ChannelType channel) {
        String key = userId + ":" + channel;
        long now = System.currentTimeMillis();
        Window existing = windows.get(key);
        if (existing == null || now - existing.windowStart >= windowMs) {
            windows.put(key, new Window(now, 1));
            return true;
        }
        if (existing.count >= maxPerWindow) {
            return false;
        }
        windows.put(key, new Window(existing.windowStart, existing.count + 1));
        return true;
    }

    private static class Window {
        final long windowStart;
        final int count;
        Window(long windowStart, int count) {
            this.windowStart = windowStart;
            this.count = count;
        }
    }
}

// ========== PROVIDERS + CHANNEL STRATEGY ==========

interface MessageProvider {
    String name();
    boolean send(String userId, String message);
}

class SendGridProvider implements MessageProvider {
    private volatile boolean down;

    void setDown(boolean down) { this.down = down; }

    @Override
    public String name() { return "SendGrid"; }

    @Override
    public boolean send(String userId, String message) {
        if (down) {
            System.out.println("   [SendGrid] DOWN");
            return false;
        }
        System.out.println("   [SendGrid] EMAIL → " + userId + ": " + message);
        return true;
    }
}

class SesProvider implements MessageProvider {
    @Override
    public String name() { return "AWS SES"; }

    @Override
    public boolean send(String userId, String message) {
        System.out.println("   [AWS SES] EMAIL → " + userId + ": " + message);
        return true;
    }
}

class TwilioSmsProvider implements MessageProvider {
    @Override
    public String name() { return "Twilio"; }

    @Override
    public boolean send(String userId, String message) {
        System.out.println("   [Twilio] SMS → " + userId + ": " + message);
        return true;
    }
}

/** Strategy: one channel type; may try a chain of providers (fallback). */
interface NotificationChannel {
    ChannelType getType();
    boolean send(String userId, String message);
}

class EmailChannel implements NotificationChannel {
    private final List<MessageProvider> providers;

    EmailChannel(List<MessageProvider> providers) {
        this.providers = providers;
    }

    @Override
    public ChannelType getType() { return ChannelType.EMAIL; }

    @Override
    public boolean send(String userId, String message) {
        for (MessageProvider provider : providers) {
            System.out.println("   [EmailChannel] try " + provider.name());
            if (provider.send(userId, message)) return true;
            System.out.println("   [EmailChannel] fallback...");
        }
        return false;
    }
}

class SmsChannel implements NotificationChannel {
    private final MessageProvider provider;

    SmsChannel(MessageProvider provider) {
        this.provider = provider;
    }

    @Override
    public ChannelType getType() { return ChannelType.SMS; }

    @Override
    public boolean send(String userId, String message) {
        return provider.send(userId, message);
    }
}

// ========== FACADE ==========

class NotificationService {
    private final PriorityBlockingQueue<NotificationRequest> priorityQueue = new PriorityBlockingQueue<>();
    private final Map<ChannelType, NotificationChannel> channels = new ConcurrentHashMap<>();
    private final TemplateEngine templateEngine;
    private final UserPreferenceService preferenceService;
    private final NotificationRateLimiter rateLimiter;
    private final ExecutorService workerPool = Executors.newFixedThreadPool(2);
    private final AtomicLong seqGen = new AtomicLong();
    private volatile boolean accepting = true;
    private volatile boolean workersStarted = false;

    NotificationService(TemplateEngine templateEngine,
                        UserPreferenceService preferenceService,
                        NotificationRateLimiter rateLimiter) {
        this.templateEngine = templateEngine;
        this.preferenceService = preferenceService;
        this.rateLimiter = rateLimiter;
    }

    /** Start drain workers (call after seeding queue for priority demos). */
    synchronized void start() {
        if (workersStarted) return;
        workersStarted = true;
        // Two workers = two threads both running drainLoop (parallel consumers of one queue)
        workerPool.submit(this::drainLoop);
        workerPool.submit(this::drainLoop);
    }

    void registerChannel(NotificationChannel channel) {
        channels.put(channel.getType(), channel);
    }

    void sendNotification(String userId, ChannelType channelType, Priority priority,
                          String templateId, Map<String, String> templateParams) {
        if (!accepting) {
            System.out.println("[Rejected] service shutting down");
            return;
        }
        if (!preferenceService.isAllowed(userId, channelType)) {
            System.out.println("[Rejected] " + userId + " opted out of " + channelType);
            return;
        }
        if (!rateLimiter.allow(userId, channelType)) {
            System.out.println("[Throttled] " + userId + " " + channelType + " rate limit");
            return;
        }

        NotificationRequest request = new NotificationRequest(
                userId, channelType, priority, templateId, templateParams, seqGen.incrementAndGet());
        priorityQueue.offer(request);
        System.out.println("[Enqueued] " + request.getRequestId()
                + " priority=" + priority + " channel=" + channelType);
    }

    private void drainLoop() {
        while (accepting || !priorityQueue.isEmpty()) {
            try {
                // Wait up to 100ms for next item; returns immediately if available, else null on timeout
                // (not "runs every 100ms" — only blocks when queue is empty)
                NotificationRequest request = priorityQueue.poll(100, TimeUnit.MILLISECONDS);
                if (request != null) processRequest(request);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private void processRequest(NotificationRequest request) {
        NotificationChannel channel = channels.get(request.getChannelType());
        if (channel == null) {
            System.out.println("[Error] no channel for " + request.getChannelType());
            return;
        }
        String message = templateEngine.render(request.getTemplateId(), request.getTemplateParams());
        System.out.println("\n[Process " + request.getPriority() + "] " + request.getRequestId());
        boolean ok = channel.send(request.getUserId(), message);
        if (!ok) System.out.println("[Failed] all providers exhausted for " + request.getChannelType());
    }

    void shutdown() throws InterruptedException {
        accepting = false;
        workerPool.shutdown();
        workerPool.awaitTermination(2, TimeUnit.SECONDS);
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        TemplateEngine templates = new TemplateEngine();
        templates.registerTemplate("T_OTP", "Your login OTP is {otp}. Valid for 5 minutes.");
        templates.registerTemplate("T_PROMO", "Hi {name}, get 50% off your next purchase!");

        UserPreferenceService prefs = new UserPreferenceService();
        NotificationRateLimiter limiter = new NotificationRateLimiter(3, 60_000);
        NotificationService service = new NotificationService(templates, prefs, limiter);

        SendGridProvider sendGrid = new SendGridProvider();
        EmailChannel email = new EmailChannel(Arrays.asList(sendGrid, new SesProvider()));
        SmsChannel sms = new SmsChannel(new TwilioSmsProvider());
        service.registerChannel(email);
        service.registerChannel(sms);

        System.out.println("=== TEST 1: PRIORITY (enqueue LOW then CRITICAL, then start workers) ===");
        Map<String, String> promo = new HashMap<>();
        promo.put("name", "Alice");
        Map<String, String> otp = new HashMap<>();
        otp.put("otp", "883921");

        service.sendNotification("user_alice", ChannelType.EMAIL, Priority.LOW, "T_PROMO", promo);
        service.sendNotification("user_bob", ChannelType.SMS, Priority.CRITICAL, "T_OTP", otp);
        service.start(); // both already queued → CRITICAL should process first
        Thread.sleep(400);

        System.out.println("\n=== TEST 2: OPT-OUT ===");
        prefs.optOut("user_charlie", ChannelType.SMS);
        service.sendNotification("user_charlie", ChannelType.SMS, Priority.HIGH, "T_OTP", otp);

        System.out.println("\n=== TEST 3: EMAIL PROVIDER FALLBACK (SendGrid → SES) ===");
        sendGrid.setDown(true);
        service.sendNotification("user_dave", ChannelType.EMAIL, Priority.HIGH, "T_PROMO", promo);
        Thread.sleep(400);

        System.out.println("\n=== TEST 4: RATE LIMIT (4th SMS in window) ===");
        for (int i = 0; i < 4; i++) {
            Map<String, String> p = new HashMap<>();
            p.put("otp", "00000" + i);
            service.sendNotification("user_eve", ChannelType.SMS, Priority.CRITICAL, "T_OTP", p);
        }
        Thread.sleep(400);

        service.shutdown();
        System.out.println("\n=== DONE ===");
    }
}
