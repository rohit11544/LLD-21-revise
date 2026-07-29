# LLD — Vending Machine System

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- View slots (A1, B2), price, stock; select item  
- Coin/cash credit tracking  
- Hardware abstracted: `CoinAcceptor` mindset via `Coin` + tray/screen/motor  
- Change via **Chain of Responsibility** ($5 → $1 → $0.25)  
- States: `Idle → HasMoney → Dispensing → Idle`

### Non-functional

- Thread-safe state / slot qty / change inventory  
- Atomicity: if change or stock fails → **full refund**, no silent partial sale  

---

## 2. Architecture (00:05 – 00:12)

```
                       ┌─────────────────────────┐
                       │ VendingMachine Context  │
                       └────────────┬────────────┘
                                    │ holds
                                    ▼
                       ┌─────────────────────────┐
                       │   VendingMachineState   │
                       └────────────┬────────────┘
         ┌──────────────┬───────────┴────────────┬──────────────┐
         ▼              ▼                        ▼              ▼
     IdleState    HasMoneyState           DispensingState  MaintenanceState

  DisplayScreen · ItemMotor · CoinReturnTray · ChangeDispenser chain

                       Transaction (Command)
                    PurchaseTransaction | RefundTransaction
```

## 3. Code (one file)

```
VendingMachine/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/VendingMachine/code
javac Main.java && java Main
```

---

## 4. Concurrency Model & Database Schema (00:30 – 00:40)

### Concurrency & Locking Strategy

**Why we care:**  
Two users (or restock + purchase) must not corrupt **machine state**, **inserted credit**, **item quantity**, or **change coin counts**.

**1) Vending machine state thread safety**

- The machine holds `currentState` (Idle / HasMoney / Dispensing) and `insertedCredit`.
- We use `synchronized` on `setState(...)`, `addCredit(...)`, `resetCredit(...)`.
- Plain meaning: **only one thread updates state/credit at a time**, so you don’t lose money in credit or jump to a wrong state.

**2) Inventory locking on the slot**

- `ItemSlot.dispense()` is `synchronized`.
- Plain meaning: two people cannot both take the **last** Coke. First call succeeds and quantity becomes 0; second sees 0 and fails.

**3) Row-level database locking (when inventory is in DB)**

- If stock is stored in a database (multi-machine fleet / restock service), lock the row before changing quantity:

```sql
SELECT quantity FROM item_slots WHERE slot_code = 'A1' FOR UPDATE;
-- then UPDATE quantity = quantity - 1 ...
-- then COMMIT
```

- `FOR UPDATE` = pessimistic lock on that slot row until commit.
- You say: *“In Java demo we synchronize the slot; in production DB we SELECT … FOR UPDATE.”*

### Database Schema

```sql
CREATE TABLE item_slots (
    slot_code VARCHAR(10) PRIMARY KEY,
    item_name VARCHAR(100) NOT NULL,
    price DECIMAL(10, 2) NOT NULL CHECK (price >= 0),
    quantity INT NOT NULL CHECK (quantity >= 0),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE change_inventory (
    vending_machine_id VARCHAR(50) NOT NULL,
    coin_denomination DECIMAL(5, 2) NOT NULL, -- 5.00, 1.00, 0.25
    coin_count INT NOT NULL CHECK (coin_count >= 0),
    PRIMARY KEY (vending_machine_id, coin_denomination)
);

CREATE TABLE vending_transactions (
    transaction_id VARCHAR(64) PRIMARY KEY,
    slot_code VARCHAR(10) REFERENCES item_slots(slot_code),
    amount_paid DECIMAL(10, 2) NOT NULL,
    change_dispensed DECIMAL(10, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- SUCCESS, REFUNDED, FAILED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**What each table is for (say this):**

- `item_slots` → what is in each button (Coke A1, price, how many left)
- `change_inventory` → how many $5 / $1 / $0.25 this machine can still give as change (CoR)
- `vending_transactions` → log of each buy/refund attempt

---

## 5. Follow-ups

- Can’t make change → cancel + refund credit  
- Motor jam after debit → compensate / refund (saga talk)  
- `MaintenanceState` for refill  

---

## 6. SOLID Principles Applied

| | How |
|--|-----|
| **S** | `VendingMachine` = orchestration · `ItemSlot` = slot stock · `ItemMotor`/`DisplayScreen`/`CoinReturnTray` = hardware only · each `Transaction` = one action |
| **O** | Add `MaintenanceState`, `CardPaymentTransaction`, or a new denomination handler without rewriting the core flow |
| **L** | Any `Transaction` can run via `execute(VendingMachine)`; any `ChangeDispenser` can sit in the chain |
| **I** | `VendingMachineState` stays thin: `insertCoin` / `selectItem` / `dispenseItem` / `refund` |
| **D** | Purchase flow talks to hardware wrappers and dispenser abstractions, not raw device code |

---

## 7. Design Patterns

| Pattern | How used |
|---------|----------|
| **State** | `IdleState` / `HasMoneyState` / `DispensingState` hold lifecycle behavior, so transitions avoid big `if-else` on machine status |
| **Command** | `PurchaseTransaction` and `RefundTransaction` wrap one business action each; the machine runs them the same way |
| **Chain of Responsibility** | `$5 → $1 → $0.25` change chain; each handler dispenses what it can and passes the remainder |
| **Facade** | `VendingMachine` is the single entry point: `insertCoin`, `selectItem`, `requestRefund`; it hides states, inventory, peripherals, and change flow |

---

## 8. Quick Interview Talking Points (under 60 seconds)

When asked: *“Explain the design patterns and SOLID principles you used”* — say exactly this:

1. **State Machine:**  
   *“I used the State Pattern for Idle, HasMoney, and Dispensing. That isolates transitions and avoids messy if-else on status.”*

2. **Transaction Extensibility:**  
   *“I used the Command Pattern — PurchaseTransaction and RefundTransaction. Adding card payment later does not rewrite the state machine. That’s OCP.”*

3. **Change Dispensing Engine:**  
   *“I used Chain of Responsibility for change: $5 → $1 → $0.25. Each handler takes what it can and passes the remainder.”*

4. **Decoupled Architecture:**  
   *“I followed SRP and DIP: motors, screen, and coin tray are hardware only; purchase/refund logic lives in commands, not inside the state classes.”*
