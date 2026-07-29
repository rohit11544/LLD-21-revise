import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.DelayQueue;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Core Redis-Like KV Store — ONE FILE
 * Dual TTL eviction: passive (lazy get) + active (DelayQueue daemon)
 *
 *   javac Main.java && java Main
 */

// ========== INTERFACE + MODELS ==========

interface KeyValueStore<K, V> {
    V get(K key);
    void put(K key, V value);
    void put(K key, V value, long ttlMillis);
    boolean delete(K key);
}

class ValueNode<V> {
    private final V value;
    private final Long expiryTimestamp; // absolute ms; null = forever

    ValueNode(V value, Long ttlMillis) {
        this.value = value;
        this.expiryTimestamp = (ttlMillis != null && ttlMillis > 0)
                ? System.currentTimeMillis() + ttlMillis
                : null;
    }

    V getValue() { return value; }
    Long getExpiryTimestamp() { return expiryTimestamp; }

    boolean isExpired() {
        return expiryTimestamp != null && System.currentTimeMillis() >= expiryTimestamp;
    }
}

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

// ========== REDIS-LIKE STORE ==========

class RedisKeyValueStore<K, V> implements KeyValueStore<K, V> {
    private final Map<K, ValueNode<V>> store = new HashMap<>();
    private final DelayQueue<ExpiryEntry<K>> delayQueue = new DelayQueue<>();
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final ExecutorService cleanerService = Executors.newSingleThreadExecutor();
    private volatile boolean isRunning = true;

    RedisKeyValueStore() {
        cleanerService.submit(this::processActiveEviction);
    }

    @Override
    public V get(K key) {
        // Fast path under read lock
        rwLock.readLock().lock();
        try {
            ValueNode<V> node = store.get(key);
            if (node == null) return null;
            if (!node.isExpired()) return node.getValue();
        } finally {
            rwLock.readLock().unlock();
        }

        // Passive eviction under write lock (safe — no lock downgrade tricks)
        rwLock.writeLock().lock();
        try {
            ValueNode<V> node = store.get(key);
            if (node == null) return null;
            if (node.isExpired()) {
                store.remove(key);
                return null;
            }
            return node.getValue();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public void put(K key, V value) {
        put(key, value, -1);
    }

    @Override
    public void put(K key, V value, long ttlMillis) {
        rwLock.writeLock().lock();
        try {
            store.put(key, new ValueNode<>(value, ttlMillis > 0 ? ttlMillis : null));
            if (ttlMillis > 0) {
                delayQueue.put(new ExpiryEntry<>(key, ttlMillis));
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    @Override
    public boolean delete(K key) {
        rwLock.writeLock().lock();
        try {
            return store.remove(key) != null;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void processActiveEviction() {
        while (isRunning) {
            try {
                ExpiryEntry<K> entry = delayQueue.take(); // blocks until due
                rwLock.writeLock().lock();
                try {
                    ValueNode<V> node = store.get(entry.getKey());
                    // Only remove if STILL expired (key may have been overwritten with new TTL)
                    if (node != null && node.isExpired()) {
                        store.remove(entry.getKey());
                        System.out.println("[Active Eviction] Purged expired key: " + entry.getKey());
                    }
                } finally {
                    rwLock.writeLock().unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void shutdown() {
        isRunning = false;
        cleanerService.shutdownNow();
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        RedisKeyValueStore<String, String> redis = new RedisKeyValueStore<>();

        System.out.println("=== TEST 1: BASIC CRUD ===");
        redis.put("user:100", "Alice");
        System.out.println("Get user:100 -> " + redis.get("user:100"));

        redis.delete("user:100");
        System.out.println("Get deleted user:100 -> " + redis.get("user:100"));

        System.out.println("\n=== TEST 2: ACTIVE & PASSIVE TTL EVICTION ===");
        redis.put("session:key1", "ActiveSessionData", 1000);
        System.out.println("Immediate Get session:key1 -> " + redis.get("session:key1"));

        System.out.println("Sleeping 1.2 seconds to allow TTL expiration...");
        Thread.sleep(1200);

        System.out.println("Get session:key1 after 1.2s -> " + redis.get("session:key1"));

        redis.shutdown();
    }
}
