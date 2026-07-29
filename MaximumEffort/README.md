# MaximumEffort — LLD Master Prep

Target companies: **Google, Meta, Uber, Stripe, Atlassian** (and similar).

Work style: you bring the solution for each problem one by one; we implement/refine under this folder following your instructions.

**Global Java / concurrency notes:** [`LEARNINGS.md`](./LEARNINGS.md) (threads, locks, queues, ConcurrentHashMap, etc.)  
**Flash cards (classes / patterns / SOLID):** [`REVISE.md`](./REVISE.md)

---

## 21 Master Problems

1. **In-Memory File System** — Google Drive, Dropbox, Local File Explorer, S3 Metadata Engine  
2. **Key-Value Store with TTL & Transactions** — Redis Clone, Memcached, Transactional KV Store  
3. **Thread-Safe Rate Limiter** — API Throttler, Hit Counter, Token Bucket Service  
4. **Delayed Task Scheduler** — Cron Job Executor, Job Queue Runner, Distributed Poller  
5. **Pub-Sub Messaging Queue** — Kafka Clone, RabbitMQ Engine, In-Memory Message Broker  
6. **Parking Lot System** — Multi-Level Parking, Smart Toll Plaza, Valet Management  
7. **Elevator Control System** — Smart Lift Dispatcher, Multi-Elevator Controller  
8. **ATM / Vending Machine** — Snack Dispenser, Automated Kiosk State Machine  
9. **Ride Sharing (Uber)** — Lyft Clone, Ola Routing Engine, Food Delivery Driver Matching  
10. **Reservation System** — BookMyShow, Hotel Booking, Flight Booking, Restaurant Table Booking  
11. **Splitwise** — Expense Sharing App, Bill Splitter, Group Debt Simplifier  
12. **Logger Framework** — Application Log Aggregator, Multi-Sink Logging System  
13. **Notification System** — Multi-Channel Alert Service, Push/SMS Engine, Email Router  
14. **Jira / Issue Tracker** — Trello Board, Bug Tracker, Workflow State Engine  
15. **Payment Gateway** — Payment Router, Merchant Billing System, Idempotent Transaction Handler  
16. **In-Memory Cache (LRU/LFU)** — LRU Cache, LFU Cache, Pluggable Eviction Cache  
17. **ACL / RBAC Framework** — Role-Based Access Control, Permission Management Engine  
18. **Meeting Scheduler** — Calendar Slot Allocation, Room Booking Engine, Interval Search  
19. **Shopping Cart & Inventory** — E-Commerce Checkout Engine, Stock Reservation Engine  
20. **Social Chat / Messenger** — WhatsApp LLD, Real-time Chat Buffer, Messenger Status Engine  
21. **Ledger / Wallet Engine** — Digital Wallet System, Double-Entry Accounting Engine  

*(Chess removed from scope — not needed for Google L4 prep.)*

---

## Universal 45-Minute Interview Timeline

Every 45-minute LLD interview follows this strict budget:

| Time | Block | What you do |
|------|--------|-------------|
| **00:00 – 00:05** | Scope & Requirements | Clarify functional vs. non-functional constraints (e.g. thread safety, memory limits). |
| **00:05 – 00:12** | Entities & Class Diagram | Define core models, Enums, and primary Interfaces (get buy-in from the interviewer). |
| **00:12 – 00:30** | Executable Code | Write clean, modular code applying SOLID principles and core design patterns. |
| **00:30 – 00:40** | Concurrency, DB Schema & Follow-ups | Thread-safe locks, database/schema relations, and requirement changes. |
| **00:40 – 00:45** | Dry Run & Trade-offs | Trace code with a sample test case and discuss edge cases. |

This blueprint applies to **every** problem on the list above.

---

## Progress by Block

### Block 1: Infrastructure & Queueing Systems — **100% COMPLETE**

| Status | # | Problem | Focus |
|--------|---|----------|--------|
| ✅ | — | Core Redis-Like KV Store *(pure cache, no txns)* | Dual TTL eviction, RWLock + HashMap, DelayQueue daemon → `RedisKeyValueStore/` |
| ✅ | 2 | Key-Value Store with TTL & Transactions | Facade, O(log N) DelayQueue eviction, ThreadLocal txn stack → `TransactionalKVStore/` |
| ✅ | 3 | Thread-Safe Rate Limiter | Strategy, Token Bucket, Sliding Window Log, fine-grained locks → `RateLimiter/` |
| ✅ | 4 | Delayed Task Scheduler | Producer-Consumer, PriorityQueue + monotonic nanoTime(), non-drifting recurrence → `DelayedTaskScheduler/` |
| ✅ | 5 | Pub-Sub Messaging Queue (Kafka L4) | Strategy, Broker Facade, offsets + GroupCoordinator, pull poll → `PubSubMessagingQueue/` |
| ✅ | 12 | Concurrent Logger Framework | Chain of Responsibility, async concurrent sinks, producer-consumer buffer → `LoggerFramework/` |

---

### Block 2: State Machines & Real-World Operations — **100% COMPLETE**

*Focus: State Machine Pattern + Time-Bound TTL Locks + Optimistic Concurrency*

| Status | # | Problem | Focus |
|--------|---|----------|--------|
| ✅ | 10 | Reservation System (BookMyShow / Hotel / Flight) | Enum state machine, per-seat TTL locks, sweeper, lock ordering → `ReservationSystem/` |
| ✅ | 9 | Ride Sharing (Uber / Lyft) | Grid spatial index, Strategy pricing/matching, per-driver tryLock → `RideSharing/` |
| ✅ | 7 | Elevator Control System | LOOK heaps, dispatcher Strategy, hall-direction pickup → `ElevatorSystem/` |
| ✅ | 14 | Jira / Issue Tracker | Graph workflows, TransitionGuard Strategy, Observer, audit → `IssueTracker/` |
| ✅ | 20 | Shopping Cart & Inventory | Per-SKU locks, cart TTL sweeper, discount Strategy → `ShoppingCart/` |
| ✅ | 8 | ATM / Vending Machine *(preview completed earlier)* | State Pattern, CoR → `ATM/` + `VendingMachine/` |

---

### Block 3: Structural Trees, Graphs & Data Abstractions — **100% COMPLETE**

*Focus: Cache eviction (HashMap + DLL), hierarchical structures, interval trees, composite models, graphs*

| Status | # | Problem | Focus |
|--------|---|----------|--------|
| ✅ | 16 | In-Memory Cache (LRU / LFU) | Strategy, HashMap + DLL / freq buckets → `InMemoryCache/` |
| ✅ | 1 | In-Memory File System | Composite File/Dir, path walk, per-node RWLock → `InMemoryFileSystem/` |
| ✅ | 18 | ACL / RBAC Framework | Role hierarchy flatten + permission-scoped ACL → `RBAC/` |
| ✅ | 19 | Meeting Scheduler | Interval calendars, Strategy room pick, ordered locks → `MeetingScheduler/` |
| ✅ | 11 | Splitwise | Strategy splits, cents, greedy debt simplify → `Splitwise/` |

---

### Block 4: Financial & Messaging — **100% COMPLETE**

*Focus: Idempotency, double-entry, async dispatch, Observer chat*

| Status | # | Problem | Focus |
|--------|---|----------|--------|
| ✅ | 15 | Payment Gateway | Adapter + idempotency + ordered fallback → `PaymentGateway/` |
| ✅ | 22 | Ledger / Wallet Engine | Double-entry journal, wallets, compensating reverse → `Ledger/` |
| ✅ | 13 | Notification System | Priority queue, channel Strategy, provider fallback → `NotificationSystem/` |
| ✅ | 21 | Social Chat / Messenger | Observer sessions, offline buffer, per-recipient receipts → `SocialChat/` |

---

**21 core problems shipped** (Blocks 1–4). Chess intentionally out of scope. Revise notes in `REVISE.md`.
