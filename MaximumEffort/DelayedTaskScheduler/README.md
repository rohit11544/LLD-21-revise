# LLD — Delayed Task Scheduler

## 1. Requirement & Scope Clarification (00:00 – 00:05)

### Functional Requirements

- **Schedule Tasks:** `schedule(Task task, long delayMs) -> taskId`
- **Recurring Tasks:** `scheduleRecurring(Task task, long initialDelayMs, long intervalMs) -> taskId` with **zero execution drift**
- **Task Cancellation:** `cancel(String taskId) -> boolean` in **O(1)** before execution
- **Execution Engine:** Run tasks as close to deadline as possible via worker thread pools

### Non-Functional Requirements

- **Monotonic Time Safety:** Use `System.nanoTime()` so NTP clock jumps don’t corrupt order
- **Thread Safety & Zero Busy-Waiting:** Daemon sleeps on condition `await`/`signal` when idle — **0% CPU** spin loops
- **Exception Shielding & Fault Isolation:** Task `RuntimeException`s must not crash worker threads or the scheduler daemon

---

## 2. Core Architecture & Design Patterns (00:05 – 00:12)

```
                            ┌─────────────────────────────────┐
                            │      CustomTaskScheduler        │
                            └────────────────┬────────────────┘
                                             │ submits/cancels
                                             ▼
                 ┌────────────────────────────────────────────────────────┐
                 │          PriorityQueue<ScheduledTask>                  │
                 │      (Min-Heap sorted by scheduledExecutionNano)       │
                 └───────────────────────┬────────────────────────────────┘
                                         │
                                         ▼
                 ┌────────────────────────────────────────────────────────┐
                 │       Scheduler Daemon Thread (ReentrantLock)          │
                 │     (Blocks on newEarliestTaskCondition.await)         │
                 └───────────────────────┬────────────────────────────────┘
                                         │ dispatches ready tasks
                                         ▼
                 ┌────────────────────────────────────────────────────────┐
                 │     ExecutorService (Worker Thread Pool)               │
                 │     (Executes Task wrapped in Exception Shield)        │
                 └────────────────────────────────────────────────────────┘
```

### Key Technical Decisions

- **Explicit PriorityQueue + ReentrantLock + Condition:** Over `PriorityBlockingQueue` / `DelayQueue` for fine-grained control when an earlier task is inserted at the heap head.
- **Monotonic Non-Drifting Recurrence:** Next run = `previousScheduledTime + interval` (not `now + interval`).
- **O(1) Soft-Delete Cancellation:** `cancel()` sets `cancelled = true` and removes from `taskMap` in O(1); heap entry purged when popped.

---

## 3. Code (one file)

```
DelayedTaskScheduler/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/DelayedTaskScheduler/code
javac Main.java && java Main
```

---

## 4. Concurrency Model & Distributed Extension (00:30 – 00:40)

### Local Complexity

| Operation | Time | Space |
|-----------|------|-------|
| `schedule()` / `scheduleRecurring()` | O(log N) | O(1) |
| `cancel()` (soft delete) | O(1) | O(1) |
| peek next task | O(1) | O(1) |
| execute and poll | O(log N) | O(1) |

### Distributed Task Scheduler Schema

When scaling across nodes, persist tasks in DB with lease locks or Redis ZSET by timestamp:

```sql
CREATE TABLE scheduled_tasks (
    task_id VARCHAR(64) PRIMARY KEY,
    payload TEXT NOT NULL,
    execution_time_nano BIGINT NOT NULL,
    recurrence_interval_nano BIGINT NULL,
    status VARCHAR(20) NOT NULL, -- PENDING, PROCESSING, COMPLETED, CANCELLED
    locked_by VARCHAR(50) NULL,   -- Node ID leasing the task
    locked_until TIMESTAMP NULL
);

CREATE INDEX idx_tasks_execution ON scheduled_tasks(execution_time_nano, status);
```

---

## 5. SOLID Principles & Design Patterns Checklist

### Design Patterns Implemented

- **Producer-Consumer:** Scheduler daemon produces work; `ExecutorService` workers consume.
- **Command Pattern:** `Task` extends `Runnable`, encapsulating business execution.
- **Facade Pattern:** `CustomTaskScheduler` simplifies submit / cancel / lifecycle.

### SOLID Principles Breakdown

- **S:** `ScheduledTask` = timestamps + cancel flag; `CustomTaskScheduler` = queue/timing; `Task` = business logic.
- **O:** Add business tasks by extending `Task` without changing scheduler internals.
- **L:** Subclasses of `Task` implement `Runnable` transparently.
- **I:** Clean API — `schedule`, `scheduleRecurring`, `cancel`.
- **D:** Scheduler depends on abstract `Task` / `Runnable`, not concrete worker logic.

---

## 6. Senior Interview Follow-up Q&A (Google / Meta / Databricks)

**“Why not use DelayQueue?”**  
DelayQueue works for basic one-shot delays. A custom `PriorityQueue` + `ReentrantLock` + `Condition` gives explicit control over non-drifting recurrence, soft-delete semantics, and `taskMap` cleanup.

**“Why System.nanoTime() instead of System.currentTimeMillis()?”**  
`currentTimeMillis()` is wall-clock time and can jump (NTP). `nanoTime()` is monotonic — only moves forward — so deadlines stay consistent.

**“How do you prevent time drift on recurring tasks?”**  
Use `nextRun = previousScheduledExecutionNano + interval`, not `now + interval`. Even if execution is late, the schedule stays on fixed intervals.

**“How do you handle task starvation if the queue grows to millions?”**  
Scheduler only does O(1)/O(log N) dispatch to the pool. Apply backpressure (reject when `taskMap` exceeds capacity) and scale out with a distributed delay buffer (e.g. Redis ZSET).
