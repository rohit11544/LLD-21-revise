# LLD — Splitwise (Expense Sharing)

Google L4–sized: group expenses, Strategy splits, balance graph, greedy debt simplify.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Users + Groups
- Split strategies: **EQUAL** / **EXACT** / **PERCENTAGE**
- Directed balances: `A → B` means A owes B
- Greedy simplify → settlement list (at most N−1 txns)

### Non-functional

- Money as **integer cents** (no `double` drift)
- Group-level lock for balance updates

---

## 2. Architecture (00:05 – 00:12)

```
                 SplitwiseService (Facade)
                    ┌──────────┴──────────┐
                    ▼                     ▼
              SplitStrategy         BalanceSheet + DebtSimplifier
           Equal|Exact|Percent      (net → greedy match)
```

**Expense path (one bill)**

```text
addExpense(...)
  → Strategy.calculateSplits  (fill Split amounts for THIS bill only)
  → under group lock: for each Split (except payer)
        BalanceSheet.addDebt(debtor → paidBy, cents)
```

`Split` list = **temp worksheet** for one expense. Long-lived state lives in `BalanceSheet`. Exact/Percent: `values.get(i)` must match `splits.get(i)` order.

---

## 3. Code (one file)

```
Splitwise/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/Splitwise/code
javac Main.java && java Main
```

---

## 4. Main algorithm — greedy debt simplification

Raw graph can have many edges (`A→B`, `B→C`, …). Settle-up should use as few payments as possible.

### Step 1 — Net each user

From all edges `debtor → creditor : amt`:

```text
net[debtor]  -= amt
net[creditor] += amt
```

- `net < 0` → still owes overall (debtor)  
- `net > 0` → owed overall (creditor)  
- `net = 0` → settled  

Σ nets = **0** always.

### Step 2 — Greedy match

Two max-heaps (by magnitude): largest debtor vs largest creditor.

```text
while debtors and creditors not empty:
  settle = min(|debt|, credit)
  record: debtor pays creditor settle
  push back whoever still has leftover net
```

Each step **zeros at least one** user → at most **N−1** transactions for N people.

```text
Example (Goa demo after equal $100 + exact taxi):
  nets → greedy pairs → few pay-to lines
  (simplify is a settlement VIEW; raw BalanceSheet can stay)
```

**Not** optimal min-cash-flow in the NP-hard sense — greedy is the L4 / interview standard.

---

## 5. Fixes in this ship

| Issue | Fix |
|-------|-----|
| `double` money | `long` cents + `Money` helper |
| `$100 / 3` | Equal: `base + 1` for first `remainder` people |
| Percent leftover | Last participant gets `total - assigned` |
| Heap mutate `Map.Entry` | `BalanceNode` objects |
| Exact/Percent size | Validate `values.size() == splits.size()` |

---

## 6. SOLID

| | How |
|--|-----|
| **S** | Strategy = split math · BalanceSheet = edges · Simplifier = settle view · Service = API |
| **O** | Add `SharesSplitStrategy` without editing facade |
| **L/I** | Thin `calculateSplits(...)` |
| **D** | Service depends on `SplitStrategy` |

---

## 7. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Clients call `addExpense` / `getSimplifiedGroupDebts` / register APIs; `SplitwiseService` hides group lock, balance graph, and simplify heaps |
| **Strategy** | `EqualSplitStrategy` / `ExactSplitStrategy` / `PercentSplitStrategy` — how to turn a bill into per-person cents without changing `addExpense` |

**Why Strategy?**  
Equal vs Exact vs Percent are different rules for the same job (“fill each `Split` amount”). Swap or add a type at call site; facade always: calculate → write debts.

**Why Facade?**  
One entry point for “record expense” and “show settle-up”; callers never touch `BalanceSheet` or heaps directly.

**Balance graph / greedy heaps** — core algorithm (mechanism), not a separate GoF pattern.

---

## 8. Interview Q&A

**“Why ≤ N−1 transactions?”**  
Nets sum to 0; each greedy step zeros at least one user → at most N−1 steps.

**“Rounding?”**  
Work in cents; distribute remainder pennies so Σ shares = total.

**“Simplify vs live graph?”**  
Simplify returns a **settlement plan**; raw debts can stay until users mark paid (production).

**“Does calculateSplits wipe old expenses?”**  
No — each expense has new `Split`s; amounts are merged into `BalanceSheet` immediately under the group lock.
