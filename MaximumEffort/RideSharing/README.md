# LLD — Ride Sharing System (Uber / Lyft)

Google L4–sized: spatial grid match, per-driver `tryLock`, pricing/matching Strategy, trip enum state machine.  
Not full Geohash/H3 — grid cells (“geohash-lite”); say Redis GEO / H3 only if asked to scale.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **Drivers:** Online/offline, location updates, accept assignment via dispatch.
- **Riders:** Request trip (pickup/dropoff), estimated fare, trip lifecycle.
- **Spatial matching:** Nearby **AVAILABLE** drivers in pickup grid cell within radius (e.g. 5 km).
- **Pricing Strategy:** Pluggable fare (`StandardPricingStrategy` × surge multiplier).
- **Matching Strategy:** Pluggable pick among candidates (`NearestDriverStrategy`) — **actually used** before lock attempts.
- **Trip state machine:**
  - `CREATED → DRIVER_ASSIGNED → IN_PROGRESS → COMPLETED`
  - `CREATED / DRIVER_ASSIGNED / IN_PROGRESS → CANCELLED`

### Non-Functional Requirements

- **No double-assign:** Per-driver `ReentrantLock.tryLock()` + re-check `AVAILABLE`.
- **Fast spatial filter:** Grid map → O(K) candidates in cell (not O(N) all drivers).
- **Extensibility:** New pricing/matching strategies without changing facade dispatch loop.

---

## 2. Architecture (00:05 – 00:12)

```
                    RideSharingService (Facade)
           ┌─────────────────┼─────────────────┐
           ▼                 ▼                 ▼
   SpatialIndexManager  PricingStrategy  DriverMatchingStrategy
   (grid cell → drivers)  (Standard…)      (Nearest…)
           │
           ▼
        Trip (enum state machine) + Driver (per-driver lock)
```

### Why `enum` status — not State Strategy?

State Strategy shines when **each status owns different logic** (Vending/ATM: insert coin, select item — behavior changes a lot).

Here the real behavior lives in **`RideSharingService` + spatial index + `tryLock`**, not inside status objects. Status is mostly:

- Can this driver be matched? (`AVAILABLE`)
- Should they be in the grid? (`AVAILABLE` only)
- Are they busy? (`ON_TRIP`)

So: **enum state machine** (same as seats / cart / trip) — not Strategy-as-State.

---

## 3. Code (one file)

```
RideSharing/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/RideSharing/code
javac Main.java && java Main
```

---

## 4. Complexity & Concurrency

| Operation | Time | Notes |
|-----------|------|--------|
| `updateDriverLocation` | O(1) | Hash cell move |
| `findNearbyAvailableDrivers` | O(K) | K = drivers in **center cell only** — not all drivers |
| `requestRide` match | O(K²) worst | Nearest pick + retries; K small in a cell |

*Production: scan 8 neighbor cells; Redis `GEORADIUS` / Uber H3.*

### Concurrency — what we use and what it achieves

| Mechanism | What we handle |
|-----------|----------------|
| **Grid-scoped search** | Match only drivers in the pickup cell (+ radius filter). Avoids O(N) scan of every online driver. |
| **Per-driver `tryLock`** | Two riders can’t assign the same driver. Winner proceeds; loser tries the next candidate (non-blocking). |
| **Double-check `AVAILABLE`** | Under the lock, re-verify status before `ON_TRIP` — closes the race after spatial read. |
| **Index hygiene** | Leave grid on `OFFLINE` / `ON_TRIP`; re-enter on `AVAILABLE`. Busy drivers aren’t matchable candidates. |
| **Cancel / complete** | Restore `AVAILABLE` + re-index so the driver can be matched again safely. |

---

## 5. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call driver online/location, `requestRide`, `start` / `complete` / `cancel`; `RideSharingService` hides spatial index, matching, per-driver locks, and trip state transitions |
| **Strategy** | `PricingStrategy`, `DriverMatchingStrategy` (pricing/matching — **not** driver status) |
| **Enum state machine** | `TripStatus`, `DriverStatus` with guarded transitions |
| **Per-driver locking** | `tryLock` during dispatch to prevent double booking |
| **Spatial index** | Grid `ConcurrentHashMap` (mechanism; not GoF) |

---

## 6. SOLID

| | How |
|--|-----|
| **S** | Location/Driver/Rider/Trip = domain · SpatialIndex = geo buckets · Strategies = fare/match · Service = orchestration |
| **O** | Add `SurgePricingStrategy` / `HighestRatedDriverStrategy` without editing dispatch core |
| **L** | Any pricing/matching impl plugs into the service |
| **I** | Tiny strategy interfaces (`calculateFare`, `matchDriver`) |
| **D** | Service depends on strategy abstractions, not concrete nearest/standard classes only |

---

## 7. Interview Q&A

**“How do two riders not get the same driver?”**  
`tryLock` on driver → re-check `AVAILABLE` → `ON_TRIP` + remove from grid → unlock. Loser tries next candidate from matching strategy.

**“How do you scale spatial search?”**  
Redis GEO / S2 / H3; async location pings; match workers query radius in the geo store.

**“What on cancel?”**  
`cancelTrip` under trip rules; if a driver was assigned → `AVAILABLE` + re-insert into spatial index.

**“Why not State Pattern for DriverStatus?”**  
Behavior isn’t per-status classes — it’s dispatch + index + locks. Enum flags are enough for L4 (same as Reservation seat status).
