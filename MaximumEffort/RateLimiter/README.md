# LLD — Thread-Safe Rate Limiter Framework

## 1. Requirement & Scope Clarification (00:00 – 00:05)

### Functional Requirements

- **API Throttling:** Decide if a request from a client (`client_id` or IP) is allowed → `allowRequest(clientId) -> boolean`
- **Pluggable Algorithms (Strategy Pattern):**
  - **Token Bucket** — allows bursts, smooth refill
  - **Sliding Window Log / Counter** — accurate window boundaries
- **Configurability:** Different limits per client (e.g. Tier-1 VIP = 100 QPS, free tier = 10 QPS)

### Non-Functional Requirements

- **Low Latency & High Throughput:** `allowRequest()` sub-millisecond, **O(1)**
- **Thread Safety:** Thousands of concurrent requests without race conditions or memory leaks

---

## 2. Core Architecture & Strategy Pattern Design (00:05 – 00:12)

```
                       ┌─────────────────────────────────────┐
                       │     RateLimiterService (Facade)     │
                       └──────────────────┬──────────────────┘
                                          │ delegates
                                          ▼
                       ┌─────────────────────────────────────┐
                       │     RateLimitStrategy (Interface)   │
                       └──────────────────┬──────────────────┘
                                          │
             ┌────────────────────────────┴────────────────────────────┐
             ▼                                                         ▼
  TokenBucketRateLimiter                                SlidingWindowRateLimiter
  ConcurrentHashMap<ClientId, Bucket>                    ConcurrentHashMap<ClientId, Window>
```

### Key Technical Decisions

- **Lock-Free Token Refill Optimization:** Do **not** run a background thread per client. Refill **lazily on access**:

```
newTokens = min(capacity, currentTokens + Δt × refillRate)
```

- **Strategy + Factory Pattern:** Decouples algorithms from callers; allows dynamic switching / per-tier config.

---

## 3. Code (one file)

```
RateLimiter/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/RateLimiter/code
javac Main.java && java Main
```

---

## 4. Concurrency Model & Distributed Extension (00:30 – 00:40)

### Local Concurrency

- **Fine-Grained Striped Locks:** Lock is per `TokenBucket` instance (keyed by `clientId`), avoiding global contention across different users.
- **Non-Blocking Compute:** `ConcurrentHashMap.computeIfAbsent()` ensures atomic creation of client bucket instances.

### Distributed Rate Limiting (System Design Extension)

When scaling across multiple app servers, local in-memory maps don’t share state. Use **Redis with Lua Scripts**:

```lua
-- Redis Token Bucket Lua Script (Atomic execution)
local key = KEYS[1]
local limit = tonumber(ARGV[1])
local window = tonumber(ARGV[2])
local now = tonumber(ARGV[3])

local current = redis.call('get', key)
if current and tonumber(current) >= limit then
    return 0 -- Reject
else
    redis.call('incrby', key, 1)
    if not current then
        redis.call('pexpire', key, window)
    end
    return 1 -- Allow
end
```

---

## 5. SOLID Principles & Design Patterns Checklist

### Design Patterns Implemented

- **Strategy Pattern:** `RateLimitStrategy` allows runtime swapping between `TokenBucketRateLimiter`, `LeakyBucketRateLimiter`, and `SlidingWindowLogRateLimiter`.
- **Facade Pattern:** `RateLimiterService` provides a simple interface hiding algorithm details and client map lookups.
- **Factory Method / Lazy Initialization:** `ConcurrentHashMap.computeIfAbsent()` dynamically initializes buckets on first request.

### SOLID Principles Breakdown

- **S (Single Responsibility):** `TokenBucket` handles token arithmetic; `RateLimiterService` handles strategy delegation; `RateLimitConfig` holds thresholds.
- **O (Open/Closed):** Add `LeakyBucketRateLimiter` by implementing `RateLimitStrategy` without modifying existing classes.
- **L (Liskov Substitution):** Any strategy can be passed seamlessly to `RateLimiterService`.
- **I (Interface Segregation):** `RateLimitStrategy` is minimal — single method `allowRequest`.
- **D (Dependency Inversion):** Callers depend on `RateLimitStrategy`, not concrete bucket classes.

---

## 6. Senior Interview Follow-up Q&A (Google / Meta / Stripe)

**“How do you prevent memory leaks from inactive clients in memory?”**  
Wrap client buckets in a cache with time-based eviction (e.g. Guava/Caffeine `expireAfterAccess(1, HOURS)`), or run a background cleanup purging buckets where `lastRefillTimestamp` is older than 1 hour.

**“What is the difference between Token Bucket and Leaky Bucket?”**  
Token Bucket allows bursts (up to capacity) and variable processing speed. Leaky Bucket enforces a smooth constant output rate (FIFO queue) and discards bursts that exceed queue size.

**“How do you handle Distributed Race Conditions?”**  
Use Redis with atomic Lua scripts to eliminate read-modify-write races, or Redis `INCR` with dynamic TTL expirations.
