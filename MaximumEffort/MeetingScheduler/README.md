# LLD — Meeting Scheduler

Google L4–sized: book rooms + participants for `[start, end)`, Strategy room pick, no double-booking.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Rooms (capacity) + Users
- `bookMeeting(title, interval, capacity, organizer, participants)`
- Auto room selection (FirstFit / BestFit)
- Fail if room **or** any participant overlaps

### Non-functional

- Concurrent safe booking (no lost updates on same room/user)
- Pluggable `RoomSelectionStrategy`

---

## 2. Architecture (00:05 – 00:12)

```
              MeetingSchedulerService (Facade)
                 ┌──────────┴──────────┐
                 ▼                     ▼
           MeetingRoom / User    RoomSelectionStrategy
           (TreeSet intervals)   FirstFit | BestFit
```

**Overlap:** `[A.start, A.end)` overlaps `[B.start, B.end)` iff `A.start < B.end && B.start < A.end`.

**Booking flow (concurrency)**

```text
soft-check users + strategy pick room   ← filter only (no full lock set)
lock room → lock users (sorted by userId)
re-check room + users under locks       ← authoritative
commit intervals → unlock reverse
```

### Why sort userIds before locking?

Same idea as **sorted seat IDs** in Reservation. Two meetings that share people can otherwise take locks in **opposite** order and deadlock:

```text
T1 (Alice+Bob): lock(Alice) → wait(Bob)
T2 (Bob+Alice): lock(Bob)   → wait(Alice)   → DEADLOCK
```

With a global order (`U1` then `U2`), both always lock Alice then Bob — no cycle.

### Why check conflict again after locking?

Soft-check / strategy ran **before** we held room+users. Another thread may have booked in between. Re-check under locks is the real “still free?” (check → lock → check again), same family as seat hold under lock.

Early TreeSet `break` when `existing.start >= query.end` is only an **optimization** (sorted by start); `overlapsWith` alone is enough for correctness.

---

## 3. Code (one file)

```
MeetingScheduler/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/MeetingScheduler/code
javac Main.java && java Main
```

---

## 4. Fixes in this ship

| Issue | Fix |
|-------|-----|
| Race on room/user | Hold room + all users, re-validate, then write |
| Nested lock while holding room | `*Locked` helpers assume lock already held |
| “O(log N)” linear scan lie | Early-exit TreeSet scan; worst case still **O(N)**; Interval Tree = follow-up |
| FirstFit missing | `FirstFitRoomStrategy` added |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | Interval/User/Room = data · Strategy = pick · Service = orchestrate locks |
| **O** | New strategy without editing `bookMeeting` |
| **L/I** | Any `RoomSelectionStrategy.selectRoom(...)` |
| **D** | Service depends on strategy abstraction |

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `bookMeeting` / register APIs; hides locks + calendars |
| **Strategy** | `FirstFitRoomStrategy` / `BestFitRoomStrategy` |

---

## 7. Interview Q&A

**“Why sort userIds before lock?”**  
One global lock order. Otherwise Alice+Bob vs Bob+Alice can deadlock (same as sorting seats before multi-seat hold).

**“Why double-check after lock?”**  
Soft-check is stale by the time all locks are held. Re-validate room + users under the lock set, then commit.

**“Thousands of meetings per room?”**  
Augmented Interval Tree (subtree max end) → true **O(log N)** overlap. Our TreeSet + early break is L4-practical.

**“Timezones?”**  
Store UTC epochs in `Interval`; convert only at UI.
