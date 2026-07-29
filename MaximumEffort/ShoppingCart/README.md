# LLD — Shopping Cart & Inventory

Google L4–sized: per-SKU stock reserve, cart TTL, checkout → pay, discount Strategy.  
Single warehouse / flat SKU map (multi-warehouse only if asked).

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **Inventory:** `available` + `reserved` per SKU; `addStock` / `reserve` / `release` / `finalizePurchase`.
- **Cart:** Add items → **immediately reserves** stock with cart TTL.
- **Lifecycle:**
  - `ACTIVE → CHECKED_OUT → PAID`
  - `ACTIVE | CHECKED_OUT → CANCELLED_EXPIRED` (TTL) → stock released
- **No oversell:** Concurrent flash-sale adds protected by per-SKU locks.

### Non-Functional Requirements

- **Pessimistic in-memory:** fine-grained **per-SKU** `ReentrantLock` (+ cart lock for status/items).
- **Sweeper daemon:** frees expired carts without blocking shoppers.
- **Discount Strategy:** pluggable totals (`PercentageDiscountStrategy`; flat % sibling if asked).

*DB optimistic versioning / Redis Lua = interview scale-out talk only — not in this code.*

---

## 2. Architecture (00:05 – 00:12)

```
            ShoppingCartService (Facade)
          ┌─────────────┼─────────────┐
          ▼             ▼             ▼
   InventoryManager   Cart(+TTL)   DiscountStrategy
   (per-SKU locks)    sweeper
```

### Why `enum CartStatus` — not State Pattern?

```java
enum CartStatus {
    ACTIVE,
    CHECKED_OUT,
    PAID,
    CANCELLED_EXPIRED
}
```

These are **fixed, predefined labels**, not objects with behavior.

An **enum** is ideal because:

- The set of statuses is finite and fixed  
- Type safety (can’t accidentally use `"ACTIVEE"`)  
- Easy to compare with `==`  
- Lightweight and readable  

**State Pattern** (separate classes like `ActiveState`, `PaidState`) is used only when **each state has different behavior** and transitions are complex (Vending/ATM).

Here behavior lives in **`ShoppingCartService` + inventory + sweeper**, not inside status classes.

**Rule of thumb**

| Approach | When |
|----------|------|
| **Enum** | States are just labels/values ← **used here** |
| **State Pattern** | Each state owns its own logic/behavior |

---

## 3. Code (one file)

```
ShoppingCart/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/ShoppingCart/code
javac Main.java && java Main
```

---

## 4. Concurrency — `compute()` vs `ReentrantLock`

### What `inventory.compute(sku, …)` does

```java
inventory.compute(sku, (k, existing) -> {
    if (existing == null) {
        return new InventoryItem(sku, quantity);  // create
    }
    existing.getLock().lock();
    try {
        existing.addStock(quantity);              // update
        return existing;
    } finally {
        existing.getLock().unlock();
    }
});
```

**Rule:** `compute()` = atomic **get → create-or-update → put** for that map key.

- SKU missing → create `InventoryItem` and insert  
- SKU present → update stock, keep same object  

`ConcurrentHashMap.compute()` runs that function for a **given key** with only one writer at a time **for that key**.

**Important:** it does **not** serialize **different** SKUs — only the same `sku`. `IPHONE` and `MACBOOK` can `compute` in parallel.

### Why still use `ReentrantLock` on the item?

These protect **different** things:

| Mechanism | Protects |
|-----------|----------|
| `ConcurrentHashMap.compute()` | The **map entry** (which object owns the key) |
| `ReentrantLock` on `InventoryItem` | **Mutable fields** (`available` / `reserved` / `total`) |

No lock while `new InventoryItem(...)` — the object is **not shared yet**.

After it’s in the map, `reserve()` / `release()` / `deductOnPurchase()` often run **without** `compute()`, so the item lock prevents concurrent field updates.

**Rule:** `compute()` protects the **map**; `ReentrantLock` protects the **shared object’s state**.

---

## 5. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `addToCart` / `checkout` / `pay` / `cancel`; `ShoppingCartService` hides inventory locks, cart status transitions, discount, and the TTL sweeper |
| **Strategy** | `DiscountStrategy` — swap % / flat / coupon totals without changing checkout / pay flow |
| **Enum state machine** | `CartStatus` (`ACTIVE` → `CHECKED_OUT` → `PAID`, or → `CANCELLED_EXPIRED`) — labels + guards, not State Pattern classes |
| **Per-SKU locking + TTL sweeper** | Same family as Reservation holds: fine-grained `ReentrantLock` per item + `ScheduledExecutorService` frees expired carts / reserved stock |

---

## 6. SOLID

| | How |
|--|-----|
| **S** | Inventory vs cart vs discount vs sweeper |
| **O** | New discount strategy without editing cart |
| **L** | Any `DiscountStrategy` plugs into checkout |
| **I** | One method: `applyDiscount` |
| **D** | Checkout depends on discount abstraction |

---

## 7. Interview Q&A

**“100k users, 100 units?”**  
In-process per-SKU locks OK for LLD. Production: Redis `DECRBY`/Lua + async persist; DB is not the hot path.

**“Worker crash with reserved stock?”**  
Reservations carry absolute expiry; sweeper / cron releases `RESERVED` past TTL (same idea as cart sweeper).

**“Why lock inside `compute` if CHM is already concurrent?”**  
CHM synchronizes the **entry** for that key; the lock synchronizes **stock fields** used by `reserve`/`release` outside `compute`. Different SKUs still run in parallel.
