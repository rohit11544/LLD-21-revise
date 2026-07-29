# Global LEARNINGS — Java & Concurrency Revision (LLD)

Shared across all MaximumEffort problems. Interview design stays in each folder’s `README.md`.

**Appears in:** Redis, Transactional KV, Rate Limiter, Delayed Scheduler, Pub/Sub, Logger, Kafka-style systems.

---

## PART A — Concurrency (revise this first)

Everything that keeps multi-threaded LLD correct and efficient.

---

### A1. Threads & Runnable

<span style="font-size:1.15em; line-height:1.7">

A **thread** is a worker that can run code in parallel inside one JVM.

```text
Main thread    → put / get
Cleaner thread → DelayQueue.take() forever
```

- **`t.start()`** — new OS thread runs `run()` (real parallelism)
- **`t.run()`** — same thread, **no** parallelism

Prefer **`ExecutorService`** over raw `new Thread(...)` (start / leak / shutdown are easy to get wrong).

**Shared memory needs locks.** Two threads mutating the same `HashMap` without sync → races / corruption.

**Runnable & run()**

`Runnable` = a task for another thread. Override `run()`.

When an executor later does `task.run()`, it just calls that override:

```java
Task t = new Task(...) {
    @Override
    public void run() {
        System.out.println("Hello");
    }
};

// later, on a worker thread:
task.run();  // prints Hello — implementation comes from the client object
```

</span>

---

### A2. ExecutorService (thread manager)

<span style="font-size:1.15em; line-height:1.7">

```java
ExecutorService ex = Executors.newSingleThreadExecutor();
// or: Executors.newFixedThreadPool(5)

ex.submit(task);
```

- **`shutdown()`** — finish current work, then stop
- **`awaitTermination(3, SECONDS)`** — wait up to 3 seconds for workers to finish
- **`shutdownNow()`** — interrupt now (needed if blocked on `take()`)

```java
ex.shutdown();
if (!ex.awaitTermination(3, TimeUnit.SECONDS)) {
    ex.shutdownNow();
}
```

**LLD uses:** Redis TTL cleaner, async logger, scheduler worker pool, Kafka consumers.

</span>

---

### A2b. ScheduledExecutorService (timed ExecutorService)

<span style="font-size:1.15em; line-height:1.7">

`ScheduledExecutorService` **is** an `ExecutorService` — it extends it. You still get `submit` / `execute` / `shutdown`. What’s **extra** is **time-based scheduling**, not a totally different world.

**Plain `ExecutorService`**

- Run a task **now** (or when a worker is free)
- No built-in “wait N ms” or “every N ms”

**What `ScheduledExecutorService` adds**

| API | Meaning |
|-----|---------|
| `schedule(...)` | Run once after a delay |
| `scheduleAtFixedRate(...)` | Run periodically (fixed rate) |
| `scheduleWithFixedDelay(...)` | Run periodically (fixed gap after each finish) |

```java
ScheduledExecutorService sweeper =
        Executors.newSingleThreadScheduledExecutor();

sweeper.scheduleAtFixedRate(
        this::sweepExpiredCarts,
        intervalMs,   // initialDelay — wait before first run
        intervalMs,   // period — wait between runs
        TimeUnit.MILLISECONDS);
```

Same value twice = first sweep after N ms, then every N ms. You could pass `0` then `intervalMs` to run once immediately.

**LLD uses:** Reservation seat-lock sweeper, Shopping Cart TTL sweeper.

</span>

---

### A3. Locks

<span style="font-size:1.15em; line-height:1.7">

**synchronized / ReentrantLock**

One thread in the critical section. Always unlock in `finally`.

```java
lock.lock();
try {
    // critical section
} finally {
    lock.unlock();
}
```

**Reentrant** means the same thread can lock again (A → B → lock again) without deadlock.

**ReentrantReadWriteLock**

- **`readLock()`** — many readers together
- **`writeLock()`** — one writer; blocks everyone

Use when **reads >> writes** (Redis `get`).

Passive eviction flow: `read` → if expired → release read → `write` → delete

</span>

---

### A4. Condition

<span style="font-size:1.15em; line-height:1.7">

Package: `java.util.concurrent.locks.Condition`

`ReentrantLock`’s version of `wait()` / `notify()`. Sleep until a condition is true **without busy-waiting**.

**Bad (busy wait — wastes CPU):**

```java
while (taskQueue.isEmpty()) {
    // spin
}
```

**Good:**

```java
Condition condition = lock.newCondition();

while (taskQueue.isEmpty()) {
    condition.await();   // sleep, ~0% CPU until signaled
}
```

- **`await()`** — sleep until another thread signals
- **`signal()`** — wake **one** waiter
- **`signalAll()`** — wake **all** waiters

**LLD — Delayed Task Scheduler**

```java
// Scheduler
while (taskQueue.isEmpty()) {
    newEarliestTaskCondition.await();
}

// Producer (schedule)
taskQueue.offer(task);
newEarliestTaskCondition.signal();
```

**Rule:** Pair `Condition` with `ReentrantLock`. Call `await` / `signal` only while holding the lock.

</span>

---

### A5. ThreadLocal (one object per thread)

<span style="font-size:1.15em; line-height:1.7">

A normal field is shared → races. `ThreadLocal` = each thread has its own copy.

```text
Thread A → counter = 5
Thread B → counter = 0   // never interfere
```

```java
ThreadLocal<Deque<Map<K, Object>>> txnStack =
        ThreadLocal.withInitial(ArrayDeque::new);
// same as: ThreadLocal.withInitial(() -> new ArrayDeque<>())
```

- **`withInitial(...)`** — lazy create on first `get()` for that thread
- **`get()`** — **this** thread’s object only

**LLD:** Transactional KV — each thread’s own txn stack.

</span>

---

### A6. Concurrent collections

<span style="font-size:1.15em; line-height:1.7">

**BlockingQueue**

- **`poll()`** — empty queue → returns `null`
- **`take()`** — empty queue → **blocks** until an element arrives

Producer `put` / consumer `take` — no busy poll.

**DelayQueue (+ Delayed)**

`BlockingQueue` of `Delayed` objects. `take()` waits until **delay = 0** (TTL / expiry).

Internally a min-heap by expiry → **O(log N)**.

```java
// ExpiryEntry implements Delayed
long getDelay(TimeUnit unit);   // time left until available
int compareTo(Delayed other);   // earlier expiry = higher priority
```

**ConcurrentHashMap + `compute` / `computeIfAbsent`**

**Difference:** `HashMap` + `synchronized` / one lock uses **one global lock** (even unrelated keys wait). `ConcurrentHashMap` uses fine-grained lock / CAS → different keys can proceed together.

**Mental model:** `HashMap` + **per-key `ReentrantLock`** ≈ “same-key locking” that makes CHM scale better. JDK is more sophisticated, but that is the interview idea.

**`compute(key, fn)`:** atomic get → create-or-update → put **for that key only**. Does **not** serialize other keys (`IPHONE` and `MACBOOK` can run in parallel).

**`computeIfAbsent`:**

```java
map.computeIfAbsent(key, k -> new Value(...));
// return existing, else create + insert + return — atomically
```

**Map vs object lock (Shopping Cart):**  
`compute()` protects the **map entry**. `ReentrantLock` on `InventoryItem` protects **stock fields** when `reserve`/`release` run outside `compute`. No lock while `new InventoryItem(...)` — not shared yet.

**When HashMap + RWLock still wins:** multi-key root **commit** needs one atomic batch across many keys — CHM alone does not give that.

**Also upcoming:** `AtomicInteger` / `AtomicLong` (counters, Kafka offsets).

</span>

---

### A — Concurrency cheat sheet

<span style="font-size:1.15em; line-height:1.7">

- **`start()` vs `run()`** — parallel vs same-thread
- **`ExecutorService`** — thread manager; `awaitTermination` → maybe `shutdownNow`
- **`ScheduledExecutorService`** — ExecutorService + `schedule` / `scheduleAtFixedRate` / `scheduleWithFixedDelay`
- **`Runnable.run()`** — invokes the task’s override
- **`ReentrantLock`** — better `synchronized`; unlock in `finally`
- **`ReadWriteLock`** — many readers **or** one writer
- **`Condition`** — `await` / `signal` — no busy wait
- **`ThreadLocal`** — one value per thread
- **`BlockingQueue.take`** — block until element
- **`DelayQueue`** — block until expiry (`Delayed`)
- **`ConcurrentHashMap`** — different keys in parallel; `compute` = per-key create/update; object lock ≠ map lock

</span>

---

## PART B — Language helpers

Shorter; needed so concurrency APIs make sense.

---

### B1. Generics (`<T>`, `<K,V>`)

<span style="font-size:1.15em; line-height:1.7">

```java
class Box<T> { T value; }
class Pair<K, V> {}

Box<String> b = new Box<>();
new ValueNode<>(...);  // diamond — compiler knows V
```

**Rule:** if it works for `String` / `User` / `Order` → make it generic.

**LLD:** `KeyValueStore<K,V>`, `ValueNode<V>`, `ExpiryEntry<K>`.

</span>

---

### B2. Interfaces (contracts)

<span style="font-size:1.15em; line-height:1.7">

```java
interface Animal {
    void speak();
}
```

- **`Comparable`** — sorting / heap order via `compareTo`
- **`Runnable`** — task for another thread (`run`) — see Part A
- **`Delayed`** — DelayQueue timing — see Part A

</span>

---

### B3. Lambda & method references

<span style="font-size:1.15em; line-height:1.7">

```java
executor.submit(new Runnable() {
    public void run() { process(); }
});

executor.submit(() -> process());

executor.submit(this::process);   // same thing
```

Redis: `cleanerService.submit(this::processActiveEviction)`.

</span>

---

### B3b. `Supplier` / lazy callback (don’t run yet)

<span style="font-size:1.15em; line-height:1.7">

```java
return idempotencyEngine.executeIdempotent(
        request,
        () -> doProcess(request));   // Supplier — recipe, NOT result
```

**What happens**

1. `() -> doProcess(request)` builds a **Supplier** (a deferred block). `doProcess` does **not** run here.  
2. `executeIdempotent(...)` runs **first** (lock key, check cache).  
3. Only if needed: `executionBlock.get()` → then `doProcess` runs.  
4. That response is returned (and maybe cached).

```text
executeIdempotent
  ├─ cache hit?  → return cached   (doProcess NEVER runs)
  └─ else        → supplier.get() → doProcess → return
```

Same idea as `Optional.orElseGet(() -> expensive())` — **lazy**, only if needed.

**Why pass a Supplier instead of calling `doProcess` first?**  
So duplicates never hit the bank. If you wrote `doProcess(request)` as the argument, the charge would already have happened before the idempotency check.

**LLD use:** Payment Gateway idempotency wrapper.

</span>

---

### B4. Everyday collections (not concurrent)

<span style="font-size:1.15em; line-height:1.7">

**Deque as a stack** (prefer over `Stack`):

```java
Deque<T> stack = new ArrayDeque<>();
stack.push(x);
stack.pop();
stack.peek();
```

**PriorityQueue — offer vs add**

Both insert; same on a normal `PriorityQueue`. Prefer `offer` (Queue API). Used with `Comparable` for heap order (scheduler).

</span>

---

## PART C — LLD pattern note

### Tombstone (Transactional KV)

<span style="font-size:1.15em; line-height:1.7">

Don’t physically delete inside a txn. Store `key → TOMBSTONE` so rollback can restore.

```text
put(A) → remove(A) → rollback()  ⇒  A comes back
```

</span>

---

## Must-know order (Google L4)

<span style="font-size:1.15em; line-height:1.7">

**Concurrency first:**

1. Threads / `Runnable` / `ExecutorService` / `ScheduledExecutorService`
2. `ReentrantLock` / `ReadWriteLock` / `Condition`
3. `ThreadLocal`
4. `BlockingQueue` / `DelayQueue`
5. `ConcurrentHashMap` + `computeIfAbsent`
6. `AtomicInteger` / `AtomicLong` *(when they appear)*

**Then language / patterns:** Generics · Interfaces · Lambda · `Supplier` (lazy) · Deque · Tombstone

</span>
