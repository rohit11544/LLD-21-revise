# LLD — Social Chat / Messenger

Google L4–sized: 1:1 + group, Observer sessions, offline buffer, per-recipient receipts.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Direct + group text messages
- Lifecycle per recipient: server **SENT** → **DELIVERED** → **READ**
- Online push; offline queue flush on reconnect
- Read receipts to sender; **lastSeen** on disconnect

### Non-functional

- Session lookup O(1); group fan-out O(members)
- Decouple transport session (Observer) from routing facade

---

## 2. Architecture (00:05 – 00:12)

```
                    ChatService (Facade)
           ┌──────────────┼──────────────┐
           ▼              ▼              ▼
    UserSession     OfflineBuffer    ReceiptRegistry
    (Observer)      per user queue   (msgId, userId) → status
```

**Observer is real here:** each online `UserSession` is notified of messages/receipts.  
(Contrast Notification LLD — that was Strategy/registry, not Observer.)

- `onMessageReceived()` → **receiver** gets the actual message  
- `onReceiptReceived()` → **sender** gets delivery/read status updates (receipts)

### Presence + delivery flow

```text
registerUserSession()
        │
        ▼
User ONLINE  (activeSessions.put)
        │
        ▼
Flush offlineBuffers (if any) ──► deliverToSession() …

──────────────────────────────────────────────

sendMessage()  →  messageStore (payload always saved)
        │
        ▼
Recipient ONLINE?
   ┌────┴────┐
  YES        NO
   │          │
   ▼          ▼
deliverToSession()    offlineBuffers.add()
   │                  (no receipt yet)
   ▼
ReceiptRegistry = DELIVERED
   │
   ├──► recipient.onMessageReceived()   // content
   └──► sender.onReceiptReceived()      // if sender still online

──────────────────────────────────────────────

unregisterUserSession()  →  User OFFLINE
        │
        ▼
Later sendMessage() → offlineBuffers only

──────────────────────────────────────────────

Later registerUserSession() again
        │
        ▼
Flush offlineBuffers → deliverToSession()
        │
        ▼
DELIVERED + onMessageReceived + onReceiptReceived (same as online path)

──────────────────────────────────────────────

markAsRead()
        │
        ▼
ReceiptRegistry = READ
        │
        ▼
sender.onReceiptReceived()   // if sender online
```

**Note:** Offline ≠ write to `ReceiptRegistry`. Buffer first; receipts update when delivery (or read) actually happens.


## 3. Code (one file)

```
SocialChat/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/SocialChat/code
javac Main.java && java Main
```

---

## 4. Fixes in this ship

| Issue | Fix |
|-------|-----|
| One shared `Message.status` for groups | `ReceiptRegistry` keyed by `(messageId, recipientUserId)` |
| Offline/group status races | `PendingDelivery` per recipient; same payload, separate receipts |
| Presence / lastSeen missing | `lastSeenEpochMs` on disconnect; `isOnline` / `getLastSeen` |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | Message = payload · Session = transport · Receipts = status · Service = route |
| **O** | New observer transport (WS/mobile) via `MessageObserver` |
| **D** | Facade notifies `MessageObserver`, not concrete socket code |

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `sendMessage` / `registerUserSession` / `markAsRead` — hides routing, buffers, receipts |
| **Observer** | Online `UserSession` receives `onMessageReceived` / `onReceiptReceived` |
| **Per-recipient state** | Delivery/read tracked per user (not one global enum on the message) |

---

## 7. Interview Q&A

**“Message order across nodes?”**  
Per-conversation sequence / Snowflake ids; client sorts by `seq_id`.

**“Offline at scale?”**  
Persist unread by `recipient_id` (Redis Streams / Cassandra); paginate on reconnect.

**“Why not one status on Message?”**  
In a group, Alice can READ while Bob is still only DELIVERED — must be per recipient.
