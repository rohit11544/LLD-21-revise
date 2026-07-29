import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Transactional KV Store + TTL — ONE FILE (interview style)
 *
 *   javac Main.java && java Main
 *
 * Patterns: Facade / Unit-of-Work (txn stack), RWLock atomic commit, DelayQueue TTL
 */

// ========== INTERFACE & MODELS ==========

interface KeyValueStore<K, V> {
    V get(K key);
    void put(K key, V value, Long ttlMillis);
    void remove(K key);
}

class ValueNode<V> {
    private final V value;
    private final Long expiryTimestamp; // absolute ms, or null = forever

    ValueNode(V value, Long ttlMillis) {
        this.value = value;
        this.expiryTimestamp = (ttlMillis != null)
                ? System.currentTimeMillis() + ttlMillis
                : null;
    }

    V getValue() { return value; }
    Long getExpiryTimestamp() { return expiryTimestamp; }

    boolean isExpired() {
        return expiryTimestamp != null && System.currentTimeMillis() >= expiryTimestamp;
    }
}

/** DelayQueue entry — wakes cleaner when this key's TTL is due (O(log N)). */
class ExpiryEntry<K> implements Delayed {
    private final K key;
    private final long expiryTimeMs;

    ExpiryEntry(K key, long ttlMillis) {
        this.key = key;
        this.expiryTimeMs = System.currentTimeMillis() + ttlMillis;
    }

    K getKey() { return key; }

    @Override
    public long getDelay(TimeUnit unit) {
        return unit.convert(expiryTimeMs - System.currentTimeMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public int compareTo(Delayed other) {
        return Long.compare(this.expiryTimeMs, ((ExpiryEntry<?>) other).expiryTimeMs);
    }
}

// ========== GLOBAL ENGINE ==========

class InMemoryKeyValueStore<K, V> implements KeyValueStore<K, V> {
    private final Map<K, ValueNode<V>> store = new HashMap<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final DelayQueue<ExpiryEntry<K>> delayQueue = new DelayQueue<>();
    private final ExecutorService cleaner = Executors.newSingleThreadExecutor();

    InMemoryKeyValueStore() {
        cleaner.submit(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    ExpiryEntry<K> entry = delayQueue.take(); // blocks until due
                    expireIfStillDue(entry.getKey());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** Only delete if the current value is still expired (handles key overwrite with new TTL). */
    private void expireIfStillDue(K key) {
        rwLock.writeLock().lock();
        try {
            ValueNode<V> node = store.get(key);
            if (node != null && node.isExpired()) {
                store.remove(key);
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public V get(K key) {
        rwLock.readLock().lock();
        try {
            ValueNode<V> node = store.get(key);
            if (node == null || node.isExpired()) return null;
            return node.getValue();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    @Override
    public void put(K key, V value, Long ttlMillis) {
        rwLock.writeLock().lock();
        try {
            store.put(key, new ValueNode<>(value, ttlMillis));
            if (ttlMillis != null) {
                delayQueue.put(new ExpiryEntry<>(key, ttlMillis));
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void remove(K key) {
        rwLock.writeLock().lock();
        try {
            store.remove(key);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    ReentrantReadWriteLock getLock() { return rwLock; }

    void shutdown() { cleaner.shutdownNow(); }
}

// ========== TRANSACTION MANAGER ==========

class TransactionManager<K, V> implements KeyValueStore<K, V> {
    private static final Object TOMBSTONE = new Object();

    private final InMemoryKeyValueStore<K, V> globalEngine;
    private final ThreadLocal<Deque<Map<K, Object>>> txnStack =
            ThreadLocal.withInitial(ArrayDeque::new);

    TransactionManager(InMemoryKeyValueStore<K, V> globalEngine) {
        this.globalEngine = globalEngine;
    }

    boolean isInTransaction() {
        return !txnStack.get().isEmpty();
    }

    void begin() {
        txnStack.get().push(new HashMap<>());
        System.out.println("[Txn] begin — nesting=" + txnStack.get().size());
    }

    @Override
    public V get(K key) {
        if (!isInTransaction()) return globalEngine.get(key);

        // Top → bottom (ArrayDeque iterates head-first; push adds at head)
        for (Map<K, Object> layer : txnStack.get()) {
            if (!layer.containsKey(key)) continue;
            Object val = layer.get(key);
            if (val == TOMBSTONE) return null;
            @SuppressWarnings("unchecked")
            ValueNode<V> node = (ValueNode<V>) val;
            return node.isExpired() ? null : node.getValue();
        }
        return globalEngine.get(key);
    }

    @Override
    public void put(K key, V value, Long ttlMillis) {
        if (!isInTransaction()) {
            globalEngine.put(key, value, ttlMillis);
            return;
        }
        txnStack.get().peek().put(key, new ValueNode<>(value, ttlMillis));
    }

    @Override
    public void remove(K key) {
        if (!isInTransaction()) {
            globalEngine.remove(key);
            return;
        }
        txnStack.get().peek().put(key, TOMBSTONE);
    }

    void commit() {
        if (!isInTransaction()) throw new IllegalStateException("No active transaction");

        Map<K, Object> current = txnStack.get().pop();

        if (!txnStack.get().isEmpty()) {
            // Nested: merge into parent layer only
            txnStack.get().peek().putAll(current);
            System.out.println("[Txn] committed into parent layer");
            return;
        }

        // Root: one atomic write pass under global write lock
        ReentrantReadWriteLock.WriteLock writeLock = globalEngine.getLock().writeLock();
        writeLock.lock();
        try {
            for (Map.Entry<K, Object> e : current.entrySet()) {
                K key = e.getKey();
                Object val = e.getValue();
                if (val == TOMBSTONE) {
                    globalEngine.remove(key); // reentrant write lock OK
                } else {
                    @SuppressWarnings("unchecked")
                    ValueNode<V> node = (ValueNode<V>) val;
                    if (!node.isExpired()) {
                        Long remaining = null;
                        if (node.getExpiryTimestamp() != null) {
                            remaining = Math.max(0, node.getExpiryTimestamp() - System.currentTimeMillis());
                        }
                        globalEngine.put(key, node.getValue(), remaining);
                    }
                }
            }
            System.out.println("[Txn] committed ATOMICALLY to global store");
        } finally {
            writeLock.unlock();
        }
    }

    void rollback() {
        if (!isInTransaction()) throw new IllegalStateException("No active transaction");
        txnStack.get().pop();
        System.out.println("[Txn] rolled back");
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        InMemoryKeyValueStore<String, String> engine = new InMemoryKeyValueStore<>();
        TransactionManager<String, String> store = new TransactionManager<>(engine);

        System.out.println("=== TEST 1: TTL (DelayQueue + lazy get) ===");
        store.put("user1", "SwiggyEngineer", 1500L);
        System.out.println("Immediate Get: " + store.get("user1"));
        Thread.sleep(1600);
        System.out.println("Get After Expiry: " + store.get("user1"));

        System.out.println("\n=== TEST 2: Nested txn + tombstone ===");
        store.put("key1", "InitialVal", null);

        store.begin(); // Txn 1
        store.put("key1", "Txn1Val", null);

        store.begin(); // Txn 2
        store.remove("key1");
        System.out.println("Inside Txn2 (deleted): " + store.get("key1"));

        store.rollback(); // undo Txn 2
        System.out.println("After rollback Txn2: " + store.get("key1"));

        store.commit(); // root
        System.out.println("Global after root commit: " + store.get("key1"));

        engine.shutdown();
    }
}
