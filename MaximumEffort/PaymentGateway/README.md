# LLD — Payment Gateway

Google L4–sized: bank **Adapters**, **idempotency** (key + fingerprint), ordered **fallback**, thin txn status.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Route payments (Card / UPI / NetBanking) to acquirers (Stripe, Razorpay, …)
- Idempotency: same payment-attempt key + same payload → same response, no double charge
- Pluggable bank adapters + ordered fallback
- Lifecycle: `INITIATED → PENDING → SUCCESS | FAILED`

### Non-functional

- Per-key lock for concurrent duplicate submits
- OCP: new acquirer = new `BankAdapter`

---

## 2. Architecture (00:05 – 00:12)

```
              PaymentGatewayFacade
         ┌───────────┼───────────┐
         ▼           ▼           ▼
 IdempotencyEngine  BankAdapter  Ordered fallback
 (per-key lock)   Stripe/Razorpay  (registration order)
```

**Not claimed:** success-rate “smart” routing (separate dynamic-router LLD).  
Here = `supportsMethod` filter + **registration-order fallback**.

### Lazy `Supplier` (don’t charge before idempotency check)

```java
return idempotencyEngine.executeIdempotent(request, () -> doProcess(request));
```

`()` → `doProcess` is a **Supplier** (recipe). `executeIdempotent` runs first; `doProcess` runs only on cache miss via `get()`.

---

## 3. End-to-end flow (main diagram)

```text
User clicks "Pay"
        │
        │  (Idempotency Key / Payment Attempt ID = KEY_1001)
        ▼
PaymentGatewayFacade.processPayment()
        │
        ▼
IdempotencyEngine.executeIdempotent()
        │
 Acquire Lock for KEY_1001
        │
        ▼
 Is KEY_1001 already cached?
    ┌────────────┴─────────────┐
   YES                         NO
    │                          │
    ▼                          ▼
Compare Fingerprint     Create Transaction
(ACC77|₹5000|UPI …)     Status = INITIATED
    │                          │
 ┌──┴──┐                       ▼
Same  Different         Status = PENDING
 │       │                     │
 ▼       ▼                     ▼
Return  Reject          Find adapters supporting
cached  (payload        payment method
resp.    mismatch)             │
                               ▼
              Try adapters in registration order
                               │
            ┌──────────────────┴──────────────────┐
            ▼                                     ▼
   Razorpay Success                      Razorpay Failed
            │                                     │
            ▼                                     ▼
   Status = SUCCESS                      Try next (Stripe)
            │                                     │
            ▼                              ┌──────┴──────┐
   Cache Response                         ▼             ▼
            │                      Stripe Success  Stripe Failed
            ▼                             │             │
         Return                           ▼             ▼
                                   Status=SUCCESS  Status=FAILED
                                          │             │
                                          ▼             ▼
                                   Cache Response  Cache Response
                                          │             │
                                          ▼             ▼
                                       Return        Return
```

---

## 4. Idempotency model

### Two checks, two jobs

| Concern | Guard |
|---------|--------|
| Retry same payment attempt (timeout / double-click) | **Same idempotency key** → cached response |
| Buggy client changes amount/method but reuses key | **Fingerprint mismatch** → **reject** |
| User switches UPI → Card on purpose | UI creates a **new** key → new attempt |

**Order id ≠ payment attempt id.** One order can have many attempts (each with its own key).

### Why not combine `idempotencyId + fingerprint` into one cache key?

They solve different problems. Combined key **fails** the corruption case:

```text
1) KEY_1001 + fp(₹5000,UPI) → FAILED, cached under combined key A
2) Same KEY_1001, amount corrupted → fp(₹9999,UPI)
   combined key B ≠ A → looks NEW → would PROCESS wrong amount
```

Lookup by **key only**, then **verify** fingerprint → reject misuse.

### Visual 1 — Successful retry (no second bank call)

```text
Payment Attempt          Later Retry
KEY_1001                 KEY_1001
₹5000 UPI                ₹5000 UPI
    │                        │
    ▼                        ▼
 Gateway                  KEY exists
    │                        │
    ▼                   Fingerprint matches
 SUCCESS                     │
    │                        ▼
    ▼                 Return cached response
 Store:               (No bank call)
 KEY_1001
  ├── Fingerprint ACC77|5000|UPI
  └── Response SUCCESS
```

### Visual 2 — Buggy client (reject)

```text
Original                 Later
KEY_1001                 KEY_1001
₹5000 UPI                ₹7000 UPI   ← corrupted
    │                        │
    ▼                        ▼
 Stored                  KEY found
                             │
                             ▼
                      Fingerprint mismatch
                             │
                             ▼
                      Reject
                      IDEMPOTENCY_KEY_PAYLOAD_MISMATCH
```

### Visual 3 — New payment attempt (new key)

```text
Order ORD-101
    │
    ├── Payment Attempt 1 (KEY_1001)  ₹5000 UPI      → FAILED
    │
    └── User changes method
            │
            ▼
        Payment Attempt 2 (KEY_1002)  ₹5000 Card   → new process

Same order · different attempts · different idempotency keys
```

---

## 5. Transaction & adapter state

### Transaction status

```text
INITIATED
    │
    ▼
PENDING
    │
    ├── Success ──────► SUCCESS
    │
    └── All adapters failed ──► FAILED
```

### Adapter fallback

```text
Supports method?
    │
    ▼
Candidates [Razorpay, Stripe]   ← registration order
    │
    ▼
Try Razorpay
    │
 ┌──┴──┐
Success Failure
 │        │
 │        ▼
 │    Try Stripe
 │        │
 │   ┌────┴────┐
 │ Success  Failure
 ▼    │         │
Return SUCCESS  Return FAILED
```

---

## 6. Code (one file)

```
PaymentGateway/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/PaymentGateway/code
javac Main.java && java Main
```

---

## 7. Fixes in this ship

| Issue | Fix |
|-------|-----|
| Test 3 order wrong | Register **Razorpay then Stripe** so fail→fallback is real |
| Idempotency only SUCCESS | Cache terminal **SUCCESS and FAILED** |
| Key reuse different amount | Payload fingerprint mismatch → reject |
| State machine talk-only | `PaymentTransaction` INITIATED→PENDING→SUCCESS/FAILED |
| “Smart routing” oversell | Document as **ordered fallback** |

---

## 8. SOLID

| | How |
|--|-----|
| **S** | Facade = orchestrate · Idempotency = dedupe · Adapter = bank I/O · Txn = status |
| **O** | New `BankAdapter` without editing `doProcess` core |
| **L/I** | Thin `supportsMethod` / `processPayment` |
| **D** | Facade depends on `BankAdapter`, not Stripe SDK |

---

## 9. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Client calls `processPayment`; hides idempotency, routing loop, txn status |
| **Adapter** | `StripeBankAdapter` / `RazorpayBankAdapter` unify acquirer APIs behind `BankAdapter` |
| **Ordered fallback** | Try candidates in registration order until one succeeds |

---

## 10. Interview Q&A

**Q1. What is an Idempotency Key?**  
Client-generated unique id for a **single payment attempt**. Retries (timeout/network) reuse the same key so the gateway does not charge twice.

**Q2. Why store a Fingerprint?**  
Prove every retry with that key is the **same** request (account, amount, method). Payload change → reject.

**Q3. Why not combine Idempotency Key + Fingerprint into one cache key?**  
Key = which attempt. Fingerprint = integrity of that attempt. Combined, a changed payload looks like a brand-new key — you cannot detect misuse of the original key.

**Q4. Can one Order have multiple Idempotency Keys?**  
Yes.

```text
ORD-101
  ├── Attempt 1 → KEY_1001
  ├── Attempt 2 → KEY_1002
  └── Attempt 3 → KEY_1003
```

**Q5. When is the same Idempotency Key reused?**  
Only retries of the **same** attempt (network timeout, client retry, server timeout) — not when the user changes payment method.

**Q6. Why cache FAILED too?**  
Same-key retry should return the same failure, not open a new charge path.

**Q7. Distributed idempotency?**  
Redis lock (`SET NX PX`) or DB unique `idempotency_key` + store response payload.

**Q8. Bank timeout — charged or not?**  
Stay `PENDING`; reconciliation queries acquirer by txn id before fallback/refund.
