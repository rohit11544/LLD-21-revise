# LLD — In-Memory Cache (LRU / LFU)

Google L4–sized: fixed capacity, `get` / `put` in **O(1)**, swappable eviction via Strategy.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- `get(key)` / `put(key, value)` with fixed capacity `C`
- Eviction policies (Strategy):
  - **LRU** — least recently accessed
  - **LFU** — least frequently accessed; same-freq tie → **LRU** among that bucket

### Non-functional

- Strict **O(1)** get/put (including list / freq moves)
- Thread-safe under concurrent access
- New policy (FIFO, …) without editing `Cache`

---

## 2. Architecture (00:05 – 00:12)

```
                            Cache<K,V> (Facade)
                                   │
                                   ▼
                      EvictionStrategy<K,V>
                    ┌──────────────┴──────────────┐
                    ▼                             ▼
           LRUEvictionStrategy           LFUEvictionStrategy
           (one DLL)                     (freq → DLL + minFrequency)
```

**LRU:** `HashMap` (in Cache) + DLL — touch → move to head; evict `tail.prev`.  
**LFU:** `Map<freq, DLL>` + `minFrequency` — touch → move to `freq+1` bucket; evict from `minFrequency` list tail (LRU tie-break).

---

## 3. Code (one file)

```
InMemoryCache/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/InMemoryCache/code
javac Main.java && java Main
```

---

## 4. Concurrency

`get` and `put` both take the **write** lock — reading updates eviction state (LRU move / LFU freq).

Say in interview: *“RWLock documents read vs write intent, but because get mutates, a plain `ReentrantLock` is equally honest. True shared reads only apply to size()-style queries.”*

---

## 5. SOLID

| | How |
|--|-----|
| **S** | `Cache` = storage + locking · Strategy = eviction order only · DLL = list ops |
| **O** | Add `FIFOEvictionStrategy` without editing `Cache` |
| **L** | Any `EvictionStrategy` plugs into the same `get`/`put` flow |
| **I** | Tiny strategy API: `keyAccessed` / `keyAdded` / `evictKey` |
| **D** | `Cache` depends on `EvictionStrategy`, not LRU/LFU concretes |

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Client calls `get` / `put` / `size`; `Cache` hides map, locks, and eviction bookkeeping |
| **Strategy** | `LRUEvictionStrategy` / `LFUEvictionStrategy` — swap policy at construct time |

---

## 7. Interview Q&A

**“Why dummy head/tail?”**  
No null checks on empty / single-node lists; add/remove stay O(1).

**“LFU same frequency — who goes?”**  
Within a freq bucket, DLL order = recency; `removeLast` = LRU among that freq.

**“Distributed Redis?”**  
Exact global LRU/LFU needs heavy sync. Production often uses **approximated** eviction (sample K keys, drop the worst) for good hit rate without cluster-wide locks.
