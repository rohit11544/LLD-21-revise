import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-Memory File System — ONE FILE (interview style)
 * Patterns: Facade + Composite (File / Directory)
 *
 *   javac Main.java && java Main
 *
 * Lock rule: always unlock the PARENT you locked — never reassign curr before unlock.
 */

// ========== COMPOSITE ==========

abstract class FileSystemNode {
    protected final String name;
    protected final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    FileSystemNode(String name) {
        this.name = name;
    }

    String getName() { return name; }
    ReentrantReadWriteLock getLock() { return rwLock; }
    abstract boolean isDirectory();
}

class FileNode extends FileSystemNode {
    private final StringBuilder content = new StringBuilder();

    FileNode(String name) {
        super(name);
    }

    @Override
    boolean isDirectory() { return false; }

    void appendContent(String contentStr) {
        rwLock.writeLock().lock();
        try {
            content.append(contentStr);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    String getContent() {
        rwLock.readLock().lock();
        try {
            return content.toString();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}

class DirectoryNode extends FileSystemNode {
    private final Map<String, FileSystemNode> children = new ConcurrentHashMap<>();

    DirectoryNode(String name) {
        super(name);
    }

    @Override
    boolean isDirectory() { return true; }

    Map<String, FileSystemNode> getChildren() { return children; }

    FileSystemNode getChild(String name) {
        return children.get(name);
    }

    void addChild(FileSystemNode node) {
        children.put(node.getName(), node);
    }

    boolean removeChild(String name) {
        return children.remove(name) != null;
    }
}

// ========== FACADE + PATH WALK ==========

class InMemoryFileSystem {
    private final DirectoryNode root = new DirectoryNode("");

    private String[] parsePath(String path) {
        if (path == null || path.isEmpty() || path.equals("/")) {
            return new String[0];
        }
        return Arrays.stream(path.split("/"))
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
    }

    void mkdir(String path) {
        String[] parts = parsePath(path);
        DirectoryNode curr = root;

        for (String part : parts) {
            // FIX: lock parent, unlock parent — never unlock after reassigning curr
            DirectoryNode parent = curr;
            parent.getLock().writeLock().lock();
            try {
                FileSystemNode child = parent.getChild(part);
                if (child == null) {
                    DirectoryNode newDir = new DirectoryNode(part);
                    parent.addChild(newDir);
                    curr = newDir;
                } else if (child.isDirectory()) {
                    curr = (DirectoryNode) child;
                } else {
                    throw new IllegalArgumentException("Path conflict: " + part + " is a file.");
                }
            } finally {
                parent.getLock().writeLock().unlock();
            }
        }
    }

    List<String> ls(String path) {
        String[] parts = parsePath(path);
        FileSystemNode curr = root;

        for (String part : parts) {
            if (!curr.isDirectory()) {
                throw new IllegalArgumentException("Invalid path directory: " + part);
            }
            DirectoryNode dir = (DirectoryNode) curr;
            dir.getLock().readLock().lock();
            try {
                curr = dir.getChild(part);
            } finally {
                dir.getLock().readLock().unlock();
            }
            if (curr == null) {
                return Collections.emptyList();
            }
        }

        if (!curr.isDirectory()) {
            return Collections.singletonList(curr.getName());
        }

        DirectoryNode dir = (DirectoryNode) curr;
        dir.getLock().readLock().lock();
        try {
            List<String> result = new ArrayList<>(dir.getChildren().keySet());
            Collections.sort(result);
            return result;
        } finally {
            dir.getLock().readLock().unlock();
        }
    }

    void addContentToFile(String filePath, String content) {
        String[] parts = parsePath(filePath);
        if (parts.length == 0) {
            throw new IllegalArgumentException("File path cannot be root directory.");
        }

        DirectoryNode curr = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            DirectoryNode parent = curr;
            parent.getLock().writeLock().lock();
            try {
                FileSystemNode child = parent.getChild(part);
                if (child == null) {
                    DirectoryNode newDir = new DirectoryNode(part);
                    parent.addChild(newDir);
                    curr = newDir;
                } else if (child.isDirectory()) {
                    curr = (DirectoryNode) child;
                } else {
                    throw new IllegalArgumentException("Invalid path: " + part + " is a file.");
                }
            } finally {
                parent.getLock().writeLock().unlock();
            }
        }

        String fileName = parts[parts.length - 1];
        DirectoryNode parent = curr;
        parent.getLock().writeLock().lock();
        try {
            FileSystemNode node = parent.getChild(fileName);
            if (node == null) {
                FileNode newFile = new FileNode(fileName);
                newFile.appendContent(content);
                parent.addChild(newFile);
            } else if (!node.isDirectory()) {
                ((FileNode) node).appendContent(content);
            } else {
                throw new IllegalArgumentException("Cannot write file content to directory: " + fileName);
            }
        } finally {
            parent.getLock().writeLock().unlock();
        }
    }

    String readContentFromFile(String filePath) {
        String[] parts = parsePath(filePath);
        if (parts.length == 0) {
            throw new IllegalArgumentException("File path cannot be root directory.");
        }

        DirectoryNode curr = root;
        for (int i = 0; i < parts.length - 1; i++) {
            DirectoryNode parent = curr;
            parent.getLock().readLock().lock();
            FileSystemNode child;
            try {
                child = parent.getChild(parts[i]);
            } finally {
                parent.getLock().readLock().unlock();
            }
            if (child == null || !child.isDirectory()) {
                throw new IllegalArgumentException("File not found: " + filePath);
            }
            curr = (DirectoryNode) child;
        }

        String fileName = parts[parts.length - 1];
        curr.getLock().readLock().lock();
        FileSystemNode node;
        try {
            node = curr.getChild(fileName);
        } finally {
            curr.getLock().readLock().unlock();
        }

        if (node != null && !node.isDirectory()) {
            return ((FileNode) node).getContent();
        }
        throw new IllegalArgumentException("File not found: " + filePath);
    }

    /**
     * Removes file or directory from parent map.
     * Whole subtree becomes unreachable → GC (in-memory recursive delete).
     */
    boolean delete(String path) {
        String[] parts = parsePath(path);
        if (parts.length == 0) return false; // cannot delete root

        DirectoryNode curr = root;
        for (int i = 0; i < parts.length - 1; i++) {
            DirectoryNode parent = curr;
            parent.getLock().readLock().lock();
            FileSystemNode child;
            try {
                child = parent.getChild(parts[i]);
            } finally {
                parent.getLock().readLock().unlock();
            }
            if (child == null || !child.isDirectory()) return false;
            curr = (DirectoryNode) child;
        }

        String target = parts[parts.length - 1];
        curr.getLock().writeLock().lock();
        try {
            return curr.removeChild(target);
        } finally {
            curr.getLock().writeLock().unlock();
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        InMemoryFileSystem fs = new InMemoryFileSystem();

        System.out.println("=== TEST 1: MKDIR & LS ===");
        fs.mkdir("/a/b/c");
        System.out.println("ls('/') -> " + fs.ls("/"));         // [a]
        System.out.println("ls('/a/b') -> " + fs.ls("/a/b"));   // [c]

        System.out.println("\n=== TEST 2: FILE CREATION & APPEND ===");
        fs.addContentToFile("/a/b/c/file1.txt", "Hello");
        fs.addContentToFile("/a/b/c/file1.txt", " World!");
        System.out.println("Read file1.txt -> " + fs.readContentFromFile("/a/b/c/file1.txt"));

        System.out.println("\n=== TEST 3: LS ON FILE & DIRECTORY ===");
        fs.addContentToFile("/a/b/c/doc.pdf", "PDF Content");
        System.out.println("ls('/a/b/c') -> " + fs.ls("/a/b/c"));
        System.out.println("ls('/a/b/c/file1.txt') -> " + fs.ls("/a/b/c/file1.txt"));

        System.out.println("\n=== TEST 4: DELETE ===");
        fs.delete("/a/b/c/file1.txt");
        System.out.println("ls('/a/b/c') after delete -> " + fs.ls("/a/b/c"));
    }
}
