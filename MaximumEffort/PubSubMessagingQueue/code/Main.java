import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Google L4 Sized Pub-Sub (Kafka Inspired) — ONE FILE
 * Patterns: Strategy + Facade + Producer-Consumer + Coordinator
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

class Message {
    private final String key;
    private final String payload;
    private final long offset;

    Message(String key, String payload, long offset) {
        this.key = key;
        this.payload = payload;
        this.offset = offset;
    }

    String getKey() { return key; }
    String getPayload() { return payload; }
    long getOffset() { return offset; }
}

// ========== STRATEGY ==========

interface PartitionStrategy {
    int getPartition(String key, int totalPartitions);
}

class HashPartitionStrategy implements PartitionStrategy {
    @Override
    public int getPartition(String key, int totalPartitions) {
        if (key == null) return 0;
        return Math.abs(key.hashCode()) % totalPartitions;
    }
}

// ========== PARTITION ==========

class Partition {
    private final int id;
    private final List<Message> messages = new ArrayList<>();
    private final AtomicLong nextOffset = new AtomicLong();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    Partition(int id) {
        this.id = id;
    }

    int getId() { return id; }

    void append(String key, String payload) {
        lock.writeLock().lock();
        try {
            messages.add(new Message(key, payload, nextOffset.getAndIncrement()));
        } finally {
            lock.writeLock().unlock();
        }
    }

    List<Message> read(long offset, int batchSize) {
        lock.readLock().lock();
        try {
            List<Message> ans = new ArrayList<>();
            for (Message m : messages) {
                if (m.getOffset() >= offset) {
                    ans.add(m);
                    if (ans.size() == batchSize) break;
                }
            }
            return ans;
        } finally {
            lock.readLock().unlock();
        }
    }
}

// ========== TOPIC ==========

class Topic {
    private final String name;
    private final List<Partition> partitions = new ArrayList<>();
    private final PartitionStrategy strategy;

    Topic(String name, int count, PartitionStrategy strategy) {
        this.name = name;
        this.strategy = strategy;
        for (int i = 0; i < count; i++) {
            partitions.add(new Partition(i));
        }
    }

    String getName() { return name; }

    List<Partition> getPartitions() { return partitions; }

    Partition getPartition(String key) {
        int idx = strategy.getPartition(key, partitions.size());
        return partitions.get(idx);
    }
}

// ========== BROKER ==========

class Broker {
    private final Map<String, Topic> topics = new ConcurrentHashMap<>();

    void createTopic(String name, int partitions) {
        topics.put(name, new Topic(name, partitions, new HashPartitionStrategy()));
    }

    void publish(String topic, String key, String payload) {
        Topic t = topics.get(topic);
        t.getPartition(key).append(key, payload);
    }

    List<Message> poll(String topic, int partition, long offset, int batchSize) {
        return topics.get(topic).getPartitions().get(partition).read(offset, batchSize);
    }

    Topic getTopic(String topic) {
        return topics.get(topic);
    }
}

// ========== PRODUCER ==========

class Producer {
    private final Broker broker;

    Producer(Broker broker) {
        this.broker = broker;
    }

    void send(String topic, String key, String payload) {
        broker.publish(topic, key, payload);
    }
}

// ========== CONSUMER ==========

class Consumer {
    private final String id;
    private final Set<Integer> assignedPartitions = ConcurrentHashMap.newKeySet();

    Consumer(String id) {
        this.id = id;
    }

    String getId() { return id; }

    void assign(int partition) {
        assignedPartitions.add(partition);
    }

    void clearAssignments() {
        assignedPartitions.clear();
    }

    void poll(Broker broker, ConsumerGroup group, int batchSize) {
        for (int partition : assignedPartitions) {
            long offset = group.getOffset(partition);
            List<Message> batch = broker.poll(group.getTopic(), partition, offset, batchSize);

            for (Message m : batch) {
                System.out.println(id + " processed : " + m.getPayload());
            }

            group.commitOffset(partition, offset + batch.size());
        }
    }
}

// ========== CONSUMER GROUP ==========

class ConsumerGroup {
    private final String topic;
    private final List<Consumer> consumers = new ArrayList<>();
    private final Map<Integer, AtomicLong> offsets = new ConcurrentHashMap<>();

    ConsumerGroup(String topic) {
        this.topic = topic;
    }

    String getTopic() { return topic; }

    void addConsumer(Consumer c) {
        consumers.add(c);
    }

    List<Consumer> getConsumers() { return consumers; }

    long getOffset(int partition) {
        return offsets.computeIfAbsent(partition, k -> new AtomicLong()).get();
    }

    void commitOffset(int partition, long offset) {
        offsets.computeIfAbsent(partition, k -> new AtomicLong()).set(offset);
    }
}

// ========== GROUP COORDINATOR ==========

class GroupCoordinator {
    void rebalance(ConsumerGroup group, Topic topic) {
        List<Consumer> consumers = group.getConsumers();
        for (Consumer c : consumers) {
            c.clearAssignments();
        }

        List<Partition> partitions = topic.getPartitions();
        for (int i = 0; i < partitions.size(); i++) {
            Consumer c = consumers.get(i % consumers.size());
            c.assign(partitions.get(i).getId());
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) {
        Broker broker = new Broker();
        broker.createTopic("orders", 2);

        Producer producer = new Producer(broker);
        producer.send("orders", "user1", "Order Created");
        producer.send("orders", "user2", "Order Paid");
        producer.send("orders", "user1", "Order Delivered");

        ConsumerGroup group = new ConsumerGroup("orders");
        Consumer c1 = new Consumer("Worker-1");
        Consumer c2 = new Consumer("Worker-2");
        group.addConsumer(c1);
        group.addConsumer(c2);

        new GroupCoordinator().rebalance(group, broker.getTopic("orders"));

        c1.poll(broker, group, 10);
        c2.poll(broker, group, 10);
    }
}
