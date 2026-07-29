# LLD — RBAC & Resource ACL

Google L4–sized: roles + hierarchy inheritance, per-resource ACL overrides, fast `hasPermission`.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- Users, Roles, Permissions (`READ` / `WRITE` / `DELETE` / …)
- Role hierarchy: parent inherits children (e.g. Admin → Editor → Viewer)
- Resource ACL: explicit **ALLOW / DENY** per **(user|role, permission)**
- `hasPermission(user, permission, resource)`

### Non-functional

- Flattened permission cache for fast checks
- Thread-safe registries + hierarchy invalidation
- ACL is **permission-scoped** (ALLOW WRITE ≠ ALLOW DELETE)

---

## 2. Architecture (00:05 – 00:12)

```
                 AccessControlService (Facade)
                    ┌──────────┴──────────┐
                    ▼                     ▼
           RoleHierarchyManager      Resource ACL
           (DFS flatten + cache)   (user/role × permission)
```

**Priority**

```text
User DENY  >  User ALLOW  >  Role DENY / ALLOW  >  Inherited role permissions
```

*(Optional: `Permission.ADMIN` as a wildcard bypass — not the same as role named Admin.)*

### Hierarchy vs flatten (must remember)

`addRoleInheritance(parent, child)` only **links** roles (`parent.childRoles += child`) and **invalidates** the cache.

The permission **list** is built later by `getEffectivePermissions` → DFS `flatten`: walk this role + all children + their children, union direct perms, then cache.

```text
getEffectivePermissions(Admin)
        │
        ▼
   In cache? ──Yes──► return cached
        │
       No
        │
        ▼
   flatten(Admin)   // DFS + visited (cycle-safe)
        │
        ▼
   Add Admin direct          {DELETE}
        │
        ▼
   Visit child Editor
        │
        ▼
   Add Editor direct         {DELETE, WRITE}
        │
        ▼
   Visit child Viewer
        │
        ▼
   Add Viewer direct         {DELETE, WRITE, READ}
        │
        ▼
   Store in cache → return
```

So with `Admin → Editor → Viewer`, Admin’s effective set already has all three — you only need `effective.contains(permission)` for inheritance to work.

---

## 3. Code (one file)

```
RBAC/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/RBAC/code
javac Main.java && java Main
```

---

## 4. Fixes vs naive draft

| Issue | Fix |
|-------|-----|
| ACL allow-all | ACL maps `(principal → permission → ALLOW/DENY)` |
| Cycle A↔B | `visited` set in DFS flatten |
| Nested lock recursion | Flatten helper runs under one write lock; no recursive public entry |
| Many `public` classes | Only `Main` is public |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | User/Role/Resource = data · HierarchyManager = flatten/cache · Service = evaluate |
| **O** | New permission enum / role edge without rewriting evaluator core |
| **L/I** | Small domain types; service API is one check method |
| **D** | Service uses hierarchy manager abstraction in spirit (concrete for LLD size) |

*Pluggable `EvaluationStrategy` = optional stretch — algorithm is inlined for L4 clarity.*

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | `hasPermission` + register/inherit APIs; hides flatten cache + ACL maps |
| **DAG / tree walk** | Role children DFS with cycle guard + cache (mechanism; not GoF) |

---

## 7. Interview Q&A

**“Cycles?”**  
`visited` on roleId during flatten; skip + log.

**“10M users / microservices?”**  
Put roles in JWT; cache hierarchy/ACL in Redis; pub-sub invalidate on change; evaluate at gateway.

**“Why not allow-all ACL?”**  
Resource override must be permission-scoped or DENY DELETE would also block READ.
