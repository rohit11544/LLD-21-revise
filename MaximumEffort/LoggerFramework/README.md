# LLD — Concurrent Logger Framework

## 1. Requirement & Scope Clarification (00:00 – 00:05)

### Functional Requirements

- **Log Levels & Hierarchy:** `DEBUG < INFO < WARN < ERROR < FATAL`. Filtering follows severity hierarchy.
- **Log Formatting:** `LogFormatter` Strategy (`PlainTextFormatter` shipped; JSON = another impl if asked).
- **Multiple Output Sinks:** Console, File via `LogSink` Strategy (Database/Remote as future sinks).
- **Async & Sync Modes:** Sync logging and async non-blocking logging via a background queue buffer.

### Non-Functional Requirements

- **Zero Application Blocking:** Async logger returns in sub-microseconds without waiting for disk/network I/O.
- **Thread Safety:** Many threads logging at once without corruption or lost logs.
- **Extensibility:** Add sinks/levels without rewriting core (Open-Closed).

---

## 2. Core Architecture & Design Patterns (00:05 – 00:12)

```
       [Application Thread] ──► Logger (Facade)
                                   │
                                   ▼
                   ┌────────────────────────────────┐
                   │ Chain of Responsibility        │
                   │ (InfoLogger -> ErrorLogger)    │
                   └───────────────┬────────────────┘
                                   │ passes valid LogMessage
                                   ▼
                   ┌────────────────────────────────┐
                   │ AsyncLogSinkManager            │
                   │ (LinkedBlockingQueue Buffer)   │
                   └───────────────┬────────────────┘
                                   │ Worker Daemon Thread
                                   ▼
                       LogAppender / Sink Strategy
                       ┌───────────┬───────────┐
                       ▼           ▼           ▼
                   ConsoleSink  FileSink   DatabaseSink
```

### Key Technical Decisions

- **Chain of Responsibility (exact-level):** Each handler matches **one** level (`INFO` handler writes INFO only). Avoids double-write into the same sinks. Example chain: `INFO → WARN → ERROR` (code ships INFO → ERROR; WARN can sit in the middle the same way).
- **Strategy for Sinks vs Formatters:** **Formatter** = how the line looks. **Sink** = where it goes (`ConsoleSink` → stdout, `FileSink` → file).
- **Producer-Consumer Async Queue:** App threads `offer` `LogMessage` into a `LinkedBlockingQueue`; a worker drains and writes all sinks (I/O off the caller).

---

## 3. Code (one file)

```
LoggerFramework/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/LoggerFramework/code
javac Main.java && java Main
```

---

## 4. Interview talking points (Google L4)

### Why `poll(100, MILLISECONDS)` instead of `poll()` / `take()`?

Worker loop:

```java
LogMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
```

| API | If queue empty | Problem |
|-----|----------------|---------|
| `poll()` | Returns `null` **immediately** | Tight spin → **CPU burn** |
| `take()` | **Blocks forever** | Harder to notice `isRunning = false` and exit cleanly |
| `poll(100, ms)` | Wait up to 100ms, then `null` | Sleep a bit, check shutdown flag, repeat |

`LinkedBlockingQueue` = thread-safe FIFO (producer–consumer). **100 is milliseconds, not seconds.**

### End-to-end flow

```text
Logger constructed
  → build chain (e.g. INFO → ERROR; WARN can sit in the middle)
  → start AsyncLogSinkManager worker (processQueue)

addSink(ConsoleSink(PlainTextFormatter))

logger.info("x")
  → start at INFO handler → match → write/dispatch
  → forward to ERROR → no match → end of chain
  → LogMessage{level=INFO, ...} already in queue
  → worker timed-poll → sinks write (level still on the LogMessage)

logger.error("y")
  → INFO → no match → ERROR → match → write/dispatch
  → end of chain
  → same queue → worker → sinks write ERROR line
```

INFO and ERROR share **one** queue. Difference is **not** “which queue” — each `LogMessage` carries its `level`.

### Why separate handlers if both `write()` call `dispatch()` today?

**Today:** both handlers end at the same `sinkManager.dispatch(message)` — output path looks the same.

**Why keep different handlers anyway:**

- `write()` can **differ by level** in a real system (ERROR → metrics / pager; INFO → sinks only).
- CoR still demos extensibility (add WARN handler in the middle without changing callers).
- Be honest in interview: with only `info()` / `error()` and identical `write()`, CoR is partly pattern showcase; value jumps when `write()` diverges or you use a generic `log(level, msg)` API.

---

## 5. Concurrency Model & Follow-Up Q&A

### Local Complexities & Performance

- **Zero Main-Thread Blocking:** `logger.info()` is O(1) via `offer` to a bounded queue; disk latency stays on the background thread.
- **Double-Checked Locking Singleton:** Single global `Logger` across threads.

### Top Interview Follow-Up Questions (Google / Meta / Stripe)

**“How do you handle backpressure if the logging queue fills up?”**  
Configure drop strategies: (1) Drop lowest severity first (DEBUG/INFO), (2) Caller-Runs (fall back to sync logging on caller thread), or (3) Ring Buffer / LMAX Disruptor for ultra-high throughput.

**“How do you guarantee log ordering across multi-threaded applications?”**  
Timestamps plus an atomic FIFO `BlockingQueue` keep dispatch order aligned with insertion order into the queue.

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `Logger` exposes `info` / `error` / `addSink` / `shutdown`; hides CoR chain, async queue, and sinks |
| **Singleton** | `Logger.getInstance()` (double-checked locking) — one global logger |
| **Chain of Responsibility** | Exact-level handlers: `InfoLoggerHandler` → `ErrorLoggerHandler` (WARN can sit in the middle) |
| **Strategy** | `LogFormatter` (how it looks) · `LogSink` (where it goes: Console / File) |
| **Producer–Consumer** | App threads `offer` to `LinkedBlockingQueue`; worker drains and writes sinks |

**Not Observer:** the app pushes logs into the logger; subscribers are not push-notified.

---

## 7. SOLID Principles

| | How applied |
|--|-------------|
| **S — Single Responsibility** | `LogMessage` = data · Handlers = level routing · `AsyncLogSinkManager` = queue/worker · Sinks = destination · Formatters = string shape · `Logger` = client API |
| **O — Open/Closed** | Add sink / formatter / handler (e.g. JSON formatter, DB sink, WARN handler) without changing caller code |
| **L — Liskov Substitution** | Any `LogSink` / `LogFormatter` / handler subclass can replace another where the abstraction is used |
| **I — Interface Segregation** | Small APIs: `LogFormatter.format`, `LogSink.write` / `close` |
| **D — Dependency Inversion** | Sinks depend on `LogFormatter`; manager depends on `LogSink` list, not a concrete console-only writer |

**Interview one-liner:** *“Facade + Singleton logger, CoR for levels, Strategy for format/sink, async Producer–Consumer so callers don’t block on I/O.”*
