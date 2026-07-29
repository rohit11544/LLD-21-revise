import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * In-Memory Cache (LRU / LFU) — ONE FILE (interview style)
 * Patterns: Facade + Strategy (eviction)
 *
 *   javac Main.java && java Main
 */

// ========== DLL + NODE ==========

class Node<K, V> {
    K key;
    V value;
    int frequency;
    Node<K, V> prev;
    Node<K, V> next;

    Node(K key, V value) {
        this.key = key;
        this.value = value;
        this.frequency = 1;
    }
}

/** Dummy head/tail → O(1) addFirst / removeLast with no null edge cases. */
class DoublyLinkedList<K, V> {
    private final Node<K, V> head;
    private final Node<K, V> tail;
    private int size;

    DoublyLinkedList() {
        head = new Node<>(null, null);
        tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
        size = 0;
    }

    void addFirst(Node<K, V> node) {
        node.next = head.next;
        node.prev = head;
        head.next.prev = node;
        head.next = node;
        size++;
    }

    void remove(Node<K, V> node) {
        node.prev.next = node.next;
        node.next.prev = node.prev;
        size--;
    }

    Node<K, V> removeLast() {
        if (size == 0) return null;
        Node<K, V> lastNode = tail.prev;
        remove(lastNode);
        return lastNode;
    }

    boolean isEmpty() {
        return size == 0;
    }
}

// ========== EVICTION STRATEGY ==========

interface EvictionStrategy<K, V> {
    void keyAccessed(Node<K, V> node);
    void keyAdded(Node<K, V> node);
    Node<K, V> evictKey();
}

/** LRU: HashMap (in Cache) + one DLL. Most recent at head; evict tail. */
class LRUEvictionStrategy<K, V> implements EvictionStrategy<K, V> {
    private final DoublyLinkedList<K, V> list = new DoublyLinkedList<>();

    @Override
    public void keyAccessed(Node<K, V> node) {
        list.remove(node);
        list.addFirst(node);
    }

    @Override
    public void keyAdded(Node<K, V> node) {
        list.addFirst(node);
    }

    @Override
    public Node<K, V> evictKey() {
        return list.removeLast();
    }
}

/**
 * LFU: freq → DLL buckets + minFrequency.
 * Same-freq tie-break = LRU (addFirst on touch, removeLast on evict).
 */
class LFUEvictionStrategy<K, V> implements EvictionStrategy<K, V> {
    private final Map<Integer, DoublyLinkedList<K, V>> freqMap = new HashMap<>();
    private int minFrequency = 0;

    @Override
    public void keyAccessed(Node<K, V> node) {
        int currentFreq = node.frequency;
        DoublyLinkedList<K, V> oldList = freqMap.get(currentFreq);
        oldList.remove(node);

        if (oldList.isEmpty() && currentFreq == minFrequency) {
            minFrequency++;
        }

        node.frequency++;
        freqMap.computeIfAbsent(node.frequency, k -> new DoublyLinkedList<>()).addFirst(node);
    }

    @Override
    public void keyAdded(Node<K, V> node) {
        node.frequency = 1;
        minFrequency = 1; // new key always lands at freq 1 → min is at most 1
        freqMap.computeIfAbsent(1, k -> new DoublyLinkedList<>()).addFirst(node);
    }

    @Override
    public Node<K, V> evictKey() {
        DoublyLinkedList<K, V> minFreqList = freqMap.get(minFrequency);
        if (minFreqList == null || minFreqList.isEmpty()) {
            return null;
        }
        // After this, put() always calls keyAdded which resets minFrequency = 1
        return minFreqList.removeLast();
    }
}

// ========== CACHE FACADE ==========

class Cache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> nodeMap = new HashMap<>();
    private final EvictionStrategy<K, V> evictionStrategy;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();

    Cache(int capacity, EvictionStrategy<K, V> evictionStrategy) {
        this.capacity = capacity;
        this.evictionStrategy = evictionStrategy;
    }

    /**
     * Write lock on get: both LRU and LFU mutate structure on access.
     * (Plain ReentrantLock is equally honest — RWLock barely helps here.)
     */
    V get(K key) {
        rwLock.writeLock().lock();
        try {
            Node<K, V> node = nodeMap.get(key);
            if (node == null) {
                return null;
            }
            evictionStrategy.keyAccessed(node);
            return node.value;
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    void put(K key, V value) {
        rwLock.writeLock().lock();
        try {
            if (capacity <= 0) return;

            Node<K, V> existingNode = nodeMap.get(key);
            if (existingNode != null) {
                existingNode.value = value;
                evictionStrategy.keyAccessed(existingNode);
                return;
            }

            if (nodeMap.size() >= capacity) {
                Node<K, V> evicted = evictionStrategy.evictKey();
                if (evicted != null) {
                    nodeMap.remove(evicted.key);
                    System.out.println("[Eviction] Capacity reached. Evicted Key: " + evicted.key);
                }
            }

            Node<K, V> newNode = new Node<>(key, value);
            nodeMap.put(key, newNode);
            evictionStrategy.keyAdded(newNode);
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    int size() {
        rwLock.readLock().lock();
        try {
            return nodeMap.size();
        } finally {
            rwLock.readLock().unlock();
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        System.out.println("=== TEST 1: LRU CACHE (Capacity = 2) ===");
        Cache<String, String> lruCache = new Cache<>(2, new LRUEvictionStrategy<>());

        lruCache.put("K1", "V1");
        lruCache.put("K2", "V2");
        System.out.println("Get K1 -> " + lruCache.get("K1")); // K1 most recent; K2 is LRU

        lruCache.put("K3", "V3"); // Evicts K2
        System.out.println("Get K2 (Evicted) -> " + lruCache.get("K2")); // null
        System.out.println("Get K3 -> " + lruCache.get("K3"));           // V3

        System.out.println("\n=== TEST 2: LFU CACHE (Capacity = 2) ===");
        Cache<String, String> lfuCache = new Cache<>(2, new LFUEvictionStrategy<>());

        lfuCache.put("K1", "V1"); // freq 1
        lfuCache.put("K2", "V2"); // freq 1

        lfuCache.get("K1"); // K1 → freq 2
        lfuCache.get("K1"); // K1 → freq 3
        lfuCache.get("K2"); // K2 → freq 2

        // Evict from minFrequency=2 → K2 (colder than K1 at freq 3)
        lfuCache.put("K3", "V3");
        System.out.println("Get K2 (Evicted) -> " + lfuCache.get("K2")); // null
        System.out.println("Get K1 (Retained) -> " + lfuCache.get("K1")); // V1
        System.out.println("Get K3 -> " + lfuCache.get("K3"));             // V3
    }
}
