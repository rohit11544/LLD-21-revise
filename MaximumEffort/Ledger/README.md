# LLD — Double-Entry Ledger & Wallet

Google L4–sized: balanced journal entries, immutable audit log, wallet transfer + overdraft guard.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Double-entry: every txn has **Σ Debits = Σ Credits**
- Account types used here: **ASSET** (bank) / **LIABILITY** (user wallets)
- Atomic wallet deposit / transfer
- Append-only journal — **no UPDATE/DELETE**; refunds = compensating txn
- **Balances = journal replay only** (no balance field on `Account`; O(T) scan). Snapshot cache = production stretch

### Non-functional

- Cents (`long`)
- Ordered per-account locks (anti-deadlock)
- Overdraft policy via `allowNegative` (wallets = false)

---

## 2. Architecture (00:05 – 00:12)

```
                    LedgerService (Facade)
                           │
           ┌───────────────┼───────────────┐
           ▼               ▼               ▼
        Account         Entry/Txn      Journal (COW list)
     (+ per-acc lock)  (immutable)    append-only
```

**Wallet model (this LLD):** user wallet = **LIABILITY** (credit increases balance).  
Transfer Alice→Bob: `DR Alice` + `CR Bob`. Deposit: `DR Bank(ASSET)` + `CR Alice`.

### Why Debit / Credit move balances this way

| Account Type | Debit | Credit |
| ------------ | ----- | ------ |
| **Asset** | **Increase** | **Decrease** |
| **Liability** | **Decrease** | **Increase** |

**Asset** (things you own — cash, bank settlement, gold):  
Debit ↑ because you receive/acquire more of the asset. Credit ↓ because the asset leaves you.

- ₹100 into bank cash → **Debit Bank Asset** ₹100 (bank has ₹100 more cash)  
- ₹100 paid out from bank cash → **Credit Bank Asset** ₹100 (bank has ₹100 less cash)

**Liability** (things you owe — customer wallet, loan payable):  
Credit ↑ because your obligation grows. Debit ↓ because you owe less.

- Alice deposits ₹100 → **Credit Alice Wallet** ₹100 (bank owes Alice ₹100 more)  
- Alice spends ₹40 → **Debit Alice Wallet** ₹40 (bank owes Alice ₹60)

**Memory trick**

```text
ASSET
------
Debit  = Receive/Gain asset  ↑
Credit = Give/Lose asset     ↓

LIABILITY
----------
Credit = Owe more            ↑
Debit  = Owe less            ↓
```

This is all you need for this LLD. `calculateDeltas()`, `deposit()`, `transfer()`, and `getAccountBalance()` just implement these two rules.

**Balances:** always computed by **replaying the journal** for that account. `Account` stores type/policy/lock only — never a running balance.

---

## 3. Code (one file)

```
Ledger/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/Ledger/code
javac Main.java && java Main
```

---

## 4. Fixes in this ship

| Issue | Fix |
|-------|-----|
| Overdraft only hard-coded LIABILITY | `Account.allowNegative`; wallets `false` |
| Raw entries only | `deposit()` / `transfer()` / `reverse()` helpers |
| Blind deposit/transfer args | Type checks: deposit ASSET→LIABILITY; transfer LIABILITY↔LIABILITY |
| Reversal talk-only | Compensating mirror legs demo |
| Read vs post races | Balance under account lock for API reads; post validates under ordered locks |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | Account = identity/policy · Entry/Txn = immutable facts · Ledger = post/lock/balance |
| **O** | New helper flow (deposit/transfer/reverse) without changing journal core |
| **D** | Callers use Facade APIs, not raw list mutation |

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `deposit` / `transfer` / `postTransaction` / `reverse` / `getAccountBalance` — hides locks, deltas, journal |
| **Append-only log** | Immutable `Transaction` + `Entry`; reverse = new txn (mechanism / event-sourcing lite) |

---

## 7. Interview Q&A

**“Why sort account ids?”**  
Same as seats/meetings — global lock order avoids deadlock.

**“O(T) balance?”**  
This LLD: balance = full journal replay only. Production stretch: snapshot + sum(entries since snapshot); update snapshot in the same commit path.

**“Refund without delete?”**  
Post compensating txn (flip DR↔CR). Audit trail stays complete.

**“ASSET wallet instead?”**  
Flip deposit/transfer legs; still use `allowNegative=false` on user cash accounts.
