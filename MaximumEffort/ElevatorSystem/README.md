# LLD — Elevator Control System

Google L4–sized: N cars, hall + car requests, LOOK dual heaps, pluggable dispatcher.  
Simulation via `stepAll()` ticks (say “per-car worker thread” if asked).

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **N cars, M floors** (floors are ints; building size implicit).
- **Internal:** `pressFloorButton(elevatorId, floor)`.
- **External:** `requestElevator(floor, direction)` (hall panel).
- **Motion:** IDLE / UP / DOWN with stops (doors open).
- **LOOK serving:** Finish requests in current direction, then reverse.
- **Dispatch Strategy:** Assign hall calls to the best car.

### Non-Functional Requirements

- Thread-safe request enqueue per car (`ReentrantLock`).
- Prefer cars already on-path / same direction (less thrashing).
- Extensible dispatcher (`ShortestSeek…` as a new Strategy if asked).

---

## 2. Architecture (00:05 – 00:12)

```
        ElevatorSystemFacade
                 │
                 ▼
     ElevatorDispatcherStrategy  (assign hall calls)
                 │
                 ▼
           ElevatorCar[]
        LOOK: up min-heap + down max-heap
```

### LOOK vs Dispatcher (say this clearly)

| Piece | Role |
|--------|------|
| **LOOK on each car** | `upRequests` / `downRequests` — how **one** car orders stops |
| **Dispatcher Strategy** | Which **car** gets a hall call (heuristic score) |

Not the same thing — both appear in good elevator LLDs.

### Hall-call correction

Hall calls use `addHallRequest(floor, direction)`:

1. Enqueue pickup floor into LOOK heaps (by vs current floor).
2. Remember **hall direction** at that floor.
3. On doors open, apply preferred direction when useful (continue LOOK that way).

Internal buttons only enqueue a destination (`addDestinationFloor`).

**One-liner:**  
**Hall** = stop at floor + remember UP/DOWN intent → applied on door open.  
**Car button** = only enqueue destination.  
**`stepAll`** = move one floor at a time until peeks match.

### End-to-end flow

```text
new ElevatorSystemFacade(2, LookDispatcherStrategy)
        │
        ▼
  Car1, Car2 @ floor 1  (IDLE / STOPPED)

═══════════════════════════════════════════════════
  HALL CALL  (outside ▲/▼)
═══════════════════════════════════════════════════

requestElevator(5, UP)
        │
        ▼
LookDispatcherStrategy.selectElevator(...)
        │
        ▼
bestCar.addHallRequest(5, UP)
        │
        ├─► hallDirectionAtFloor[5] = UP
        └─► enqueueFloor(5) → upRequests/upSet
             (if IDLE → direction = UP, status = MOVING)

═══════════════════════════════════════════════════
  SIMULATION TICKS
═══════════════════════════════════════════════════

stepAll()  →  each car.step() once per tick
        │
        ▼
direction UP & upRequests not empty
        │
        ▼
currentFloor++
        │
        ▼
peek == currentFloor?  (e.g. 5)
        │
       Yes
        │
        ▼
poll floor from upRequests
openDoors()                    ← STOP / doors
applyHallDirectionIfPresent(5) ← use hall map; may set UP/DOWN/IDLE
        │
        ▼
if upRequests empty → reverse to DOWN if down has work, else IDLE

═══════════════════════════════════════════════════
  CAR BUTTON  (inside — destination)
═══════════════════════════════════════════════════

pressFloorButton(carId, 8)
        │
        ▼
addDestinationFloor(8) → enqueueFloor(8)
        │
        ▼
(no hall map — destination only)

═══════════════════════════════════════════════════
  MORE stepAll() UNTIL DESTINATION
═══════════════════════════════════════════════════

currentFloor++ … until peek == 8
        │
        ▼
poll · openDoors()
        │
        ▼
queues empty → IDLE / STOPPED
```

---

## 3. Code (one file)

```
ElevatorSystem/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/ElevatorSystem/code
javac Main.java && java Main
```

---

## 4. Complexity

| Operation | Time |
|-----------|------|
| Enqueue floor (heap) | O(log K) |
| `step()` peek/compare | O(1) |
| Dispatch select car | O(E) elevators |

Dedupe floors with `HashSet` (PQ `contains` would be O(K)).

---

## 5. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `ElevatorSystemFacade` — hall, car button, step |
| **Strategy** | `ElevatorDispatcherStrategy` / `LookDispatcherStrategy` |
| **LOOK algorithm** | Dual `PriorityQueue`s per car |
| **Enum state** | `Direction`, `ElevatorStatus` |

---

## 6. SOLID

| | How |
|--|-----|
| **S** | Car = motion + queues · Dispatcher = assignment · Facade = API |
| **O** | New dispatcher strategy without changing cars |
| **L** | Any `ElevatorDispatcherStrategy` plugs in |
| **I** | One method: `selectElevator(...)` |
| **D** | Facade depends on dispatcher abstraction |

---

## 7. Interview Q&A

**“LOOK vs FCFS?”**  
FCFS thrashing (1→10→2). LOOK serves one direction fully, then reverses.

**“Capacity / FULL?”**  
Track weight; mark car unschedulable until under limit (verbal).

**“100-story building?”**  
Zone elevators (low/mid/high) + express shuttles; dispatchers per zone.
