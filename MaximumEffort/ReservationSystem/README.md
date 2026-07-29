# LLD — Reservation System (BookMyShow / Hotel / Flight)

Google L4–sized: seat inventory, TTL hold, confirm, per-seat locks, expiration sweeper.  
One show’s flat inventory for the interview; multi-show = `Map<ShowId, seats>` if asked.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **Inventory:** Seats with categories (VIP / PREMIUM / REGULAR) and prices.
- **Availability:** List seats in `AVAILABLE`.
- **Temporary hold (TTL):** `AVAILABLE → HELD` with a time-bound lock while “payment” happens.
- **Confirm / release:** Payment OK → `CONFIRMED`. Fail / TTL expire → back to `AVAILABLE`.
- **No double-booking:** Concurrent holds on the same seat — only one wins.

### Non-Functional Requirements

- **Fine-grained concurrency:** Lock **per seat**, not the whole venue.
- **Background sweeper:** Release expired holds without blocking booking APIs.
- **Multi-seat safety:** All-or-nothing hold + **lock ordering** to avoid deadlocks.

---

## 2. Architecture (00:05 – 00:12)

```
                 ReservationService (Facade)
                            │
                            ▼
                 SeatLockManager (TTL + sweeper)
                 Map<SeatId, SeatLock>
                            │
                            ▼
                      Seat (per-seat ReentrantLock)
                            │
                            ▼
              SeatStatus: AVAILABLE → HELD → CONFIRMED
                          HELD → AVAILABLE (expire / release)
```

### State machine (enum + guarded transitions)

```text
AVAILABLE  --hold()-->  HELD  --confirm()-->  CONFIRMED
                ↑
                +-- expire() / release() --+
```

*(Enum state machine — not GoF State classes, unless interviewer asks.)*

---

## 3. Code (one file)

```
ReservationSystem/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/ReservationSystem/code
javac Main.java && java Main
```

---

## 4. Concurrency highlights

### Per-seat `ReentrantLock`
Booking `A1` does not block booking `B1`.

### Lock ordering (deadlock prevention)

`holdSeats` and `confirmBooking` **sort seat IDs** before locking so every thread acquires in the same order.

**Why?** Alice wants `A1,A2`, Bob wants `A2,A1`:

```text
Without sort:
  Alice locks A1 → waits for A2
  Bob   locks A2 → waits for A1
  → deadlock

With sort (always A1 then A2):
  Alice: lock A1 → lock A2
  Bob:   wait for A1 → then A2
  → no cycle
```

### TTL + sweeper
`SeatLock` uses monotonic `nanoTime`. `ScheduledExecutorService` sweeps expired holds → `AVAILABLE`.

### Confirm clears hold metadata
On confirm: status → `CONFIRMED` and `activeLocks.remove(seatId)` so the sweeper does not keep stale hold entries.

### Complete locking lifecycle

**Framing:** two different “locks” — Java `ReentrantLock` (ms, protect memory) vs `SeatLock` (TTL, business hold).  
Multi-seat: sort IDs first (see lock ordering above).

**Hold — Alice books A1**

```text
Alice books A1
       │
       ▼
 lock(A1)   ← ReentrantLock (brief)
       │
       ▼
 status == AVAILABLE?
    │           │
   No          Yes
    │           │
 return    HELD + create SeatLock(Alice, TTL)
                │
                ▼
         activeLocks[A1]
                │
                ▼
         unlock(A1)
```

**Confirm — pays before TTL**

```text
Validate SeatLock owner (Alice, not expired)
       │
       ▼
 lock(A1)
       │
       ▼
 HELD → CONFIRMED
       │
       ▼
 Remove activeLocks[A1]
       │
       ▼
 unlock(A1)
```

**Expire — never pays**

```text
Sweeper wakes (every ~1s)
       │
       ▼
 activeLocks[A1] expired? → YES
       │
       ▼
 lock(A1)
       │
       ▼
 HELD → AVAILABLE
       │
       ▼
 Remove activeLocks[A1]
       │
       ▼
 unlock(A1)
```

### Two kinds of “locks” (don’t confuse them)

| | `ReentrantLock` | `SeatLock` |
|--|-----------------|------------|
| **Purpose** | Protect shared memory from races | Reserve the seat for a user |
| **Duration** | Milliseconds (while updating) | Seconds–minutes (until TTL / confirm) |
| **Stored in** | `Seat` object | `activeLocks` map |
| **Remember** | Protects the **code** | Protects the **business resource** |

---

## 5. Distributed scale-out (say only — don’t code)

In-memory path = **pessimistic per-seat locks**.

At DB scale, add **optimistic concurrency**:

```sql
UPDATE seats
SET status = 'HELD', lock_user_id = ?, lock_expiry = NOW() + INTERVAL '5 minutes', version = version + 1
WHERE seat_id = ? AND status = 'AVAILABLE' AND version = ?;
```

0 rows updated → someone else won the seat.

---

## 6. Design Patterns

| Pattern / mechanism | How used |
|---------------------|----------|
| **Facade** | `ReservationService` — hold / confirm / availability |
| **Enum state machine** | `SeatStatus` transitions guarded under seat lock |
| **Per-resource locking** | `ReentrantLock` on each `Seat` |
| **Lock ordering** | Sorted seat IDs before multi-lock |
| **Background sweeper** | `ScheduledExecutorService` releases expired holds |

---

## 7. SOLID

| | How |
|--|-----|
| **S** | `Seat` = inventory unit · `SeatLock` = TTL hold · `SeatLockManager` = acquire/release/sweep · `ReservationService` = booking API · `Booking` = confirmed record |
| **O** | New category / multi-show map without rewriting lock protocol |
| **L** | N/A heavy — small domain types |
| **I** | Thin service API: availability, hold, confirm |
| **D** | Service depends on lock manager + seat map abstractions in spirit (concrete for LLD size) |

---

## 8. Interview Q&A

**“Why `ReentrantLock` per seat instead of one big `synchronized`?”**  
Max concurrency across different seats; cleaner multi-lock unlock/rollback.

**“How do you prevent deadlock when holding A1+A2?”**  
Always sort seat IDs; every thread locks in the same order.

**“What if Redis lock fails in production?”**  
Redis = fast path. DB = source of truth via OCC/`UNIQUE(show_id, seat_id)` on active holds.
