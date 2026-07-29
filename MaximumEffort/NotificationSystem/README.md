# LLD — Multi-Channel Notification System

Google L4–sized: priority async dispatch, templates, opt-out, rate limit, channel Strategy, provider fallback.

---

## 1. Goal

Asynchronously send notifications through **Email / SMS / Push** with:

- Priority
- Templates
- Opt-out
- Rate limiting
- Provider fallback

---

## 2. Architecture (00:05 – 00:12)

```
                 NotificationService (Facade)
            ┌────────────┼────────────┐
            ▼            ▼            ▼
   PriorityBlockingQueue  TemplateEngine  Prefs + RateLimiter
            │
            ▼
   NotificationChannel (Strategy per ChannelType)
            │
            ▼
   MessageProvider chain (fallback)
```

**Not Observer:** one request → one channel type from a registry. Observer would fan out to many listeners.

---

## 3. Components

| Component | Role |
|-----------|------|
| **NotificationService** (Facade) | `sendNotification()` — prefs + rate limit → enqueue. Workers started via `start()` (not inside send). |
| **NotificationRequest** | One notification: userId, channel, priority, templateId, params, `seq`. `Comparable` for queue order. |
| **PriorityBlockingQueue** | Pending work. Higher priority first; same priority → FIFO via `seq`. |
| **Worker threads** | Continuously `poll` the queue and process. Caller does not wait. Demo = **2** drain loops. |
| **TemplateEngine** | Store templates; replace `{name}` → `Alice`. |
| **UserPreferenceService** | Opt-outs; reject if user disabled that channel. |
| **NotificationRateLimiter** | Fixed window per `userId:channel` (e.g. max 3 / window). |
| **NotificationChannel** (Strategy) | `EmailChannel` / `SmsChannel` (+ Push = new impl, OCP). |
| **MessageProvider** | Real vendor: SendGrid, AWS SES, Twilio. |

**Email fallback (this LLD):**

```text
SendGrid
   ↓ fails
AWS SES
```

SMS demo uses Twilio only (same provider-list pattern can add more).

---

## 4. End-to-end flow

```text
Client
   ↓
NotificationService
   ↓
Preference Check
   ↓
Rate Limit
   ↓
Priority Queue
   ↓
Worker Thread
   ↓
TemplateEngine
   ↓
Channel Strategy
   ↓
Provider (+ fallback if needed)
   ↓
Notification Sent
```

---

## 5. Code (one file)

```
NotificationSystem/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/NotificationSystem/code
javac Main.java && java Main
```

---

## 6. Fixes in this ship

| Issue | Fix |
|-------|-----|
| Fake Observer claim | Strategy/registry only |
| Fallback as `if` inside Email | `List<MessageProvider>` SendGrid → SES |
| Rate limit missing | Simple fixed-window limiter before enqueue |
| Equal-priority order | `seq` tie-break |
| Shutdown drops work | `accepting=false` then drain + awaitTermination |

---

## 7. SOLID

| | How |
|--|-----|
| **S** | Template / prefs / limiter / channel / facade split |
| **O** | Add WhatsApp channel or new email provider without editing worker loop |
| **L/I** | Thin `NotificationChannel` / `MessageProvider` |
| **D** | Facade depends on channel abstractions |

---

## 8. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `sendNotification` — hides queue, workers, template, prefs, limit |
| **Strategy** | `EmailChannel` / `SmsChannel` selected by `ChannelType` |
| **Provider fallback chain** | Try providers in order until one succeeds (CoR-like; not multi-subscriber Observer) |

---

## 9. Interview Q&A

**“`synchronized` vs map locks — how do we talk about this?”**

| Approach | Behavior |
|----------|----------|
| **`synchronized` method/block** | Only **one thread** enters that method/block at a time |
| **`HashMap` + one `ReentrantLock`** | Map is thread-safe, but **all** ops are serialized (unrelated keys wait) |
| **`HashMap` + per-key `ReentrantLock`** | Same-key ops serialize; **different keys** can proceed in parallel — the core idea behind why `ConcurrentHashMap` scales better (JDK is more sophisticated: stripes/CAS, but mental model is the same) |

**This LLD’s rate limiter:** `synchronized allow(...)` → correct and simple, but **global** — Alice EMAIL and Bob SMS wait on each other. At scale: Redis / per-key lock / CHM `compute` for that window key.

**“Per-user OTP rate limit at scale?”**  
Redis INCR + TTL / token bucket before enqueue (we demo in-memory window).

**“Exactly-once with provider retries?”**  
Store terminal state by `requestId`; ignore duplicate delivery callbacks.

**“Why not Observer?”**  
Routing is pick-one-channel-by-type. Observer = many handlers on one event (fan-out).
