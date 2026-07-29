# LLD — Pub-Sub Messaging Queue (Google L4–sized, Kafka Inspired)

Slim interview version: topics, partitions, offsets, consumer groups, rebalance.  
No disk segments / `StorageEngine` / `PollRequest` / `TopicManager` (mention those only if asked to scale up).

---

## 1. Requirement & Scope Clarification (00:00 – 00:05)

### Functional Requirements

- **Topics & Partitions:** Publish to named topics; each topic has one or more partitions for parallel throughput.
- **Partition Strategies:** Pluggable `PartitionStrategy` (ship `HashPartitionStrategy`; Round Robin / Sticky as extensions).
- **Broker Facade:** `Broker` exposes `createTopic()`, `publish()`, `poll()` — routes to the right topic/partition.
- **Producer / Consumer API:** `Producer.send(...)` and `Consumer.poll(broker, group, batchSize)`.
- **Consumer Group + Offsets:** `ConsumerGroup` tracks per-partition offsets and owns the consumer list; after processing, `commitOffset(...)`.
- **Rebalance:** `GroupCoordinator.rebalance(...)` assigns partitions round-robin across consumers in the group.
- **Pull Polling:** Consumers **pull** `Message` batches via `poll` over their `assignedPartitions` (not push / Observer).

### Non-Functional Requirements

- **In-memory log:** Each `Partition` stores messages in a `List` with an `AtomicLong` next offset.
- **Thread safety:** `ReentrantReadWriteLock` per partition (shared reads, exclusive appends).
- **Broker registry:** Topics in a `ConcurrentHashMap`.
- **Immutability:** `Message` fields are final (`key`, `payload`, `offset`).

---

## 2. Core Architecture & Component Topology (00:05 – 00:12)

```
        Producer                         Consumer(s)
           │                                  │
           │ send                             │ poll(broker, group, batch)
           ▼                                  ▼
    ┌─────────────┐                    ┌──────────────────┐
    │   Broker    │◄───────────────────│  ConsumerGroup   │
    │  (Facade)   │                    │  (offsets map)   │
    └──────┬──────┘                    └────────┬─────────┘
           │                                    │
           ▼                                    │ assign via
         Topic                                  │
           │                             GroupCoordinator
           │                             .rebalance()
           ├── PartitionStrategy
           └── Partition(s)  →  List<Message> log
```

### Publish / Consume Flow (Google L4–sized)

**Producer**

```text
Producer
  ↓
Broker.publish()
  ↓
Topic
  ↓
Partition Strategy
  ↓
Partition (append message)
```

**Consumer**

```text
Consumer
  ↓
Consumer Group (owns offsets)
  ↓
Broker.poll()
  ↓
Partition
  ↓
Messages
  ↓
Consumer processes
  ↓
ConsumerGroup.commitOffset()
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

*Scale-up (only if asked): Partition → StorageEngine → Segments; durable offset store; smarter assignors.*

---

## 3. Code (one file)

```
PubSubMessagingQueue/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/PubSubMessagingQueue/code
javac Main.java && java Main
```

---

## 4. Interview Follow-ups (keep short)

**“Is this Observer?”**  
No — consumers **pull** with `poll`. Observer would **push**/notify subscribers.

**“Why no ACKS_ALL / replication?”**  
Single in-memory broker. Multi-broker would wait for quorum replication before ack.

**“Why is Partition.read O(N)?”**  
`ArrayList` scan from the start for offset ≥ cursor. Fine for LLD; production uses segment indexes / binary search.

**“Where does GroupCoordinator live?”**  
Outside the Broker facade — Broker routes data; Coordinator only assigns partitions.

**“How would you make this production-ready?”**  
Persist partition logs (segments), durable offset commits, richer rebalance (sticky/range), optional multi-broker replication.

---

## 5. Design Patterns

### Strategy Pattern (Behavioral)

`PartitionStrategy` encapsulates partition selection. Plug in Hash / Round Robin / Sticky without modifying `Topic`.

### Facade Pattern (Structural)

`Broker` exposes `createTopic()`, `publish()`, `poll()` while hiding topics, partitions, and routing.

### Producer–Consumer Pattern (Behavioral / Concurrency)

Producers append; consumers independently poll through the broker.

**Not Observer** — pull, not push.

### Coordinator Pattern (Behavioral)

`GroupCoordinator` owns rebalance / partition assignment, separate from consumers and the broker.

---

## 6. SOLID Principles

### S — Single Responsibility Principle (SRP)

| Class | Responsibility |
|-------|----------------|
| `Producer` | Only publishes messages |
| `Broker` | Only routes publish / poll requests |
| `Topic` | Only manages partitions |
| `Partition` | Only stores and retrieves messages |
| `ConsumerGroup` | Only manages consumers and offsets |
| `GroupCoordinator` | Only performs partition assignment (rebalance) |
| `HashPartitionStrategy` | Only decides which partition to use |

### O — Open/Closed Principle (OCP)

Add `RoundRobinPartitionStrategy` / `StickyPartitionStrategy` without modifying `Topic` / `Broker`.

### L — Liskov Substitution Principle (LSP)

Any `PartitionStrategy` implementation can replace another where the interface is expected.

### I — Interface Segregation Principle (ISP)

`PartitionStrategy` exposes only `getPartition(...)`.

### D — Dependency Inversion Principle (DIP)

`Topic` depends on `PartitionStrategy`, not concrete `HashPartitionStrategy`.
