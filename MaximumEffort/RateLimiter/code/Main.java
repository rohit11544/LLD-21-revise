import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-Safe Rate Limiter — ONE FILE
 * Patterns: Strategy + Facade + lazy Token Bucket refill
 *
 *   javac Main.java && java Main
 */

// ========== CONFIG ==========

class RateLimitConfig {
    private final long capacity;     // max tokens / max requests in window
    private final long windowSizeMs; // e.g. 1000ms

    RateLimitConfig(long capacity, long windowSizeMs) {
        this.capacity = capacity;
        this.windowSizeMs = windowSizeMs;
    }

    long getCapacity() { return capacity; }
    long getWindowSizeMs() { return windowSizeMs; }
}

// ========== STRATEGY ==========

interface RateLimitStrategy {
    boolean allowRequest(String clientId);
}

// ========== TOKEN BUCKET ==========

class TokenBucketRateLimiter implements RateLimitStrategy {
    private final RateLimitConfig config;
    private final Map<String, TokenBucket> buckets = new ConcurrentHashMap<>();

    TokenBucketRateLimiter(RateLimitConfig config) {
        this.config = config;
    }

    @Override
    public boolean allowRequest(String clientId) {
        TokenBucket bucket = buckets.computeIfAbsent(clientId,
                k -> new TokenBucket(config.getCapacity(), config.getWindowSizeMs()));
        return bucket.tryConsume();
    }

    private static class TokenBucket {
        private final long capacity;
        private final double refillRatePerMs;
        private double currentTokens;
        private long lastRefillTimestamp;
        private final ReentrantLock lock = new ReentrantLock();

        TokenBucket(long capacity, long windowSizeMs) {
            this.capacity = capacity;
            this.currentTokens = capacity;
            this.refillRatePerMs = (double) capacity / windowSizeMs;
            this.lastRefillTimestamp = System.currentTimeMillis();
        }

        boolean tryConsume() {
            lock.lock();
            try {
                refill();
                if (currentTokens >= 1.0) {
                    currentTokens -= 1.0;
                    return true;
                }
                return false;
            } finally {
                lock.unlock();
            }
        }

        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefillTimestamp;
            if (elapsed > 0) {
                currentTokens = Math.min(capacity, currentTokens + elapsed * refillRatePerMs);
                lastRefillTimestamp = now;
            }
        }
    }
}

// ========== SLIDING WINDOW LOG ==========

class SlidingWindowLogRateLimiter implements RateLimitStrategy {
    private final RateLimitConfig config;
    private final Map<String, ConcurrentLinkedDeque<Long>> clientLogs = new ConcurrentHashMap<>();

    SlidingWindowLogRateLimiter(RateLimitConfig config) {
        this.config = config;
    }

    @Override
    public boolean allowRequest(String clientId) {
        long now = System.currentTimeMillis();
        long windowStart = now - config.getWindowSizeMs();

        ConcurrentLinkedDeque<Long> log = clientLogs.computeIfAbsent(clientId,
                k -> new ConcurrentLinkedDeque<>());

        synchronized (log) {
            while (!log.isEmpty() && log.peekFirst() <= windowStart) {
                log.pollFirst();
            }
            if (log.size() < config.getCapacity()) {
                log.offerLast(now);
                return true;
            }
            return false;
        }
    }
}

// ========== FACADE ==========

class RateLimiterService {
    private final RateLimitStrategy strategy;

    RateLimiterService(RateLimitStrategy strategy) {
        this.strategy = strategy;
    }

    boolean allowRequest(String clientId) {
        return strategy.allowRequest(clientId);
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        System.out.println("=== TEST 1: TOKEN BUCKET (Capacity = 3, Window = 1 sec) ===");
        RateLimitConfig config = new RateLimitConfig(3, 1000);
        RateLimiterService tokenBucketLimiter =
                new RateLimiterService(new TokenBucketRateLimiter(config));

        String client = "User_Tier1";
        for (int i = 1; i <= 4; i++) {
            System.out.println("Request " + i + " -> Allowed: "
                    + tokenBucketLimiter.allowRequest(client));
        }

        System.out.println("\n... Waiting 500ms for partial token refill ...");
        Thread.sleep(500);
        System.out.println("Request 5 -> Allowed: " + tokenBucketLimiter.allowRequest(client));

        System.out.println("\n=== TEST 2: SLIDING WINDOW LOG ===");
        RateLimiterService slidingWindowLimiter =
                new RateLimiterService(new SlidingWindowLogRateLimiter(config));

        for (int i = 1; i <= 4; i++) {
            System.out.println("Sliding Window Request " + i + " -> Allowed: "
                    + slidingWindowLimiter.allowRequest("User_Tier2"));
        }
    }
}
