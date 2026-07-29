# LLD — In-Memory File System

Google L4–sized: POSIX-like tree from `/`, Composite File/Directory, path walk, per-node locks.

---

## 1. Requirement & Scope (00:00 – 00:05)

### Functional

- `mkdir(path)` — create dirs recursively (`/a/b/c`)
- `ls(path)` — file → `[name]`; dir → sorted child names
- `addContentToFile(path, content)` — create or append
- `readContentFromFile(path)` — file contents
- `delete(path)` — remove file or whole directory subtree

### Non-functional

- Trie-like path traversal **O(depth)**
- Fine-grained **per-node** `ReadWriteLock` (not one global FS lock)
- Composite: treat File and Directory uniformly as `FileSystemNode`

---

## 2. Architecture (00:05 – 00:12)

```
                    InMemoryFileSystem (Facade)
                              │
                              ▼
                       FileSystemNode
                    ┌─────────┴─────────┐
                    ▼                   ▼
                 FileNode          DirectoryNode
              (StringBuilder)    Map<name, Node>
```

**Lock rule (fixed):** lock `parent`, mutate/descend, **always unlock that same parent** — never reassign `curr` before unlock.

---

## 3. Code (one file)

```
InMemoryFileSystem/
  README.md
  code/Main.java
```

```bash
cd MaximumEffort/InMemoryFileSystem/code
javac Main.java && java Main
```

---

## 4. Bugs fixed in this ship

| Issue | Fix |
|-------|-----|
| Unlock after `curr = child` | Keep `DirectoryNode parent = curr`; unlock `parent` in `finally` |
| Unlocked `ls` / `read` walk | Hand-over-hand **read** locks while descending |
| Two `public` classes | Only `Main` is public |

---

## 5. SOLID

| | How |
|--|-----|
| **S** | `FileNode` = content · `DirectoryNode` = children · Facade = path APIs |
| **O** | Add `SymLinkNode` as another `FileSystemNode` without rewriting mkdir core |
| **L** | Any node answers `isDirectory` / `getName`; dirs expose children |
| **I** | Thin node API; content ops only on `FileNode` |
| **D** | Facade depends on `FileSystemNode` abstraction |

---

## 6. Design Patterns

| Pattern | How used |
|---------|----------|
| **Facade** | Client calls `mkdir` / `ls` / `addContent…` / `read…` / `delete`; hides tree + locks |
| **Composite** | `FileNode` (leaf) + `DirectoryNode` (composite) under `FileSystemNode` |

---

## 7. Interview Q&A

**“Why per-node locks?”**  
Different directories can be updated in parallel; a global FS lock serializes everything.

**“Recursive delete?”**  
`removeChild` drops the subtree reference → GC. No need to walk every descendant for in-memory LLD.

**“100k files in one dir?”**  
Sort on every `ls` is `O(K log K)`. Production: keep children in a `TreeMap` for ordered inserts.

**“Symlinks?”**  
Third node type storing target path; resolve with a max hop limit to avoid cycles.
