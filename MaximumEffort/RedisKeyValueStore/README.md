# LLD — Core Redis-Like Key-Value Store

## 1. Requirement & Scope Clarification (00:00 – 00:05)

### Functional Requirements

- **Core CRUD:** O(1) `put(key, value)`, `get(key)`, `delete(key)`
- **TTL & Expiration:** `put(key, value, ttlMillis)` — expired keys never returned on `get()`
- **Dual Eviction Strategy:**
  - **Passive (Lazy):** purge on access during `get()`
  - **Active (Daemon):** purge via `DelayQueue` in O(log N) — no O(N) full map scans

### Non-Functional Requirements

- **Thread Safety & High Throughput:** safe for thousands of concurrent readers/writers
- **Zero CPU Spikes:** TTL cleaner blocks on expired entries (no busy-wait)
- **Clean Abstraction:** `KeyValueStore<K,V>` separate from `RedisKeyValueStore<K,V>` (SOLID)

---

## 2. Architecture & Design Patterns (00:05 – 00:12)

```
                            ┌──────────────────────────────────┐
                            │    KeyValueStore<K, V> Interface │
                            └────────────────┬─────────────────┘
                                             │ implements
                                             ▼
                      ┌──────────────────────────────────────────────┐
                      │          RedisKeyValueStore<K, V>            │
                      └──────┬────────────────────────────────┬──────┘
                             │                                │
                             ▼                                ▼
              ┌─────────────────────────────┐  ┌─────────────────────────────┐
              │      HashMap<K, Node>       │  │  DelayQueue<ExpiryEntry<K>> │
              │  Protected by RWLock        │  │  (O(log N) Head-Check TTL)  │
              └─────────────────────────────┘  └─────────────────────────────┘
```

### Key Architectural Decisions

- **DelayQueue for Active Eviction:** Min-heap by expiry; cleaner `take()` blocks until head expires (0% CPU when idle).
- **ReentrantReadWriteLock + HashMap:** Shared reads; exclusive writes so Map + DelayQueue stay consistent (vs CHM for this teaching design).

---

## 3. Code (one file)

```
RedisKeyValueStore/
  README.md       ← interview design (this file)
  FAQ.md          ← Redis-specific Q&A while learning the code
  code/Main.java

Java / concurrency basics (global): ../LEARNINGS.md
```

```bash
cd MaximumEffort/RedisKeyValueStore/code
javac Main.java && java Main
```

---

## 4. Complexity, Concurrency & SOLID

### Time & Space Complexity

| Operation | Time | Space |
|-----------|------|-------|
| `get(key)` | O(1) | O(1) |
| `put(key, val)` (no TTL) | O(1) | O(1) |
| `put(key, val, ttl)` | O(log N) heap insert | O(1) |
| `delete(key)` | O(1) | O(1) |
| Active eviction | O(log N) per expired key | O(N) heap |

### Concurrency Model

- **Shared-read / exclusive-write** via `ReentrantReadWriteLock`
- **Atomic eviction:** active + passive removals under write lock so clients never see dirty expired values

### Design Patterns / Mechanisms

- **Dual Eviction Mechanisms:** Combines **passive (lazy)** eviction on access during `get()` with **active** eviction via a background daemon consuming an O(log N) `DelayQueue`. (Not Strategy Pattern — both run together; there is no pluggable `EvictionStrategy` interface.)
- **Facade:** Clients interact only with `get()`, `put()`, and `delete()`; `RedisKeyValueStore` internally handles locking, TTL expiration, and background cleanup.

### SOLID Principles Used

- **S — Single Responsibility Principle (SRP):** Each class has one responsibility (`ValueNode` stores data, `ExpiryEntry` handles TTL scheduling, `RedisKeyValueStore` manages CRUD operations).
- **O — Open/Closed Principle (OCP):** The `KeyValueStore` interface allows new storage implementations (e.g., `DiskBackedStore`, `DistributedStore`) without changing client code.
- **L — Liskov Substitution Principle (LSP):** Any implementation of `KeyValueStore` can replace another without affecting correctness.
- **I — Interface Segregation Principle (ISP):** `KeyValueStore` exposes only essential CRUD operations; clients aren't forced to depend on unnecessary methods.
- **D — Dependency Inversion Principle (DIP):** Clients depend on the `KeyValueStore` abstraction rather than the concrete `RedisKeyValueStore` implementation (internal dependencies are still concrete, so DIP is partially followed).

---

## 5. Bridging the Mental Gap: Redis vs Redis + Transactions

| Concept | Pure Redis (Cache) | Redis + Transactions (DB) |
|---------|--------------------|---------------------------|
| Primary Goal | Fast O(1) lookup & TTL purge | Isolation (ACID), atomic multi-key ops |
| Write Execution | Applied directly to main map | Scratchpad / local layer first |
| ThreadLocal Stack | ❌ Not needed | ✅ Isolate uncommitted writes per thread |
| Commit | ❌ None (ops immediate) | ✅ Flush scratchpad to global store atomically |
| Rollback | ❌ None | ✅ Discard scratchpad |
| Tombstones | ❌ Direct `remove()` | ✅ Mask lower layers during active txn |

See also: `TransactionalKVStore/` for the transaction-stack version.
