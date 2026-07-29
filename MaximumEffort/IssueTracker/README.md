# LLD — Jira / Issue Tracker (Dynamic Workflow Engine)

Google L4–sized: issues, **config-driven** status graphs, transition guards, audit, hierarchy links, Observer hooks.  
Not full Jira (no JQL/boards/sprints).

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional Requirements

- **Issues:** Types (EPIC, STORY, BUG, …), summary, assignee, reporter, custom fields.
- **Dynamic workflows:** Per `IssueType` graph of `Status` nodes + `Transition` edges (not hardcoded switch/enums for states).
- **Guards:** Rules before transition (assignee required, resolution required, …).
- **Hierarchy:** `parentIssueId` / `childIssueIds` links.
- **Audit:** Immutable logs for **transitions** and key **field updates** (assignee, custom fields).
- **Observer:** Listeners notified after successful transitions (notifications / cascades — verbal).

### Non-Functional Requirements

- **OCP:** New statuses/workflows via registry config, not by editing `Issue`.
- **Concurrency:** Per-issue `ReentrantLock` around transition/field updates.
- **Extensibility:** Pluggable guards + listeners.

---

## 2. Architecture (00:05 – 00:12)

```
                 IssueService (Facade)
          ┌──────────────┼──────────────┐
          ▼              ▼              ▼
   WorkflowRegistry   Issue store    Observers
          │
          ▼
      Workflow (graph)
      Status --Transition(+Guards)--> Status
```

**Interview mental model** (reconstruct the design from this picture):

```text
                    IssueService
                         │
     ┌───────────────────┼────────────────────┐
     │                   │                    │
     ▼                   ▼                    ▼
 WorkflowRegistry     Issue Store        Audit Log
     │
     ▼
  Workflow (Graph)
     │
     ▼
 Transition (Edge)
     │
     ▼
 TransitionGuard
     │
     ▼
 Update Issue
     │
     ▼
 Notify Observers  (outside the lock)
```

*Hierarchy:* `parentIssueId` / `childIssueIds` link issues; cascade-close children = Observer talk (not coded).

### Complete execution flow

```text
APPLICATION STARTS
        │
        ▼
Create Status objects → Build Workflow graphs
        │
        ▼
OPEN ──(Guard)──► IN_PROGRESS ──► IN_REVIEW ──(Guard)──► CLOSED
        │
        ▼
Register workflows in WorkflowRegistry
        │
        ▼
Create IssueService → Register Observers (listeners)

────────────────────────────────────────────────

createIssue()
        │
        ▼
Registry → workflow for BUG → initial status OPEN
        │
        ▼
New Issue → issueStore
        │
        ▼
(optional) link parent/child ids

────────────────────────────────────────────────

assignIssue() / setCustomField()
        │
        ▼
Lock issue → update field → AuditLog (FIELD_UPDATE) → Unlock

────────────────────────────────────────────────

transitionIssue()
        │
        ▼
Lock issue
        │
        ▼
Get Workflow → find edge (from → to)
        │
   No edge? ──► reject
        │ Yes
        ▼
Execute Guards
        │
   Fail? ──► reject
        │ Pass
        ▼
Update status → AuditLog (TRANSITION) → Unlock
        │
        ▼
Notify Observers  ← outside lock (avoid listener deadlocks)
        │
        ├─► demo: println
        └─► can plug: Email / Slack / Analytics / cascade-close
            (same listener hook — not all implemented in this file)
```

### Component responsibilities

| Component | Responsibility |
|-----------|----------------|
| **Issue** | Stores issue data |
| **Status** | Represents a workflow state (value object) |
| **Workflow** | Valid transitions graph for a type |
| **Transition** | One legal edge (from → to) |
| **TransitionGuard** | Validates whether a transition is allowed |
| **WorkflowRegistry** | Workflow for an `IssueType` |
| **AuditLog** | History of transitions + field updates |
| **IssueEventListener** | Reacts to successful transitions |
| **IssueService** | Orchestrates everything (**Facade**) |

---

## 3. Code (one file)

```
IssueTracker/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/IssueTracker/code
javac Main.java && java Main
```

---

## 4. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `IssueService` — create, assign, fields, transition; hides graph, guards, audit, listeners |
| **Graph state machine** | `Workflow` = `Map<Status, Map<Status, Transition>>` |
| **Strategy** | `TransitionGuard` (functional / lambda) |
| **Observer** | `IssueEventListener.onTransitioned(...)` — notify after unlock |
| **Per-issue lock** | Atomic check + apply on one issue |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | Issue = data · Workflow/Transition = rules · Guards = validation · Service = API · Audit/Listeners = side effects |
| **O** | Register new workflow/status edges without changing `Issue` |
| **L** | Any guard/listener plugs into the same hooks |
| **I** | Tiny `TransitionGuard` / `IssueEventListener` |
| **D** | Service depends on `WorkflowRegistry` + abstractions for guards/listeners |

---

## 6. Interview Q&A

**“Cascade close sub-tasks?”**  
Observer on parent `CLOSED` → call `transitionIssue` on each child (or a dedicated cascade policy). Same hook as Email/Slack — plug another listener.

**“Load workflows from JSON?”**  
`Status` is a string value object; builder parses nodes/edges into `Workflow` at startup — no Java enum redeploy for new statuses.
