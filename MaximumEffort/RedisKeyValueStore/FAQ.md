# Redis LLD — FAQ (Questions I Had While Learning)

Companion notes for `RedisKeyValueStore/`. Interview design stays in `README.md`. Global Java concurrency primitives are in `../LEARNINGS.md`.

---

## Q1. What is `implements Delayed`? What is "Delayed" here?

**Delayed** is a Java interface used only with `DelayQueue`.

It tells the queue:

> "Don't give me this object until a certain amount of time has passed."

For example,

```text
ExpiryEntry("user1", 5000)
```

means: don't return this object for 5 seconds.

When those 5 seconds finish,

```java
delayQueue.take()
```

returns that object.

So **Delayed** is simply Java's way of representing:

> "An object that becomes available only after some delay."

---

## Q2. What is `compareTo()` doing?

Java needs to know how to compare two `ExpiryEntry` objects.

We tell Java:

```java
@Override
public int compareTo(Delayed other) {
    return Long.compare(this.expiryTimeMs,
                        ((ExpiryEntry<?>) other).expiryTimeMs);
}
```

Meaning: **earlier expiry time = higher priority.**

Example:

- Key A expires at 10 sec
- Key B expires at 5 sec

Queue internally becomes:

```text
Head
 ↓
 B
 ↓
 A
```

because B expires first.

Without `compareTo()`, Java wouldn't know how to order the heap.

---

## Q3. What is `DelayQueue`?

It is a Java concurrent collection.

Package: `java.util.concurrent`

Internally it's basically:

```text
Priority Queue (Min Heap)
+
Blocking Queue
```

It gives you both:

- Elements stay sorted by expiry time
- `take()` automatically waits until the head has expired

---

## Q4. Difference between Queue and DelayQueue?

### Normal Queue

```text
A
↓
B
↓
C
```

Calling `take()` returns **A** immediately.

### DelayQueue

Suppose:

- A expires after 10 sec
- B expires after 5 sec

Internally:

```text
Head
 ↓
 B
 ↓
 A
```

If you call `take()` before 5 seconds, **it blocks**.

At exactly 5 seconds, **B** comes out.

After another 5 seconds, **A** comes out.

That's why `DelayQueue` is perfect for TTL.

---

## Q5. What is `ReentrantReadWriteLock`?

Think of it as two locks: **Read Lock** and **Write Lock**.

### Read Lock

```text
Thread A → Read
Thread B → Read
Thread C → Read
```

All allowed simultaneously.

### Write Lock

```text
Thread A writing
↓
Everyone waits
```

Only one writer.

This is perfect for Redis because **Reads >>> Writes**. Most operations are `get()`. Very few are `put()`.

---

## Q6. Why not use `synchronized`?

Because `synchronized` allows **ONE thread total** — even if 100 threads only want to read.

`ReadWriteLock` allows:

- 100 readers, **OR**
- 1 writer

Much higher throughput.

---

## Q7. What is `ExecutorService`?

Think of it as **Java's Thread Manager**.

Instead of creating threads manually:

```java
Thread t = new Thread(...);
```

we ask `ExecutorService`: please run this work in another thread.

Example:

```java
ExecutorService service = Executors.newSingleThreadExecutor();
service.submit(task); // runs task in the background
```

---

## Q8. What does this mean?

```java
cleanerService.submit(this::processActiveEviction);
```

It means: run `processActiveEviction()` on another thread.

Equivalent to:

```java
cleanerService.submit(() -> processActiveEviction());
```

Even older Java style:

```java
cleanerService.submit(new Runnable() {
    public void run() {
        processActiveEviction();
    }
});
```

All three are identical.

---

## Q9. Why can't we simply call `processActiveEviction()` inside the constructor?

Because `processActiveEviction()` contains `while (isRunning)` which never ends.

If the constructor does `processActiveEviction()`:

```text
Constructor
 ↓
Infinite loop
 ↓
Constructor never finishes
 ↓
Object never created
```

Game over.

Instead:

```text
Constructor
 ↓
Start background thread
 ↓
Constructor finishes immediately
 ↓
Cleaner runs forever separately
```

---

## Q10. Why do we need `shutdownNow()` if we already do `isRunning = false`?

Excellent question.

The loop is:

```java
while (isRunning) {
    delayQueue.take();
}
```

Problem: suppose the queue is empty. `take()` **blocks forever**.

Then `isRunning = false` changes nothing — the thread is still sleeping.

`shutdownNow()` interrupts the thread. The interrupted thread immediately exits:

```text
take()
 ↓
InterruptedException
 ↓
break
```

Now the thread dies immediately.

So:

| Tool | Role |
|------|------|
| `isRunning = false` | stops future loops |
| `shutdownNow()` | wakes the sleeping thread |

**Both are needed.**

---

## Q11. Why is there write-lock code inside `get()`?

Initially we only want to read.

```text
Read Lock
 ↓
Get node
```

If the node isn't expired → return value. Done.

But suppose the node **is** expired. Now we must delete it. Deleting modifies the `HashMap`. Modification requires a **Write Lock**.

That's why:

```text
Read
 ↓
Found expired
 ↓
Release read lock
 ↓
Acquire write lock
 ↓
Delete
 ↓
Return null
```

---

## Q12. Why not just return `null`?

Because then the expired key would remain forever in memory.

Eventually millions of expired keys accumulate.

Passive eviction says:

> If I discover an expired key during `get()`, I'll clean it immediately.

---

## Q13. What is `<K,V>` after class names?

Example: `class RedisKeyValueStore<K,V>`

Those are **Java Generics**.

Instead of fixing the type to `String` or `Integer`, we say **any type**.

Example:

```java
RedisKeyValueStore<String, Integer>
RedisKeyValueStore<Integer, User>
```

Same class. Different types. No duplicate code.

---

## Q14. Why do we write `new ValueNode<>(...)` instead of `new ValueNode<String>()`?

The Java compiler already knows `V` from the variable type.

So `new ValueNode<>()` is simply shorthand — exactly the same meaning.

---

## Q15. Why do we use `DelayQueue` instead of scanning the whole map every second?

### Scanning approach

```text
Every second
 ↓
Visit every key
 ↓
Check expiry
```

Time complexity: **O(N)** every scan.

### DelayQueue approach

```text
Insert key
 ↓
Heap
 ↓
Cleaner sleeps
 ↓
Automatically wakes when earliest key expires
```

- Insertion: **O(log N)**
- Removal: **O(log N)**

No unnecessary scanning. Much more efficient.

---

## Q16. Why do we check `node.isExpired()` again after taking from `DelayQueue`?

Suppose `user1` with TTL = 5 sec gets inserted.

`DelayQueue` contains: `user1 @ 5 sec`

After 2 seconds, someone updates `user1` with TTL = 20 sec.

`DelayQueue` now contains:

- Old entry (5 sec)
- New entry (20 sec)

When the **old** entry wakes up, we must verify: is the **current** node expired?

If not, don't delete — otherwise we'd delete the fresh value accidentally.

That's why every active eviction does:

```java
if (node != null && node.isExpired())
```

before removing.
