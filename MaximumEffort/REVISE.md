# REVISE — Code Structure + Patterns + SOLID

Block 1: Redis · Transactional KV · Rate Limiter · Delayed Task Scheduler · Pub-Sub (Kafka L4).  
Block 2 so far: Reservation · Ride Sharing · Elevator · Issue Tracker · Shopping Cart · Vending Machine · ATM.  
Block 3 revise so far: In-Memory Cache (LRU/LFU) · In-Memory File System · RBAC · Meeting Scheduler · Splitwise.  
Block 4 revise so far: Payment Gateway · Ledger / Wallet · Notification System · Social Chat.

---

# 1. Redis-Like KV Store

```
KeyValueStore<K,V>  (interface)
        ▲
        │ implements
RedisKeyValueStore<K,V>
   uses → ValueNode<V>
   uses → ExpiryEntry<K>  (implements Delayed)
```

### Interface

```
KeyValueStore<K,V>
  + get(K key) → V
  + put(K key, V value)
  + put(K key, V value, long ttlMillis)
  + delete(K key) → boolean
```

### Classes

```
ValueNode<V>
  + ValueNode(V value, Long ttlMillis)
  + getValue() → V
  + getExpiryTimestamp() → Long
  + isExpired() → boolean

ExpiryEntry<K>  implements Delayed
  + ExpiryEntry(K key, long ttlMillis)
  + getKey() → K
  + getDelay(TimeUnit) → long
  + compareTo(Delayed) → int

RedisKeyValueStore<K,V>  implements KeyValueStore<K,V>
  fields: HashMap, DelayQueue, ReentrantReadWriteLock, ExecutorService
  + RedisKeyValueStore()          // starts cleaner
  + get / put / put(ttl) / delete
  - processActiveEviction()       // DelayQueue daemon
  + shutdown()
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Client only calls `get` / `put` / `delete`; store hides locks, TTL, cleaner |
| **Dual eviction** | Passive on `get` + active `DelayQueue` daemon (not Strategy — both always run) |

### SOLID — how applied

| | How |
|--|-----|
| **S** | `ValueNode` = data+expiry · `ExpiryEntry` = DelayQueue ordering · `RedisKeyValueStore` = CRUD + concurrency |
| **O** | New store (`DiskBackedStore`) implements `KeyValueStore` — clients unchanged |
| **L** | Any `KeyValueStore` impl can replace another |
| **I** | Interface = CRUD only; no cleaner/lock APIs forced on clients |
| **D** | Clients depend on `KeyValueStore`, not `RedisKeyValueStore` (internals still concrete → partial DIP) |

---

# 2. Transactional KV Store

```
KeyValueStore<K,V>  (interface)
        ▲
        │ implements          │ implements
InMemoryKeyValueStore    TransactionManager
        ▲                          │
        └──── wraps / uses ────────┘

Shared models: ValueNode<V>, ExpiryEntry<K> (Delayed)
```

### Interface

```
KeyValueStore<K,V>
  + get(K key) → V
  + put(K key, V value, Long ttlMillis)
  + remove(K key)
```

### Classes

```
ValueNode<V>
  + ValueNode(V, Long ttlMillis)
  + getValue() / getExpiryTimestamp() / isExpired()

ExpiryEntry<K>  implements Delayed
  + ExpiryEntry(K, long ttlMillis)
  + getKey() / getDelay() / compareTo()

InMemoryKeyValueStore<K,V>  implements KeyValueStore<K,V>
  fields: HashMap, RWLock, DelayQueue, cleaner ExecutorService
  + get / put / remove
  - expireIfStillDue(K)
  + getLock() → ReentrantReadWriteLock
  + shutdown()

TransactionManager<K,V>  implements KeyValueStore<K,V>
  fields: globalEngine, ThreadLocal<Deque<Map>>, TOMBSTONE
  + begin()
  + commit()
  + rollback()
  + isInTransaction() → boolean
  + get / put / remove          // layered over stack or global
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `begin` / `commit` / `rollback` / CRUD — hides stack, locks, TTL, root flush |
| **Decorator-like** | `TransactionManager` wraps `InMemoryKeyValueStore`; adds txn semantics without changing store |
| **Thread-per-context** | `ThreadLocal` txn stack — each thread isolated |
| **Tombstone** | `remove` in txn → `TOMBSTONE` so rollback can restore |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Store = map+TTL · TxnManager = nested begin/commit/rollback · Node/Entry = data only |
| **O** | New backend implements `KeyValueStore`; txn manager can sit on top |
| **L** | Both store and txn manager are `KeyValueStore` — drop-in for `get`/`put`/`remove` |
| **I** | Store interface has no `begin`/`commit`; txn APIs live only on `TransactionManager` |
| **D** | Txn logic talks to store abstraction / engine, not a file format |

---

# 3. Rate Limiter

```
RateLimitStrategy  (interface)
        ▲
        │ implements
TokenBucketRateLimiter          SlidingWindowLogRateLimiter
   (inner TokenBucket)

RateLimiterService  (Facade)
   depends on → RateLimitStrategy

RateLimitConfig  (capacity + window)
```

### Interface

```
RateLimitStrategy
  + allowRequest(String clientId) → boolean
```

### Classes

```
RateLimitConfig
  + RateLimitConfig(long capacity, long windowSizeMs)
  + getCapacity() / getWindowSizeMs()

TokenBucketRateLimiter  implements RateLimitStrategy
  + TokenBucketRateLimiter(RateLimitConfig)
  + allowRequest(clientId) → boolean
  inner TokenBucket:
    + tryConsume() → boolean
    - refill()

SlidingWindowLogRateLimiter  implements RateLimitStrategy
  + SlidingWindowLogRateLimiter(RateLimitConfig)
  + allowRequest(clientId) → boolean

RateLimiterService
  + RateLimiterService(RateLimitStrategy)
  + allowRequest(clientId) → boolean   // delegates
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Strategy** | Swap Token Bucket vs Sliding Window via `RateLimitStrategy` |
| **Facade** | `RateLimiterService` hides algorithm + per-client maps |
| **Lazy init** | `ConcurrentHashMap.computeIfAbsent` creates bucket/log on first request |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Config = thresholds · `TokenBucket` = token math · Service = delegation |
| **O** | Add `LeakyBucketRateLimiter` by implementing strategy — no edits to service |
| **L** | Any strategy plugs into `RateLimiterService` |
| **I** | Strategy = one method `allowRequest` |
| **D** | Service / callers depend on `RateLimitStrategy`, not concrete classes |

---

# 4. Delayed Task Scheduler

```
Runnable
   ▲
Task (abstract)  ←── Command (business work)
   ▲
client subclasses override run()

ScheduledTask  implements Comparable<ScheduledTask>
   holds Task + when + cancel + recurrence

CustomTaskScheduler  (Facade)
   PriorityQueue + Condition + worker pool
```

### Interface / abstract type

```
Task  extends Runnable   (abstract class)
  + Task(String description)
  + getDescription() → String
  + run()                    // from Runnable — client overrides
```

*(No separate interface file — Command = `Task` / `Runnable`.)*

### Classes

```
ScheduledTask  implements Comparable<ScheduledTask>
  + ScheduledTask(Task, delayMs, intervalMs)
  + getTaskId() / getTask() / getScheduledExecutionNano()
  + isRecurring() / isCancelled()
  + cancel()
  + createNextRecurringTask() → ScheduledTask   // no drift: prev + interval
  + compareTo(ScheduledTask) → int

CustomTaskScheduler
  fields: PriorityQueue, ConcurrentHashMap taskMap,
          ReentrantLock, Condition, ExecutorService, scheduler Thread
  + CustomTaskScheduler(int workerPoolSize)
  + schedule(Task, delayMs) → String taskId
  + scheduleRecurring(Task, initialDelayMs, intervalMs) → String
  + cancel(String taskId) → boolean
  + stop()
  - runScheduler()                  // await / signal loop
  - submitWithExceptionShield(Task)
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `schedule` / `scheduleRecurring` / `cancel` / `stop` — hides queue, lock, Condition, pool |
| **Command** | `Task` wraps work; scheduler only calls `run()` |
| **Producer-Consumer** | Daemon peels due tasks → worker pool executes |
| **Condition** | `await` when empty / not due; `signal` on new earliest task |

### SOLID — how applied

| | How |
|--|-----|
| **S** | `ScheduledTask` = timing+cancel · `CustomTaskScheduler` = queue/timing · `Task` = business logic |
| **O** | New jobs = extend `Task`; scheduler unchanged |
| **L** | Any `Task` subclass runs as `Runnable` |
| **I** | Public API is small: schedule / cancel / stop |
| **D** | Scheduler depends on `Task` / `Runnable`, not concrete job classes |

---

# 5. Pub-Sub Messaging Queue (Kafka L4–sized)

```
PartitionStrategy  (interface)
        ▲
        │ implements
HashPartitionStrategy

Producer ──► Broker (Facade) ──► Topic ──► Partition (List log)
                 ▲
Consumer ──► ConsumerGroup (offsets)
GroupCoordinator ──► assigns partitions to consumers
```

### Publish / Consume Flow

**Producer**

```text
Producer → Broker.publish() → Topic → Partition Strategy → Partition (append)
```

**Consumer**

```text
Consumer → ConsumerGroup (owns offsets) → Broker.poll() → Partition → Messages
        → Consumer processes → ConsumerGroup.commitOffset()
```

### Component details

| Component | Holds / does |
|-----------|----------------|
| **Message** | `[Key, Payload, Offset]` |
| **Partition** | `[List<Message>, nextOffset]` |
| **Topic** | `[List<Partition>, PartitionStrategy]` |
| **Partition Strategy** | `hash(key) % numPartitions` |
| **Broker** | `[Map<TopicName, Topic>]` |
| **Producer** | `Broker.publish(topic, key, payload)` |
| **Consumer** | `[AssignedPartitionIds]` + `poll()` |
| **Consumer Group** | `[List<Consumer>, Map<PartitionId, Offset>]` |
| **Group Coordinator** | `rebalance()` |

*Scale-up only if asked: Partition → StorageEngine → Segments.*

### Interface

```
PartitionStrategy
  + getPartition(String key, int totalPartitions) → int
```

### Classes

```
Message
  + Message(key, payload, offset)
  + getKey() / getPayload() / getOffset()

HashPartitionStrategy  implements PartitionStrategy
  + getPartition(key, totalPartitions) → int

Partition
  fields: List<Message>, AtomicLong nextOffset, ReentrantReadWriteLock
  + append(key, payload)
  + read(offset, batchSize) → List<Message>

Topic
  + Topic(name, count, strategy)
  + getPartition(key) → Partition

Broker
  + createTopic(name, partitions)
  + publish(topic, key, payload)
  + poll(topic, partition, offset, batchSize) → List<Message>
  + getTopic(name) → Topic

Producer
  + send(topic, key, payload)

Consumer
  + assign / clearAssignments
  + poll(Broker, ConsumerGroup, batchSize)

ConsumerGroup
  + addConsumer / getOffset / commitOffset

GroupCoordinator
  + rebalance(ConsumerGroup, Topic)
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Strategy** | `PartitionStrategy` — Hash / RoundRobin / Sticky without changing `Topic` |
| **Facade** | `Broker`: `createTopic` / `publish` / `poll` — hides topics, partitions, routing |
| **Producer–Consumer** | Producers append; consumers **pull** via `poll` |
| **Coordinator** | `GroupCoordinator` owns rebalance / partition assignment |

**Not Observer:** Observer = subject **pushes** to subscribers. Here the consumer **explicitly polls** — pull, not push.

### SOLID — how applied

| | How |
|--|-----|
| **S** | `Producer`=publish · `Broker`=route · `Topic`=partitions · `Partition`=log · `ConsumerGroup`=consumers+offsets · `GroupCoordinator`=rebalance · `HashPartitionStrategy`=pick partition |
| **O** | Add `RoundRobinPartitionStrategy` without editing `Topic` / `Broker` |
| **L** | Any `PartitionStrategy` plugs into `Topic` |
| **I** | Strategy = only `getPartition(...)` |
| **D** | `Topic` depends on `PartitionStrategy`, not `HashPartitionStrategy` |

---

# 6. Reservation System (BookMyShow / Hotel / Flight)

```
ReservationService (Facade)
        │
        ▼
SeatLockManager  — activeLocks + ScheduledExecutor sweeper
        │
        ▼
Seat (status + ReentrantLock)     SeatLock (userId + TTL nano)
        │
        ▼
AVAILABLE → HELD → CONFIRMED
HELD → AVAILABLE (expire / release)
```

### Enums / classes

```
SeatStatus: AVAILABLE | HELD | CONFIRMED
SeatCategory: REGULAR | PREMIUM | VIP (+ price)

Seat
  + getLock() / getStatus() / setStatus()

SeatLock
  + isExpired()   // nanoTime TTL

Booking
  + bookingId, userId, seats, totalAmount

SeatLockManager
  + acquireLock(seat, userId, ttl)
  + validateLockOwner(seatId, userId)
  + clearLock(seatId)      // on confirm
  + releaseLock(seat)      // HELD → AVAILABLE
  - sweepExpiredLocks()    // every ~1s

ReservationService
  + getAvailableSeats()
  + holdSeats(ids, userId[, ttl])   // sort IDs, all-or-nothing
  + confirmBooking(ids, userId)     // sort IDs, clearLock
  + shutdown()
```

### Locking lifecycle (must remember)

**Hold**

```text
lock(seat) → AVAILABLE? → HELD + SeatLock → activeLocks[id] → unlock
```

**Confirm (before TTL)**

```text
validate owner → lock(seat) → CONFIRMED → clearLock → unlock
```

**Expire (never pays)**

```text
sweeper → expired? → lock(seat) → AVAILABLE → remove activeLocks → unlock
```

### Two kinds of “locks”

| | `ReentrantLock` | `SeatLock` |
|--|-----------------|------------|
| Purpose | Protect shared memory | Reserve seat for a user |
| Duration | Milliseconds | Seconds–minutes (TTL) |
| Stored in | `Seat` | `activeLocks` map |
| Remember | Protects the **code** | Protects the **business resource** |

### Why sort seat IDs?

Alice `A1,A2` + Bob `A2,A1` without sort → deadlock.  
With sort (always A1 then A2) → same lock order → no deadlock.  
Used in both `holdSeats` and `confirmBooking`.

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `ReservationService` — hold / confirm / availability |
| **Enum state machine** | `SeatStatus` guarded under seat lock |
| **Per-seat locking** | `ReentrantLock` on each `Seat` |
| **Lock ordering** | Sorted seat IDs before multi-lock |
| **TTL sweeper** | `ScheduledExecutorService.scheduleAtFixedRate` |

*In-memory = pessimistic per seat. DB OCC (`UPDATE … WHERE version=?`) = scale-out talk only.*

### SOLID — how applied

| | How |
|--|-----|
| **S** | Seat / SeatLock / LockManager / Service / Booking split |
| **O** | New category or `Map<ShowId, seats>` without rewriting lock protocol |
| **I** | Thin API: availability, hold, confirm |
| **D** | Service orchestrates lock manager + inventory (concrete OK for LLD) |

---

# 7. Ride Sharing (Uber / Lyft)

```
RideSharingService (Facade)
     ┌──────────┼──────────┐
     ▼          ▼          ▼
SpatialIndex  PricingStrategy  DriverMatchingStrategy
(grid cells)  (Standard…)      (Nearest…)
     │
     ▼
Driver (status + ReentrantLock) + Trip (enum SM)
```

### Enums / classes

```
DriverStatus: OFFLINE | AVAILABLE | ON_TRIP
TripStatus: CREATED → DRIVER_ASSIGNED → IN_PROGRESS → COMPLETED | CANCELLED

Location
  + distanceTo() / toGridKey()

Driver / Rider
Trip
  + assignDriver / startTrip / completeTrip / cancelTrip

PricingStrategy → StandardPricingStrategy
DriverMatchingStrategy → NearestDriverStrategy

SpatialIndexManager
  + updateDriverLocation / removeDriver
  + findNearbyAvailableDrivers(pickup, radius)  // O(K) in cell

RideSharingService
  + updateDriverStatus / updateDriverLocation
  + requestRide(...) → Trip | null
  + startTrip / completeTrip / cancelTrip
```

### Why enum status — not State Strategy?

State Strategy shines when **each status owns different logic** (Vending/ATM).

Here behavior lives in **service + spatial index + `tryLock`**. Status is mostly:

- Can match? (`AVAILABLE`)
- In grid? (`AVAILABLE` only)
- Busy? (`ON_TRIP`)

→ **enum state machine**, not Strategy-as-State.

### Concurrency

| Mechanism | Achieves |
|-----------|----------|
| Grid-scoped search | O(K) in cell — not all drivers |
| Per-driver `tryLock` | No double-assign; loser tries next |
| Double-check `AVAILABLE` | Race-safe under lock |
| Index hygiene | Leave grid on OFFLINE/ON_TRIP; re-enter on AVAILABLE |

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call online/location, `requestRide`, start/complete/cancel; service hides index, matching, locks, trip SM |
| **Strategy** | Pricing + matching (**not** driver status) |
| **Enum state machine** | `DriverStatus`, `TripStatus` |
| **Per-driver locking** | `tryLock` on dispatch |
| **Spatial index** | Grid map (mechanism) |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Domain / index / strategies / service split |
| **O** | New pricing/matching strategy without editing dispatch |
| **L** | Any strategy impl plugs into the service |
| **I** | `calculateFare` / `matchDriver` only |
| **D** | Service depends on strategy abstractions |

---

# 8. Elevator Control System

```
ElevatorSystemFacade
        │
        ▼
ElevatorDispatcherStrategy  (which car gets a hall call)
        │
        ▼
ElevatorCar[]  — LOOK: up min-heap + down max-heap (+ upSet/downSet dedupe)
```

### Hall vs car (real world → code)

| Real world | API |
|------------|-----|
| Outside ▲/▼ on floor F | `requestElevator(F, UP/DOWN)` → `addHallRequest` |
| Inside floor buttons | `pressFloorButton(carId, dest)` → `addDestinationFloor` |

**One-liner:** Hall = stop + remember UP/DOWN intent (applied on door open). Car button = destination only. `stepAll` = one floor per tick until peek matches.

### Classes / APIs

```
Direction: UP | DOWN | IDLE
ElevatorStatus: MOVING | STOPPED | OUT_OF_SERVICE

ElevatorCar
  + addHallRequest(floor, dir) / addDestinationFloor(floor)
  + step()
  - enqueueFloor / applyHallDirectionIfPresent
  - upRequests (min-heap) / downRequests (max-heap)

LookDispatcherStrategy
  + selectElevator(cars, Request)   // distance + direction + load score

ElevatorSystemFacade
  + requestElevator / pressFloorButton / stepAll
```

### End-to-end flow (short)

```text
Facade(2 cars @ floor 1)
  → requestElevator(5, UP) → dispatcher → addHallRequest
  → stepAll… until peek==5 → openDoors → applyHallDirectionIfPresent
  → pressFloorButton(car, 8) → enqueueFloor(8)
  → stepAll… until peek==8 → openDoors → queues empty → IDLE
```

### LOOK vs Dispatcher

| Piece | Role |
|--------|------|
| **LOOK on car** | Order stops in current direction, then reverse |
| **Dispatcher** | Which car gets the hall call |

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Hall, car button, step — hides queues + LOOK |
| **Strategy** | `ElevatorDispatcherStrategy` |
| **LOOK algorithm** | Dual `PriorityQueue`s per car |
| **Enum state** | `Direction`, `ElevatorStatus` |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Car = motion/queues · Dispatcher = assign · Facade = API |
| **O** | New dispatcher without changing cars |
| **L/I/D** | Pluggable `selectElevator(...)` abstraction |

---

# 9. Jira / Issue Tracker (Dynamic Workflow)

```
IssueService (Facade)
     ┌──────────┼──────────┐
     ▼          ▼          ▼
WorkflowRegistry  Issue store  Audit + Observers
     │
  Workflow graph: Status --Transition(+Guards)--> Status
```

**Interview mental model**

```text
                    IssueService
                         │
     ┌───────────────────┼────────────────────┐
     │                   │                    │
     ▼                   ▼                    ▼
 WorkflowRegistry     Issue Store        Audit Log
     │
     ▼
  Workflow (Graph) → Transition (Edge) → TransitionGuard
     │
     ▼
 Update Issue → Notify Observers (outside the lock)
```

*Hierarchy:* parent/child ids link issues; cascade-close = Observer talk (not coded).

### Component cheat sheet

| Component | Responsibility |
|-----------|----------------|
| Issue | Issue data |
| Status | State value object |
| Workflow | Transition graph per type |
| Transition | One legal edge |
| TransitionGuard | Lambda validation (Strategy) |
| WorkflowRegistry | Workflow for `IssueType` |
| AuditLog | Transition + field history |
| IssueEventListener | Side effects after success |
| IssueService | Orchestrates all (Facade) |

### Complete execution flow

```text
START → Statuses → Workflow graph (OPEN→IN_PROGRESS→IN_REVIEW→CLOSED)
      → Register in WorkflowRegistry → IssueService → add listeners

createIssue()
  → registry workflow → initial OPEN → Issue → issueStore
  → optional parent/child link

assignIssue() / setCustomField()
  → Lock → update → AuditLog(FIELD_UPDATE) → Unlock

transitionIssue()
  → Lock → find edge → guards → status + AuditLog(TRANSITION) → Unlock
  → Notify observers (outside lock)
       demo: println | can plug: Email / Slack / Analytics / cascade-close
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `IssueService` — create/assign/fields/transition; hides graph, guards, audit, listeners |
| **Graph state machine** | Config-driven statuses (not hardcoded enum states) |
| **Strategy** | `TransitionGuard` (`@FunctionalInterface` / lambda) |
| **Observer** | Listeners after unlock |
| **Per-issue lock** | Safe concurrent transitions |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Data / graph / guards / service / audit-listeners split |
| **O** | New workflow/edges without editing `Issue` |
| **L/I** | Any guard/listener plugs into tiny interfaces |
| **D** | Service → registry + guard/listener abstractions |

---

# 10. Shopping Cart & Inventory

```
            ShoppingCartService (Facade)
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
   InventoryManager   Cart(+TTL)   DiscountStrategy
   (per-SKU locks)    sweeper
```

**Lifecycle:** `ACTIVE → CHECKED_OUT → PAID` · `ACTIVE | CHECKED_OUT → CANCELLED_EXPIRED` (TTL → release stock)

### Enums / classes

```
CartStatus: ACTIVE → CHECKED_OUT → PAID
            ACTIVE | CHECKED_OUT → CANCELLED_EXPIRED

Product
  + sku / name / price

InventoryItem          // per-SKU stock + ReentrantLock
  + addStock / reserve / release / deductOnPurchase
  + getLock()

InventoryManager
  + addStock(sku, qty)           // CHM.compute create-or-update
  + reserveStock / releaseStock / finalizePurchase
  + getAvailableStock(sku)

DiscountStrategy → NoDiscountStrategy | PercentageDiscountStrategy
  + applyDiscount(subtotal)

CartItem
  + product, quantity

Cart
  + items, status, expiresAt, cartLock
  + isExpired() / calculateSubtotal()

ShoppingCartService
  + registerProduct(product, initialStock)
  + createCart(userId, ttlMillis)
  + addItemToCart(cartId, sku, qty)   // reserve stock under cart + SKU locks
  + checkout(cartId, DiscountStrategy)
  + completePayment(cartId)           // finalizePurchase → PAID
  - sweepExpiredCarts()               // scheduleAtFixedRate
  + shutdown()
```

### Why `enum CartStatus` — not State Pattern?

Finite labels + guards (`==`), not behavior classes. Real logic lives in **service + inventory + sweeper** (same rule as Ride / Reservation).

| Approach | When |
|----------|------|
| **Enum** | States are labels ← **here** |
| **State Pattern** | Each state owns different behavior (Vending/ATM) |

### `compute()` vs item `ReentrantLock`

| Mechanism | Protects |
|-----------|----------|
| `CHM.compute(sku, …)` | **Map entry** (create-or-update that key; different SKUs parallel) |
| `InventoryItem` lock | **Fields** (`available` / `reserved`) used by `reserve`/`release` outside `compute` |

### TTL sweeper

`ScheduledExecutorService.scheduleAtFixedRate` — `initialDelay` + `period` (same ms twice = first after N, then every N). Same family as Reservation seat-lock sweeper.

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `addToCart` / `checkout` / `pay` / `cancel`; `ShoppingCartService` hides inventory locks, cart status transitions, discount, and the TTL sweeper |
| **Strategy** | `DiscountStrategy` — swap % / flat / coupon totals without changing checkout / pay flow |
| **Enum state machine** | `CartStatus` — labels + guards, not State Pattern classes |
| **Per-SKU locking + TTL sweeper** | Same family as Reservation holds: per-item `ReentrantLock` + scheduled expire/release |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Inventory vs cart vs discount vs sweeper |
| **O** | New discount strategy without editing cart |
| **L/I** | Any `DiscountStrategy` (`applyDiscount`) plugs in |
| **D** | Checkout depends on discount abstraction |

---

# 11. Vending Machine

```
                       VendingMachine (Facade / Context)
                                    │ holds
                                    ▼
                       VendingMachineState
         ┌──────────────┬───────────┴────────────┐
         ▼              ▼                        ▼
     IdleState    HasMoneyState           DispensingState

  DisplayScreen · ItemMotor · CoinReturnTray · ChangeDispenser ($5→$1→$0.25)

                       Transaction (Command)
                    PurchaseTransaction | RefundTransaction
```

**Lifecycle:** `Idle → HasMoney → Dispensing → Idle` · refund from HasMoney → Idle

### Why State Pattern here (not enum)?

Each state **owns different behavior** (`insertCoin` / `selectItem` / `refund` mean different things). Contrast with Cart/Ride where status is mostly a label and logic lives in the service.

### Enums / classes

```
Coin: QUARTER | ONE | FIVE

Item / ItemSlot
  + dispense()   // synchronized qty--

ItemMotor / DisplayScreen / CoinReturnTray   // hardware stubs only

ChangeDispenser (CoR)
  FiveDollarDispenser → OneDollarDispenser → QuarterDispenser
  + dispenseChange(amount)   // all-or-nothing exact change

Transaction
  PurchaseTransaction(slot) / RefundTransaction
  + execute(machine)

VendingMachineState
  IdleState | HasMoneyState | DispensingState
  + insertCoin / selectItem / dispenseItem / refund

VendingMachine
  + insertCoin / selectItem / dispenseItem / requestRefund
  + setState / addCredit / resetCredit
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `insertCoin` / `selectItem` / `requestRefund`; machine hides states, inventory, peripherals, change chain |
| **State** | Idle / HasMoney / Dispensing own lifecycle behavior — avoids `if-else` on status |
| **Command** | `PurchaseTransaction` / `RefundTransaction` — one business action each; add card pay without rewriting states |
| **Chain of Responsibility** | `$5 → $1 → $0.25` change; each handler takes what it can, passes remainder |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Machine = orchestration · slot = stock · peripherals = hardware · each txn = one action |
| **O** | Add `MaintenanceState` / new txn / new denomination without rewriting core |
| **L/I** | Any `Transaction` / thin `VendingMachineState` lifecycle API |
| **D** | Flow depends on hardware wrappers + dispenser abstractions |

---

# 12. ATM

```
                          ATM (Facade / Context)
                                     │ holds
                                     ▼
                                 ATMState
         ┌───────────────────┬───────┴───────────┐
         ▼                   ▼                   ▼
     IdleState          HasCardState     AuthenticatedState

  CardReader · ReceiptPrinter · CashDispenser ($50→$20→$10) · BankService

                                        Transaction (Command)
                               WithdrawTransaction | DepositTransaction
```

**Lifecycle:** `Idle → HasCard → Authenticated → Idle` (eject / after txn)

### Why State Pattern here (not enum)?

Same as Vending: each state owns different behavior for `insertCard` / PIN / txn / eject. Money ops stay in **Command**, not bloated into the state interface.

### Enums / classes

```
Card
  + validatePin(pin)

Account
  + getBalance / deduct / add   // synchronized

CardReader / ReceiptPrinter     // hardware stubs

CashDispenser (CoR)
  FiftyDispenser → TwentyDispenser → TenDispenser
  + dispense(amount)            // all-or-nothing exact notes

BankService → MockBankService
  + authenticate / getAccount / hasSufficientBalance
  + debitAccount / creditAccount

Transaction
  WithdrawTransaction / DepositTransaction
  + execute(atm)

ATMState
  IdleState | HasCardState | AuthenticatedState
  + insertCard / authenticatePin / executeTransaction / ejectCard

ATM
  + insertCard / authenticatePin / executeTransaction / ejectCard
  + setState
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `insertCard` / `authenticatePin` / `executeTransaction` / `ejectCard`; ATM hides states, bank, peripherals, cash chain |
| **State** | Idle / HasCard / Authenticated own lifecycle behavior |
| **Command** | Withdraw / Deposit — one money op each; add BalanceInquiry without rewriting states |
| **Chain of Responsibility** | `$50 → $20 → $10`; each handler takes what it can, passes remainder |

### SOLID — how applied

| | How |
|--|-----|
| **S** | ATM = orchestration · peripherals = hardware · BankService = ledger · each txn = one op |
| **O** | Add `MaintenanceState` / new txn / new denomination without rewriting core |
| **L/I** | Any `Transaction` / thin `ATMState` lifecycle API |
| **D** | ATM → `BankService` abstraction; withdraw → dispenser chain abstraction |

---

# 13. In-Memory Cache (LRU / LFU)

```
Cache<K,V> (Facade)
     │
     ▼
EvictionStrategy → LRUEvictionStrategy | LFUEvictionStrategy
```

**Capacity is fixed** (constructor). Never “increases.” On new `put` when full → evict one, then insert. `if (capacity <= 0) return` is only a guard for bad args.

### DLL mental model (LRU) — must remember

Dummy **head** + **tail** (sentinels, no real data). Real nodes live **between** them:

```text
head  ↔  [MRU]  ↔  ...  ↔  [LRU]  ↔  tail
         ↑ after head              ↑ before tail
```

| Position | Meaning |
|----------|---------|
| **After head** | Most Recently Used — `addFirst` inserts here |
| **Before tail** | Least Recently Used — `removeLast` / evict here |

`addFirst(NEW)` = insert right after head:

```text
BEFORE: head ↔ A ↔ ... ↔ tail
AFTER:  head ↔ NEW ↔ A ↔ ... ↔ tail
```

```java
node.next = head.next;
node.prev = head;
head.next.prev = node;
head.next = node;
```

Not `node.next = head` — that hangs outside the sentinel range.

### Enums / classes

```
Node<K,V>            // key, value, frequency, prev/next
DoublyLinkedList     // dummy head/tail; addFirst / remove / removeLast

EvictionStrategy
  + keyAccessed / keyAdded / evictKey

LRUEvictionStrategy  // one DLL — touch → head; evict tail
LFUEvictionStrategy  // freq→DLL + minFrequency; same-freq tie = LRU in bucket

Cache
  + get / put / size
  // write lock on get+put (get mutates eviction state)
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `get` / `put` / `size` — hides map, locks, eviction |
| **Strategy** | LRU vs LFU |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Cache = store/lock · Strategy = eviction · DLL = list |
| **O/L/I** | New eviction via tiny interface |
| **D** | Cache → `EvictionStrategy` |

---

# 14. In-Memory File System

```
InMemoryFileSystem (Facade)
         │
         ▼
  FileSystemNode → FileNode | DirectoryNode (Map children)
```

### One idea for the whole problem

Almost every API is the **same walk**:

```text
parse "/a/b/c" → ["a","b","c"]
start at root
for each token: go to child (create dir if mkdir / addContent needs it)
at the end: ls / read / append / delete
```

`mkdir`, `ls`, `addContentToFile`, `readContentFromFile`, `delete` — all just **root → … → leaf** along the path. Composite (File vs Dir) only changes what you do at the last node.

### Lock rule (must remember)

```text
DirectoryNode parent = curr;
parent.lock();
// create or descend; update curr
parent.unlock();   // unlock PARENT — never unlock after reassigning curr
```

### Enums / classes

```
FileSystemNode     + getName / getLock / isDirectory()
FileNode           + appendContent / getContent
DirectoryNode      + getChild / addChild / removeChild
InMemoryFileSystem + mkdir / ls / addContent… / read… / delete
                   - parsePath → tokens under /
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Path APIs hide tree walk + per-node locks |
| **Composite** | File (leaf) + Directory (composite) as `FileSystemNode` |

### SOLID — how applied

| | How |
|--|-----|
| **S** | File = content · Dir = children · Facade = path ops |
| **O** | Add SymLink node without rewriting core walk |
| **D** | Facade depends on `FileSystemNode` |

---

# 15. RBAC & Resource ACL

```
AccessControlService (Facade)
       ┌──────────┴──────────┐
       ▼                     ▼
RoleHierarchyManager    Resource ACL
(DAG flatten + cache)  (user/role × permission)
```

**Priority:** User DENY > User ALLOW > Role DENY/ALLOW > inherited role perms

### Hierarchy = DAG edges; flatten = walk the DAG

`addRoleInheritance(parent, child)` → link edge + **invalidate cache** (don’t call `addChildRole` alone).

Roles form a **DAG** (directed acyclic graph): parent → children. Not only a strict tree — a role could have multiple parents in richer designs; we still DFS with a `visited` set so cycles don’t loop forever.

```text
getEffectivePermissions(Admin)
  cache hit? → return
  else flatten DFS:
    {DELETE} + Editor {WRITE} + Viewer {READ}
    → cache {DELETE, WRITE, READ}
```

Role named **Admin** ≠ `Permission.ADMIN` wildcard. Inheritance alone already gives Admin all three; `contains(permission)` is enough unless you want a superuser flag.

### Enums / classes

```
Permission / AccessType (ALLOW|DENY)
User / Role / Resource   // ACL: principal → permission → ALLOW/DENY

RoleHierarchyManager
  + getEffectivePermissions(role)  // cache + DFS flatten
  + invalidateCache()

AccessControlService
  + registerUser / registerRole / addRoleInheritance
  + hasPermission(user, permission, resource)
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `hasPermission` + register/inherit; hides flatten + ACL |
| **DAG walk** | Role children DFS + `visited` + flattened cache (mechanism) |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Hierarchy flatten ≠ ACL maps ≠ evaluate |
| **O** | New role edge / permission without rewriting check core |
| **D** | Service uses hierarchy manager for effective perms |

---

# 16. Meeting Scheduler

```
MeetingSchedulerService (Facade)
       ┌──────────┴──────────┐
       ▼                     ▼
 MeetingRoom / User    RoomSelectionStrategy
 (TreeSet intervals)   FirstFit | BestFit
```

**Overlap:** `A.start < B.end && B.start < A.end` on half-open `[start, end)`.

### Booking concurrency (must remember)

```text
soft-check + strategy pick room
lock(room) → lock(users sorted by userId)
re-check room + users under locks
commit → unlock reverse
```

**Why sort userIds?** Same as sorted seats — Alice+Bob vs Bob+Alice otherwise deadlock.

**Why double-check?** Soft-check is stale; another thread may have booked before you held all locks.

Early `break` when `existing.start >= query.end` = optimization only (TreeSet by start); `overlapsWith` alone is correct. Worst case still O(N); Interval Tree = follow-up.

### Enums / classes

```
Interval
  + overlapsWith / compareTo (by start then end)

User / MeetingRoom
  + isAvailable (locks)
  + hasConflictLocked / add|book IntervalLocked  // caller holds lock

RoomSelectionStrategy → FirstFitRoomStrategy | BestFitRoomStrategy

MeetingSchedulerService
  + addRoom / registerUser / bookMeeting(...)
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `bookMeeting` hides calendars + ordered locks |
| **Strategy** | FirstFit / BestFit room pick |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Interval/User/Room = data · Strategy = pick · Service = locks |
| **O** | New room strategy without editing book flow |
| **D** | Service → `RoomSelectionStrategy` |

---

# 17. Splitwise

```
SplitwiseService (Facade)
       ┌──────────┴──────────┐
       ▼                     ▼
 SplitStrategy         BalanceSheet + DebtSimplifier
Equal|Exact|Percent    (net → greedy match)
```

**Expense path:** Strategy fills `Split` amounts (temp worksheet for **this** bill) → under group lock `addDebt(debtor → payer)`. Exact/Percent: `values[i]` matches `splits[i]`. Money = **cents**.

### Greedy simplify (must remember)

```text
1) Net each user from edges: debtor -= amt, creditor += amt  (Σ net = 0)
2) Heaps: largest debtor vs largest creditor
3) settle = min(|debt|, credit); push leftovers back
→ at most N-1 transactions (each step zeros ≥ 1 user)
```

Simplify = settlement **view**; raw `BalanceSheet` can stay.

### Enums / classes

```
Money / User / Split (amountCents)
SplitStrategy → Equal | Exact | Percent
BalanceSheet  // debtor → creditor → cents
DebtSimplifier.simplify → List<Transaction>
Group (members + sheet + lock)
SplitwiseService
  + addExpense / getSimplifiedGroupDebts
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `addExpense` / `getSimplifiedGroupDebts` — hides lock, graph, heaps |
| **Strategy** | Equal / Exact / Percent — how to fill per-person cents; swap without editing facade |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Strategy = split math · Sheet = edges · Simplifier = settle · Service = API |
| **O** | New split type without editing `addExpense` |
| **D** | Service → `SplitStrategy` |

---

# 18. Payment Gateway

```
PaymentGatewayFacade
       ┌──────────┴──────────┐
       ▼                     ▼
IdempotencyEngine      BankAdapter[] (ordered)
(per-key lock + cache) Stripe / Razorpay …
```

**Pay path:** `processPayment` → `executeIdempotent(req, () -> doProcess(req))` — Supplier is **lazy**; bank work only on cache miss. Txn: `INITIATED → PENDING → SUCCESS|FAILED`. Fallback = `supportsMethod` filter + **registration order** (not smart routing).

### Idempotency (must remember)

```text
Lookup by key only → then verify fingerprint (account|amount|method)
  same key + same fp     → return cached SUCCESS/FAILED (no bank)
  same key + different fp → reject IDEMPOTENCY_KEY_PAYLOAD_MISMATCH
  intentional method change → NEW key (new payment attempt)
```

Do **not** combine key+fp into one cache key — corrupted amount would look like a new attempt and could charge wrong. Order ≠ attempt: one order can have many keys. Cache **FAILED** too.

### Enums / classes

```
PaymentStatus / PaymentMethod
PaymentRequest (+ payloadFingerprint) / PaymentResponse
PaymentTransaction   // thin status lifecycle
BankAdapter → Stripe | Razorpay
IdempotencyRecord / IdempotencyEngine
PaymentGatewayFacade
  + registerAdapter / processPayment
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `processPayment` hides idempotency, adapter loop, txn status |
| **Adapter** | Stripe / Razorpay behind `BankAdapter` |
| **Ordered fallback** | Try candidates in registration order until one succeeds |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Facade = orchestrate · Idempotency = dedupe · Adapter = bank I/O · Txn = status |
| **O** | New `BankAdapter` without editing `doProcess` core |
| **D** | Facade → `BankAdapter`, not Stripe SDK |

---

# 19. Ledger / Wallet (Double-Entry)

```
LedgerService (Facade)
       ┌──────────┴──────────┐
       ▼                     ▼
   Account (+lock)      Journal (COW, append-only)
   ASSET | LIABILITY    immutable Transaction/Entry
```

**Helpers:** `deposit` / `transfer` / `reverse` build legs → `postTransaction` (Σ DR = Σ CR, ordered locks, overdraft). Never UPDATE/DELETE old txns — refund = compensating mirror.

### Debit / Credit (must remember)

```text
ASSET:      Debit ↑  Credit ↓     (bank cash / settlement)
LIABILITY:  Debit ↓  Credit ↑     (user wallets — owe customer)

deposit:  DR Bank(ASSET) + CR Wallet(LIABILITY)
transfer: DR FromWallet  + CR ToWallet   (both LIABILITY)
```

Helpers validate types (`deposit` ASSET→LIABILITY; `transfer` LIABILITY↔LIABILITY). `calculateDeltas` / balance = same two rules.

**Balance = journal replay only** — no cached balance on `Account`; `getAccountBalance` scans the append-only journal.

### Enums / classes

```
AccountType (ASSET|LIABILITY) / EntryType (DEBIT|CREDIT)
Account  // allowNegative; wallet=false, asset=true
Entry / Transaction  // unmodifiable entry copy
LedgerService
  + registerAccount / deposit / transfer / reverse
  + postTransaction / getAccountBalance
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `deposit` / `transfer` / `post` / `reverse` — hides locks, deltas, journal |
| **Append-only log** | Immutable txn+entries; reverse = new compensating txn |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Account = identity/policy · Entry/Txn = facts · Ledger = post/lock/balance |
| **O** | New helper flow without changing journal core |
| **D** | Callers use Facade APIs, not raw list mutation |

---

# 20. Notification System

```
NotificationService (Facade)
       ┌──────────┼──────────┐
       ▼          ▼          ▼
PriorityQueue  Template   Prefs + RateLimiter
       │
       ▼
NotificationChannel (Strategy) → MessageProvider chain
```

**Path:** prefs → rate limit → enqueue → workers drain → render template → channel → provider. **Not Observer** — registry pick-one-channel; Observer = fan-out.

### Must remember

```text
PriorityBlockingQueue: CRITICAL < HIGH < LOW (by level); same priority → FIFO via seq
Workers: poll(100ms) = wait up to 100ms (not "every 100ms"); 2× submit = 2 consumers
Email fallback: SendGrid → SES (List<MessageProvider>)
Rate limiter: synchronized allow = correct but global; scale → per-key / Redis
```

### Enums / classes

```
ChannelType / Priority
NotificationRequest implements Comparable
TemplateEngine / UserPreferenceService / NotificationRateLimiter
MessageProvider → SendGrid | SES | Twilio
NotificationChannel → EmailChannel | SmsChannel
NotificationService
  + registerChannel / start / sendNotification / shutdown
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `sendNotification` hides queue, workers, template, prefs, limit |
| **Strategy** | Channel by type; add Push/WhatsApp without editing drain loop |
| **Provider fallback** | Try providers in order until one succeeds |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Template / prefs / limiter / channel / facade split |
| **O** | New channel or email provider without editing worker loop |
| **D** | Facade → `NotificationChannel` / providers |

---

# 21. Social Chat / Messenger

```
ChatService (Facade)
       ┌──────────┼──────────┐
       ▼          ▼          ▼
UserSession   OfflineBuffer  ReceiptRegistry
(Observer)    per-user queue (msgId, recipientId) → status
```

**Real Observer here** (unlike Notification Strategy).  
`onMessageReceived` → **receiver** gets content.  
`onReceiptReceived` → **sender** gets DELIVERED/READ (if sender online).

### Presence + delivery (must remember)

```text
registerUserSession   = ONLINE  → flush offlineBuffers → deliverToSession
unregisterUserSession = OFFLINE → later sends buffer (not ReceiptRegistry yet)

sendMessage → messageStore always
  recipient online  → deliverToSession → DELIVERED + onMessageReceived + onReceiptReceived
  recipient offline → offlineBuffers only

markAsRead → ReceiptRegistry = READ → sender.onReceiptReceived
```

Receipts are **per recipient** — never one status on shared `Message` (group: Alice READ, Bob still DELIVERED).

### Enums / classes

```
MessageStatus (SENT|DELIVERED|READ)
Message / PendingDelivery / GroupChat / User
MessageObserver → UserSession
ReceiptRegistry
ChatService
  + registerUserSession / unregisterUserSession
  + sendMessage / markAsRead / isOnline / getLastSeen
```

### Design patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `sendMessage` / register / `markAsRead` — hides route, buffer, receipts |
| **Observer** | Online session notified of messages + receipts |
| **Per-recipient state** | Delivery/read keyed by `(messageId, recipientUserId)` |

### SOLID — how applied

| | How |
|--|-----|
| **S** | Message = payload · Session = transport · Receipts = status · Service = route |
| **O** | New transport via `MessageObserver` |
| **D** | Facade notifies observer, not concrete socket code |

---

# Instant pattern recall

| Problem | Patterns | SOLID one-liner |
|---------|----------|-----------------|
| Redis | Facade + dual eviction | Interface for store; SRP on node/entry/store |
| Txn KV | Facade + decorator + ThreadLocal + tombstone | Store ≠ txn; ISP keeps begin/commit off store |
| Rate limiter | Strategy + Facade + computeIfAbsent | Depend on `RateLimitStrategy` |
| Scheduler | Facade + Command + Producer-Consumer + Condition | Depend on `Task`/`Runnable` |
| Pub-Sub (Kafka L4) | Strategy + Facade + Coordinator + pull (not Observer) | Depend on `PartitionStrategy` |
| Reservation | Facade + enum SM + per-seat lock + TTL sweeper | SeatLock ≠ ReentrantLock; sort IDs |
| Ride Sharing | Facade + Strategy (fare/match) + grid + tryLock | Enum status ≠ State Strategy |
| Elevator | Facade + dispatcher Strategy + LOOK heaps | Hall ≠ car button; LOOK ≠ dispatcher |
| Issue Tracker | Facade + graph workflow + Guard Strategy + Observer | Notify outside lock; OCP via registry |
| Shopping Cart | Facade + Discount Strategy + enum SM + per-SKU lock + sweeper | Enum ≠ State Pattern; compute ≠ item lock |
| Vending Machine | Facade + State + Command + CoR ($5→$1→$0.25) | States thin; purchase/refund = Command; hardware peripherals SRP |
| ATM | Facade + State + Command + CoR ($50→$20→$10) + DIP BankService | States thin; money ops = Command; bank behind interface |
| In-Memory Cache | Facade + Eviction Strategy (LRU/LFU) | head=MRU, tail=LRU; get mutates → write lock |
| In-Memory File System | Facade + Composite (File/Dir) | All ops = walk root→path; unlock parent not curr |
| RBAC | Facade + role DAG flatten + permission-scoped ACL | Inherit via child edges; invalidate on link; DENY>ALLOW>roles |
| Meeting Scheduler | Facade + room Strategy + ordered locks | Sort userIds; re-check under locks; overlap early-break optional |
| Splitwise | Facade + Split Strategy + greedy net/heaps | Cents; Split=temp; simplify ≤ N-1 settle view |
| Payment Gateway | Facade + Adapter + ordered fallback + idempotency | Key≠fp; cache SUCCESS+FAILED; lazy Supplier |
| Ledger / Wallet | Facade + append-only journal + ordered locks | Balance=journal replay; ASSET/LIABILITY DR/CR; reverse≠delete |
| Notification System | Facade + Channel Strategy + provider fallback | Not Observer; priority+seq; poll timeout ≠ tick |
| Social Chat | Facade + Observer session + offline buffer | Per-recipient receipts; online push / offline flush |
