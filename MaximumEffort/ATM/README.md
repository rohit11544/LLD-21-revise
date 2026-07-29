# LLD — ATM / Vending Machine System

## 1. Requirement & Scope Clarification

### Functional Requirements

- **Card & Authentication:** User inserts a card, enters a PIN, and gets authenticated via an external `BankService`.
- **Hardware Abstractions:** System decouples physical hardware (`CardReader`, `Keypad`, `CashDispenser`, `ReceiptPrinter`).
- **Extensible Transactions:** Support multiple operations (Withdrawal, Deposit, BalanceInquiry) using an extensible command hierarchy.
- **Cash Dispensing:** Dispense cash notes ($50, $20, $10) using a true **Chain of Responsibility** pattern.
- **State Machine:** Manage state transitions seamlessly (`Idle → HasCard → Authenticated → Idle`).

### Non-Functional Requirements

- **Thread Safety:** State transitions and financial balances are concurrency-safe under simultaneous user and hardware calls.
- **Fault Tolerance & Atomicity:** Account debits only occur if physical hardware dispensing succeeds; otherwise, the transaction rolls back gracefully.

---

## 2. Architecture

```
                          ┌──────────────────────┐
                          │   ATM (Facade)       │
                          └──────────┬───────────┘
                                     │ holds
                                     ▼
                          ┌──────────────────────┐
                          │       ATMState       │
                          └──────────┬───────────┘
                                     │
         ┌───────────────────┬───────┴───────────┬───────────────────┐
         ▼                   ▼                   ▼                   ▼
     IdleState          HasCardState     AuthenticatedState    MaintenanceState

  CardReader · ReceiptPrinter · CashDispenser ($50→$20→$10) · BankService

                                        Transaction (Command)
                               WithdrawTransaction | DepositTransaction
```

---

## 3. Code layout (interview-simple — ONE file)

```
ATM/
  README.md          ← design + talk track
  code/
    Main.java        ← entire solution
```

```bash
cd MaximumEffort/ATM/code
javac Main.java && java Main
```

Write **one file** in interviews. Diagram + explanation > LOC.

---

## 4. Concurrency Model & Database Schema (00:30 – 00:40)

### Concurrency & Locking Strategy

**Why we care:**  
In an interview, “thread safety” means: if two things happen at the same time (two button presses, two users, restock + purchase), we must not corrupt **state**, **balances**, or **inventory counts**.

**1) ATM / machine state thread safety**

- The ATM holds one field: `currentState` (Idle / HasCard / Authenticated).
- We mark `setState(...)` as `synchronized`.
- Meaning in plain words: **only one thread can change the state at a time**.
- So you never get half-updated weird states (e.g. two threads both thinking they moved from Idle → HasCard).

Same idea on account money methods (`deduct` / `add`): they are `synchronized` so two withdraws cannot read the same balance and both succeed incorrectly.

**2) Row-level database locking (pessimistic locking)**

- In real systems, the bank balance lives in a **database row**, not only in Java memory.
- Before changing balance, the bank service locks that row so no other request can change it until we finish.
- SQL pattern interviewers expect:

```sql
SELECT balance FROM accounts WHERE account_number = 'ACC-1001' FOR UPDATE;
-- then UPDATE balance ...
-- then COMMIT
```

- `FOR UPDATE` = “this row is mine until I commit.” That is **pessimistic locking** (lock first, then modify).
- You say out loud: *“In-memory we use synchronized; in DB we use SELECT … FOR UPDATE on the account row.”*

### Database Schema

```sql
CREATE TABLE accounts (
    account_number VARCHAR(50) PRIMARY KEY,
    user_id VARCHAR(50) NOT NULL,
    balance DECIMAL(15, 2) NOT NULL CHECK (balance >= 0),
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE atm_inventory (
    atm_id VARCHAR(50) NOT NULL,
    denomination INT NOT NULL, -- 50, 20, 10
    note_count INT NOT NULL CHECK (note_count >= 0),
    PRIMARY KEY (atm_id, denomination)
);

CREATE TABLE transactions (
    transaction_id VARCHAR(64) PRIMARY KEY,
    account_number VARCHAR(50) REFERENCES accounts(account_number),
    atm_id VARCHAR(50) NOT NULL,
    type VARCHAR(20) NOT NULL, -- WITHDRAWAL, DEPOSIT
    amount DECIMAL(15, 2) NOT NULL,
    status VARCHAR(20) NOT NULL, -- SUCCESS, FAILED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
```

**What each table is for (say this):**

- `accounts` → customer money (what `BankService` updates)
- `atm_inventory` → how many $50/$20/$10 notes this ATM still has (CoR inventory)
- `transactions` → audit log of each withdraw/deposit attempt

---

## 5. Follow-ups

- Dispenser jam → debit only after hardware confirms (saga / compensate)
- Refill → `MaintenanceState` + tech card
- Peripherals stay dumb; business stays in Command + BankService

---

## 6. SOLID Principles Applied

| | How |
|--|-----|
| **S** | `ATM` = orchestration · `CardReader`/`ReceiptPrinter` = hardware · `BankService` = auth/ledger · each `Transaction` = one money op |
| **O** | Add `MaintenanceState`, `BalanceInquiryTransaction`, or `$100` dispenser without rewriting core |
| **L** | Any `Transaction` runs via `executeTransaction`; any `CashDispenser` link can sit in the chain |
| **I** | `ATMState` stays thin: `insertCard` / `authenticatePin` / `executeTransaction` / `ejectCard` |
| **D** | ATM depends on `BankService` abstraction (not `MockBankService` / DB); withdraw uses dispenser chain abstraction |

---

## 7. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `insertCard` / `authenticatePin` / `executeTransaction` / `ejectCard`; ATM hides states, bank, peripherals, and cash chain |
| **State** | Idle / HasCard / Authenticated own lifecycle behavior — avoids `if-else` on status |
| **Command** | `WithdrawTransaction` / `DepositTransaction` — one business action each; add balance inquiry without rewriting states |
| **Chain of Responsibility** | `$50 → $20 → $10` notes; each handler takes what it can and passes the remainder |

---

## 8. Quick Interview Talking Points (under 60 seconds)

When asked: *“Explain the design patterns and SOLID principles you used”* — say exactly this:

1. **State Machine:**  
   *“I used the State Pattern for the ATM lifecycle (Idle, HasCard, Authenticated). That isolates transition rules and avoids messy if-else on status.”*

2. **Transaction Extensibility:**  
   *“I used the Command Pattern — WithdrawTransaction and DepositTransaction extend Transaction. Adding transfer or PIN change does not alter the state machine. That’s OCP.”*

3. **Cash Dispensing Engine:**  
   *“I used Chain of Responsibility for notes: $50 processes what it can, then passes the remainder to $20 and $10. Inventory stays modular.”*

4. **Decoupled Architecture:**  
   *“I followed SRP and DIP: CardReader/Printer are hardware only; all account and balance work goes through an abstract BankService. The ATM never owns storage or account objects directly.”*
