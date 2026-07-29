# LLD — Transactional Key-Value Store with TTL

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **Core CRUD:** `get(key)`, `put(key, value, ttlMillis)`, `remove(key)` in average **O(1)** via `KeyValueStore<K,V>`
- **TTL:** Expired keys must never be returned on `get()`
- **Transactions (nested):**
  - `begin()` → new txn layer on current thread
  - `commit()` → merge into parent layer, or atomically into global store
  - `rollback()` → discard current layer
- **Tombstones:** `remove` inside a txn masks keys from lower layers / global store

### Non-Functional Requirements

- Readers see consistent data; root `commit()` applies all writes in **one atomic write pass**
- Expired keys cleaned efficiently (**O(log N)** via `DelayQueue`), not by scanning the whole map

---

## 2. Architecture (00:05 – 00:12)

```
                 KeyValueStore<K,V>
                         │
                         ▼
              InMemoryKeyValueStore
         HashMap + ReentrantReadWriteLock
         DelayQueue + cleaner thread (TTL)

                 TransactionManager
         ThreadLocal<Deque<Map<K,Object>>>
         [Top Txn] → [Parent Txn] → [Global]
```

| Piece | Role |
|-------|------|
| `HashMap` + **RWLock** | O(1) storage; atomic multi-key root commit |
| `DelayQueue` | O(log N) wake when next key expires |
| `ThreadLocal` stack | nested txns, isolated per thread |
| `TOMBSTONE` | delete inside txn without touching global yet |

---

## 3. Code (one file)

```
TransactionalKVStore/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/TransactionalKVStore/code
javac Main.java && java Main
```

---

## 4. Concurrency Model & Database Schema (00:30 – 00:40)

### Concurrency & Locking Strategy (read carefully)

**Why we care:**  
Many threads may `get`/`put` at once. A root `commit()` may update **many keys**. We must not show half-committed data, and we must not let TTL cleanup fight with writers unsafely.

**1) ReadWriteLock on the global HashMap**

- We use `ReentrantReadWriteLock` (not `ConcurrentHashMap`) on purpose.
- **Read lock** for `get` → many readers together OK.
- **Write lock** for `put` / `remove` / root `commit` → only one writer.
- Plain words: *during a root commit, we take the write lock once and flush every key in that transaction. Other threads wait. That is the atomic commit.*

**2) Why not only ConcurrentHashMap?**

- CHM is great for single-key ops.
- A **multi-key commit** needs “all keys appear together.” CHM does not give you one lock for the whole batch.
- So: plain `HashMap` + one RWLock = simple story for interviews.

**3) DelayQueue cleaner thread**

- Expired entries sit in a `DelayQueue` ordered by expiry time.
- Cleaner thread calls `take()` and **blocks** until the next key is due → almost **0% CPU** when nothing expires.
- That is **O(log N)** insert/remove in the queue, not O(N) scan of the map.
- On `get`, we also do a **lazy check** (`isExpired()`) so even before the cleaner runs, expired keys are not returned.

**4) ThreadLocal transaction stack**

- Each thread has its own `Deque` of maps.
- Thread A’s uncommitted writes are invisible to Thread B.
- Nested `begin()` = push another map; `rollback()` = pop; nested `commit()` = merge into parent map.

### Database Schema (if persisted)

```sql
CREATE TABLE kv_store (
    key VARCHAR(255) PRIMARY KEY,
    value TEXT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NULL
);

CREATE INDEX idx_kv_expires_at ON kv_store(expires_at);
```

**What this table is for (say this):**

- `kv_store` → durable copy of key/value  
- `expires_at` → NULL means no TTL; index helps a DB sweeper find due keys (same idea as DelayQueue)

---

## 5. High-Yield Interview Follow-ups (Google L4)

**Prep focus for L4:** Transactional KV with TTL + nested txns + thread safety + **MVCC (high level)** is enough. Invest ~95% here.

**Do not** spend hours on WAL internals, Raft internals, Paxos, log compaction, or leader election — those show up more in L5/L6 or dedicated distributed-systems rounds. Know **what** WAL and Raft are (one sentence each); only go deeper if the interviewer pulls you there.

---

### 5.1 MVCC (Multi-Version Concurrency Control) — go deep if asked

**Problem with our current design**

- Today, a root `commit()` takes a **write lock on the whole map**.
- During that lock, **all readers wait**, even if they want unrelated keys.
- Under high QPS (Stripe / ads / caching), that becomes a bottleneck.

**What MVCC means (plain English)**

- Don’t overwrite a key in place.
- Keep **multiple versions** of the same key, each stamped with a version number or timestamp.
- Example:
  - `user:42` → version 1 = `"Alice"` (created at txn T10)
  - `user:42` → version 2 = `"Alice-Updated"` (created at txn T25)
- A **reader** picks the newest version that is **visible** to its snapshot (e.g. “all commits with txn id ≤ my start time”).
- A **writer** creates a **new version**; it does not block readers of older versions.

**How you would explain it in the interview**

1. Assign every transaction a monotonically increasing id: `T1, T2, T3…`
2. On commit of a write to `key`, append `(key, value, txnId)` instead of replacing the only slot.
3. `get(key)` under snapshot `S` returns the value with the largest `txnId ≤ S` that is not deleted.
4. Deletes are also versions (tombstone version), not immediate physical removal.
5. Later, a GC / compaction removes versions no active snapshot still needs.

**What you gain**

- Readers rarely block writers (and vice versa) → much better concurrency than one global write lock.
- You can offer **snapshot isolation**: a long read sees a consistent point-in-time view.

**What you trade**

- More memory (many versions).
- More complex `get` (find right version).
- Need compaction / vacuum.

**Say this sentence:**  
*“Our LLD uses a coarse RWLock for atomic multi-key commit. At scale I’d move to MVCC so readers use snapshots and writers append versions instead of locking the whole map.”*

---

### 5.2 WAL & Raft — know the name, not the internals (L4)

If they ask *“How would you make this production ready?”* — a perfect **20–30 second** L4 answer is:

> Currently this is a single-process in-memory transactional KV store. To productionize it, I’d add **WAL** for crash recovery, **MVCC** to improve concurrency, and **Raft-based replication** if we needed a distributed deployment.

That’s enough. If they want more detail, they’ll ask. Otherwise, move on.

| Topic | One-liner to memorize |
|-------|------------------------|
| **WAL** | *“For durability I’d append every committed transaction to a Write-Ahead Log before applying it to memory.”* |
| **Raft** | *“For multiple machines I’d replicate commits using Raft so every node applies them in the same order.”* |

**Mental model (optional, if they nudge):**

- **WAL** → crash on one machine shouldn’t lose committed txns (log first, then memory).
- **Raft** → multiple machines shouldn’t diverge (same commit order everywhere).

You do **not** need to explain fsync, log compaction, leader election, or Paxos unless they explicitly go there.

---

## 6. Design Patterns Used

- **Facade Pattern (Structural):** `TransactionManager` exposes a simple API (`begin()`, `commit()`, `rollback()`, `get()`, `put()`, `remove()`) while hiding transaction layers, locking, TTL handling, and commits to the global store.
- **Decorator-like Layering (Structural):** `TransactionManager` sits on top of `InMemoryKeyValueStore`, intercepting operations and adding transaction semantics without changing the storage engine.
- **Thread-Per-Context (Concurrency):** Every thread gets its own independent transaction stack (`ThreadLocal`), preventing interference between concurrent transactions.

---

## 7. SOLID Principles Applied (talk this clearly)

### S — Single Responsibility Principle (SRP)

- **InMemoryKeyValueStore** → map storage + locks + TTL queue only  
- **TransactionManager** → nested stack begin/commit/rollback only  
- **ValueNode** → value + expiry only  
- **ExpiryEntry** → DelayQueue ordering only  

**Say:** *“The store does not know about nesting. The txn manager does not own the DelayQueue.”*

### O — Open/Closed Principle (OCP)

- New backend (`RocksDBKeyValueStore`) implements `KeyValueStore` — TransactionManager can sit on top without rewrite  

**Say:** *“I extend storage by new implementations of the interface.”*

### L — Liskov Substitution Principle (LSP)

- Both `InMemoryKeyValueStore` and `TransactionManager` implement `KeyValueStore`  
- Client can call `get`/`put`/`remove` the same way  

**Say:** *“Caller only needs KeyValueStore; txn wrapper is a drop-in.”*

### I — Interface Segregation Principle (ISP)

- Interface stays tiny: `get`, `put`, `remove`  
- `begin`/`commit`/`rollback` stay on `TransactionManager` (not forced on every store)  

**Say:** *“Simple stores aren’t forced to implement transactions.”*

### D — Dependency Inversion Principle (DIP)

- `TransactionManager` depends on `InMemoryKeyValueStore` / `KeyValueStore` abstraction for global flush  
- Not on a specific DB file format  

**Say:** *“Txn logic depends on the store abstraction.”*

---

## 8. Quick Interview Talking Points (under 60 seconds)

When asked: *“Explain patterns and SOLID”* — say:

1. **Facade:**  
   *“TransactionManager exposes begin/put/commit/rollback; clients never touch txn layers, locks, or the global flush.”*

2. **Decorator-like layering:**  
   *“It wraps InMemoryKeyValueStore and adds txn semantics without changing the store.”*

3. **Thread-per-context:**  
   *“Each thread has its own ThreadLocal txn stack, so concurrent txns don’t interfere.”*

4. **TTL + concurrency:**  
   *“Lazy expiry on get plus DelayQueue cleaner; HashMap + RWLock so multi-key root commit is one atomic write.”*

5. **SRP + DIP:**  
   *“Storage and transactions are separate classes; both speak KeyValueStore where it matters.”*
