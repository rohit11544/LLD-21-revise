import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * RBAC + Resource ACL — ONE FILE (interview style)
 * Patterns: Facade + role hierarchy flatten cache
 *
 * Priority: Explicit user DENY > user ALLOW > role ACL DENY/ALLOW > inherited role perms
 * ACL is per (principal, permission) — not allow-all.
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

enum Permission {
    READ,
    WRITE,
    DELETE,
    EXECUTE,
    ADMIN   // super-permission: grants everything when present on effective set
}

enum AccessType {
    ALLOW,
    DENY
}

class User {
    private final String userId;
    private final String name;
    private final Set<Role> directRoles = new CopyOnWriteArraySet<>();

    User(String userId, String name) {
        this.userId = userId;
        this.name = name;
    }

    String getUserId() { return userId; }
    String getName() { return name; }
    Set<Role> getDirectRoles() { return directRoles; }

    void addRole(Role role) { directRoles.add(role); }
    void removeRole(Role role) { directRoles.remove(role); }
}

class Role {
    private final String roleId;
    private final String name;
    private final Set<Permission> directPermissions = new CopyOnWriteArraySet<>();
    /** Children whose permissions this role inherits (Admin → Editor → Viewer). */
    private final Set<Role> childRoles = new CopyOnWriteArraySet<>();

    Role(String roleId, String name) {
        this.roleId = roleId;
        this.name = name;
    }

    String getRoleId() { return roleId; }
    String getName() { return name; }
    Set<Permission> getDirectPermissions() { return directPermissions; }
    Set<Role> getChildRoles() { return childRoles; }

    void addPermission(Permission permission) { directPermissions.add(permission); }
    void addChildRole(Role child) { childRoles.add(child); }
}

/**
 * Per-resource ACL: overrides keyed by userId/roleId AND permission.
 * Example: Bob ALLOW WRITE on DOC_101 does not grant Bob DELETE.
 */
class Resource {
    private final String resourceId;
    private final String name;
    private final Map<String, Map<Permission, AccessType>> userAcl = new ConcurrentHashMap<>();
    private final Map<String, Map<Permission, AccessType>> roleAcl = new ConcurrentHashMap<>();

    Resource(String resourceId, String name) {
        this.resourceId = resourceId;
        this.name = name;
    }

    String getResourceId() { return resourceId; }
    String getName() { return name; }

    void setUserAccess(String userId, Permission permission, AccessType accessType) {
        userAcl.computeIfAbsent(userId, k -> new EnumMap<>(Permission.class))
                .put(permission, accessType);
    }

    void setRoleAccess(String roleId, Permission permission, AccessType accessType) {
        roleAcl.computeIfAbsent(roleId, k -> new EnumMap<>(Permission.class))
                .put(permission, accessType);
    }

    AccessType getUserAccess(String userId, Permission permission) {
        Map<Permission, AccessType> perms = userAcl.get(userId);
        return perms == null ? null : perms.get(permission);
    }

    AccessType getRoleAccess(String roleId, Permission permission) {
        Map<Permission, AccessType> perms = roleAcl.get(roleId);
        return perms == null ? null : perms.get(permission);
    }
}

// ========== HIERARCHY FLATTENER ==========

class RoleHierarchyManager {
    private final Map<String, Set<Permission>> flattenedCache = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    void invalidateCache() {
        rwLock.writeLock().lock();
        try {
            flattenedCache.clear();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    /** Effective = direct + all descendants. Cycle-safe DFS. Cached for O(1) set lookup later. */
    Set<Permission> getEffectivePermissions(Role role) {
        rwLock.readLock().lock();
        try {
            Set<Permission> cached = flattenedCache.get(role.getRoleId());
            if (cached != null) return cached;
        } finally {
            rwLock.readLock().unlock();
        }

        rwLock.writeLock().lock();
        try {
            Set<Permission> cached = flattenedCache.get(role.getRoleId());
            if (cached != null) return cached;

            Set<Permission> effective = new HashSet<>();
            flatten(role, effective, new HashSet<>());
            Set<Permission> frozen = Collections.unmodifiableSet(effective);
            flattenedCache.put(role.getRoleId(), frozen);
            return frozen;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void flatten(Role role, Set<Permission> out, Set<String> visited) {
        if (!visited.add(role.getRoleId())) {
            System.out.println("[Hierarchy] cycle skipped at role=" + role.getRoleId());
            return;
        }
        out.addAll(role.getDirectPermissions());
        for (Role child : role.getChildRoles()) {
            flatten(child, out, visited);
        }
    }
}

// ========== FACADE ==========

class AccessControlService {
    private final RoleHierarchyManager hierarchyManager = new RoleHierarchyManager();
    private final Map<String, User> userRegistry = new ConcurrentHashMap<>();
    private final Map<String, Role> roleRegistry = new ConcurrentHashMap<>();

    void registerUser(User user) {
        userRegistry.put(user.getUserId(), user);
    }

    void registerRole(Role role) {
        roleRegistry.put(role.getRoleId(), role);
        hierarchyManager.invalidateCache();
    }

    void addRoleInheritance(Role parent, Role child) {
        parent.addChildRole(child);
        hierarchyManager.invalidateCache();
    }

    /**
     * Core check.
     * 1) User ACL for this permission (DENY / ALLOW)
     * 2) Each direct role: role ACL for this permission, else inherited perms (+ ADMIN bypass)
     */
    boolean hasPermission(User user, Permission permission, Resource resource) {
        if (user == null || permission == null) return false;

        if (resource != null) {
            AccessType userAccess = resource.getUserAccess(user.getUserId(), permission);
            if (userAccess == AccessType.DENY) return false;
            if (userAccess == AccessType.ALLOW) return true;
        }

        for (Role role : user.getDirectRoles()) {
            if (resource != null) {
                AccessType roleAccess = resource.getRoleAccess(role.getRoleId(), permission);
                if (roleAccess == AccessType.DENY) continue;
                if (roleAccess == AccessType.ALLOW) return true;
            }

            Set<Permission> effective = hierarchyManager.getEffectivePermissions(role);
            if (effective.contains(Permission.ADMIN) || effective.contains(permission)) {
                return true;
            }
        }
        return false;
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        AccessControlService rbac = new AccessControlService();

        Role viewer = new Role("R_VIEWER", "Viewer");
        viewer.addPermission(Permission.READ);

        Role editor = new Role("R_EDITOR", "Editor");
        editor.addPermission(Permission.WRITE);

        Role admin = new Role("R_ADMIN", "Admin");
        admin.addPermission(Permission.DELETE);

        rbac.registerRole(viewer);
        rbac.registerRole(editor);
        rbac.registerRole(admin);

        // Admin inherits Editor inherits Viewer
        rbac.addRoleInheritance(admin, editor);
        rbac.addRoleInheritance(editor, viewer);

        User alice = new User("U1", "Alice (Admin)");
        alice.addRole(admin);

        User bob = new User("U2", "Bob (Viewer)");
        bob.addRole(viewer);

        rbac.registerUser(alice);
        rbac.registerUser(bob);

        Resource doc1 = new Resource("DOC_101", "Design Document");

        System.out.println("=== TEST 1: INHERITED PERMISSIONS ===");
        System.out.println("Alice READ:  " + rbac.hasPermission(alice, Permission.READ, doc1));   // true
        System.out.println("Alice WRITE: " + rbac.hasPermission(alice, Permission.WRITE, doc1));  // true
        System.out.println("Alice DELETE:" + rbac.hasPermission(alice, Permission.DELETE, doc1)); // true
        System.out.println("Bob WRITE:   " + rbac.hasPermission(bob, Permission.WRITE, doc1));    // false

        System.out.println("\n=== TEST 2: PERMISSION-SCOPED USER ACL ===");
        doc1.setUserAccess(bob.getUserId(), Permission.WRITE, AccessType.ALLOW);
        System.out.println("Bob WRITE after ALLOW:  " + rbac.hasPermission(bob, Permission.WRITE, doc1));  // true
        System.out.println("Bob DELETE still false: " + rbac.hasPermission(bob, Permission.DELETE, doc1)); // false

        doc1.setUserAccess(alice.getUserId(), Permission.DELETE, AccessType.DENY);
        System.out.println("Alice DELETE after DENY: " + rbac.hasPermission(alice, Permission.DELETE, doc1)); // false
        System.out.println("Alice READ still true:   " + rbac.hasPermission(alice, Permission.READ, doc1));   // true

        System.out.println("\n=== TEST 3: CYCLE GUARD ===");
        // Introduce a cycle Editor -> Admin (in addition to Admin -> Editor)
        rbac.addRoleInheritance(editor, admin);
        System.out.println("Alice READ with cycle still works: "
                + rbac.hasPermission(alice, Permission.READ, doc1));
    }
}
