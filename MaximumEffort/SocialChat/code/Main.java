import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Social Chat / Messenger — ONE FILE (interview style)
 * Patterns: Facade + Observer (live sessions) + offline buffer
 *
 * Fixes vs naive draft:
 * - Per-recipient receipts (msgId, userId) — not one global status on shared Message
 * - Offline/group fan-out uses recipient-scoped delivery records
 * - lastSeen on disconnect
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum MessageStatus {
    SENT,       // accepted by server
    DELIVERED,  // pushed to that recipient's device/session
    READ        // that recipient opened it
}

/** Immutable message payload (content). Delivery state lives in ReceiptRegistry. */
class Message {
    private final String messageId;
    private final String senderId;
    private final String targetId; // userId or groupId
    private final String content;
    private final boolean group;
    private final long timestamp;

    Message(String senderId, String targetId, String content, boolean group) {
        this.messageId = "MSG_" + UUID.randomUUID().toString().substring(0, 8);
        this.senderId = senderId;
        this.targetId = targetId;
        this.content = content;
        this.group = group;
        this.timestamp = System.currentTimeMillis();
    }

    String getMessageId() { return messageId; }
    String getSenderId() { return senderId; }
    String getTargetId() { return targetId; }
    String getContent() { return content; }
    boolean isGroup() { return group; }
    long getTimestamp() { return timestamp; }

    @Override
    public String toString() {
        return "Message{id='" + messageId + "', from='" + senderId + "', to='" + targetId
                + "', content='" + content + "', group=" + group + "}";
    }
}

class User {
    private final String userId;
    private final String name;

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    String getUserId() { return userId; }
    String getName() { return name; }
}

/** Pending push for one recipient (offline queue item). */
class PendingDelivery {
    final Message message;
    final String recipientUserId;

    PendingDelivery(Message message, String recipientUserId) {
        this.message = message;
        this.recipientUserId = recipientUserId;
    }
}

// ========== OBSERVER (LIVE SESSION) ==========

interface MessageObserver {
    /** Receiver gets the actual message content. */
    void onMessageReceived(Message message);

    /** Sender gets delivery/read status updates (receipts). */
    void onReceiptReceived(String messageId, MessageStatus status, String updatedByUserId);
}

class UserSession implements MessageObserver {
    private final User user;

    UserSession(User user) {
        this.user = user;
    }

    User getUser() { return user; }

    @Override
    public void onMessageReceived(Message message) {
        System.out.println("   [Session " + user.getName() + "] got \"" + message.getContent()
                + "\" from " + message.getSenderId());
    }

    @Override
    public void onReceiptReceived(String messageId, MessageStatus status, String updatedByUserId) {
        System.out.println("   [Receipt → " + user.getName() + "] " + messageId
                + " → " + status + " by " + updatedByUserId);
    }
}

class GroupChat {
    private final String groupId;
    private final String groupName;
    private final Set<String> memberUserIds = ConcurrentHashMap.newKeySet();

    GroupChat(String groupId, String groupName) {
        this.groupId = groupId;
        this.groupName = groupName;
    }

    String getGroupId() { return groupId; }
    String getGroupName() { return groupName; }
    Set<String> getMemberUserIds() { return memberUserIds; }
    void addMember(String userId) { memberUserIds.add(userId); }
}

/** Per (messageId, recipientUserId) status — fixes shared Message status bug. */
class ReceiptRegistry {
    private final Map<String, Map<String, MessageStatus>> receipts = new ConcurrentHashMap<>();

    void set(String messageId, String recipientUserId, MessageStatus status) {
        receipts.computeIfAbsent(messageId, k -> new ConcurrentHashMap<>())
                .put(recipientUserId, status);
    }

    MessageStatus get(String messageId, String recipientUserId) {
        Map<String, MessageStatus> perUser = receipts.get(messageId);
        return perUser == null ? null : perUser.get(recipientUserId);
    }
}

// ========== FACADE ==========

class ChatService {
    private final Map<String, UserSession> activeSessions = new ConcurrentHashMap<>();
    private final Map<String, Queue<PendingDelivery>> offlineBuffers = new ConcurrentHashMap<>();
    private final Map<String, GroupChat> groupRegistry = new ConcurrentHashMap<>();
    private final Map<String, Message> messageStore = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenEpochMs = new ConcurrentHashMap<>();
    private final ReceiptRegistry receipts = new ReceiptRegistry();

    /**
     * User comes ONLINE (session registered).
     * Flush any messages buffered while they were offline → deliverToSession.
     */
    void registerUserSession(UserSession session) {
        String userId = session.getUser().getUserId();
        activeSessions.put(userId, session);
        System.out.println("[Online] " + session.getUser().getName());

        Queue<PendingDelivery> buffer = offlineBuffers.get(userId);
        if (buffer != null && !buffer.isEmpty()) {
            System.out.println("[Offline flush] " + buffer.size() + " msg(s) → "
                    + session.getUser().getName());
            PendingDelivery pending;
            while ((pending = buffer.poll()) != null) {
                deliverToSession(session, pending.message);
            }
        }
    }

    /** User goes OFFLINE — later sendMessage routes to offlineBuffers instead of push. */
    void unregisterUserSession(String userId) {
        activeSessions.remove(userId);
        lastSeenEpochMs.put(userId, System.currentTimeMillis());
        System.out.println("[Offline] " + userId + " lastSeen=" + lastSeenEpochMs.get(userId));
    }

    Long getLastSeen(String userId) {
        if (activeSessions.containsKey(userId)) return null; // online now
        return lastSeenEpochMs.get(userId);
    }

    boolean isOnline(String userId) {
        return activeSessions.containsKey(userId);
    }

    void createGroup(GroupChat group) {
        groupRegistry.put(group.getGroupId(), group);
    }

    Message sendMessage(String senderId, String targetId, String content, boolean isGroup) {
        Message message = new Message(senderId, targetId, content, isGroup);
        messageStore.put(message.getMessageId(), message);
        System.out.println("[Server SENT] " + message);

        if (isGroup) {
            routeGroup(message);
        } else {
            routeDirect(message, targetId);
        }
        return message;
    }

    private void routeDirect(Message message, String recipientUserId) {
        UserSession session = activeSessions.get(recipientUserId);
        if (session != null) {
            deliverToSession(session, message);
        } else {
            System.out.println("[Buffer] " + recipientUserId + " offline");
            offlineBuffers.computeIfAbsent(recipientUserId, k -> new ConcurrentLinkedQueue<>())
                    .add(new PendingDelivery(message, recipientUserId));
        }
    }

    private void routeGroup(Message message) {
        GroupChat group = groupRegistry.get(message.getTargetId());
        if (group == null) throw new IllegalArgumentException("Group not found");

        for (String memberId : group.getMemberUserIds()) {
            if (memberId.equals(message.getSenderId())) continue;
            routeDirect(message, memberId);
        }
    }

    private void deliverToSession(UserSession recipientSession, Message message) {
        String recipientId = recipientSession.getUser().getUserId();
        receipts.set(message.getMessageId(), recipientId, MessageStatus.DELIVERED);
        recipientSession.onMessageReceived(message);

        UserSession senderSession = activeSessions.get(message.getSenderId());
        if (senderSession != null) {
            senderSession.onReceiptReceived(
                    message.getMessageId(), MessageStatus.DELIVERED, recipientId);
        }
    }

    void markAsRead(String messageId, String readerUserId) {
        Message message = messageStore.get(messageId);
        if (message == null) return;

        receipts.set(messageId, readerUserId, MessageStatus.READ);
        System.out.println("[READ] " + messageId + " by " + readerUserId
                + " (per-recipient status=" + receipts.get(messageId, readerUserId) + ")");

        UserSession senderSession = activeSessions.get(message.getSenderId());
        if (senderSession != null) {
            senderSession.onReceiptReceived(messageId, MessageStatus.READ, readerUserId);
        }
    }

    MessageStatus receiptFor(String messageId, String recipientUserId) {
        return receipts.get(messageId, recipientUserId);
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        ChatService chat = new ChatService();

        User alice = new User("U1", "Alice");
        User bob = new User("U2", "Bob");
        User charlie = new User("U3", "Charlie");

        UserSession aliceSession = new UserSession(alice);
        UserSession bobSession = new UserSession(bob);

        chat.registerUserSession(aliceSession);
        chat.registerUserSession(bobSession);

        System.out.println("\n=== TEST 1: DIRECT + DELIVERED RECEIPT ===");
        Message m1 = chat.sendMessage("U1", "U2", "Hey Bob, how are you?", false);
        System.out.println("Receipt U2=" + chat.receiptFor(m1.getMessageId(), "U2"));

        System.out.println("\n=== TEST 2: READ RECEIPT ===");
        chat.markAsRead(m1.getMessageId(), "U2");

        System.out.println("\n=== TEST 3: OFFLINE BUFFER + LAST SEEN ===");
        chat.unregisterUserSession("U2"); // Bob goes offline (sets lastSeen)
        System.out.println("Bob lastSeen=" + chat.getLastSeen("U2") + " online=" + chat.isOnline("U2"));

        Message m2 = chat.sendMessage("U1", "U3", "Hi Charlie, check this out!", false);
        System.out.println("\nCharlie connects → flush");
        chat.registerUserSession(new UserSession(charlie));
        System.out.println("Receipt U3=" + chat.receiptFor(m2.getMessageId(), "U3"));

        System.out.println("\n=== TEST 4: GROUP FAN-OUT (per-member receipts) ===");
        GroupChat devTeam = new GroupChat("G101", "Dev Team");
        devTeam.addMember("U1");
        devTeam.addMember("U2");
        devTeam.addMember("U3");
        chat.createGroup(devTeam);

        // Bob still offline → buffered; Charlie online → delivered
        Message g1 = chat.sendMessage("U1", "G101", "Team, deployment is live!", true);
        System.out.println("Group receipt U2 (offline)=" + chat.receiptFor(g1.getMessageId(), "U2"));
        System.out.println("Group receipt U3 (online)=" + chat.receiptFor(g1.getMessageId(), "U3"));

        System.out.println("\nBob reconnects → group msg flush");
        chat.registerUserSession(new UserSession(bob));
        System.out.println("Group receipt U2 after flush=" + chat.receiptFor(g1.getMessageId(), "U2"));
    }
}
