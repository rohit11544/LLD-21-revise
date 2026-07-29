import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Delayed Task Scheduler — ONE FILE
 * Patterns: Producer-Consumer + Command + Facade
 * Monotonic nanoTime, soft-cancel, non-drifting recurrence
 *
 *   javac Main.java && java Main
 */

// ========== DOMAIN ==========

abstract class Task implements Runnable {
    private final String description;

    Task(String description) {
        this.description = description;
    }

    String getDescription() { return description; }
}

class ScheduledTask implements Comparable<ScheduledTask> {
    private final String taskId;
    private final Task task;
    private final long scheduledExecutionNano;
    private final Long intervalNano;
    private volatile boolean cancelled = false;

    ScheduledTask(Task task, long delayMs, Long intervalMs) {
        this.taskId = UUID.randomUUID().toString();
        this.task = task;
        this.scheduledExecutionNano = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMs);
        this.intervalNano = (intervalMs != null) ? TimeUnit.MILLISECONDS.toNanos(intervalMs) : null;
    }

    private ScheduledTask(String taskId, Task task, long nextExecutionNano, Long intervalNano) {
        this.taskId = taskId;
        this.task = task;
        this.scheduledExecutionNano = nextExecutionNano;
        this.intervalNano = intervalNano;
    }

    String getTaskId() { return taskId; }
    Task getTask() { return task; }
    long getScheduledExecutionNano() { return scheduledExecutionNano; }
    boolean isRecurring() { return intervalNano != null; }
    boolean isCancelled() { return cancelled; }

    void cancel() { this.cancelled = true; }

    /** NO DRIFT: next = previousScheduled + interval (not now + interval). */
    ScheduledTask createNextRecurringTask() {
        return new ScheduledTask(taskId, task, scheduledExecutionNano + intervalNano, intervalNano);
    }

    @Override
    public int compareTo(ScheduledTask other) {
        return Long.compare(this.scheduledExecutionNano, other.scheduledExecutionNano);
    }
}

// ========== SCHEDULER ==========

class CustomTaskScheduler {
    private final PriorityQueue<ScheduledTask> taskQueue = new PriorityQueue<>();
    private final Map<String, ScheduledTask> taskMap = new ConcurrentHashMap<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition newEarliestTaskCondition = lock.newCondition();

    private final ExecutorService taskExecutionPool;
    private final Thread schedulerThread;
    private volatile boolean isRunning = true;

    CustomTaskScheduler(int workerPoolSize) {
        this.taskExecutionPool = Executors.newFixedThreadPool(workerPoolSize);
        this.schedulerThread = new Thread(this::runScheduler, "Scheduler-Daemon");
        this.schedulerThread.start();
    }

    String schedule(Task task, long delayMs) {
        return scheduleRecurring(task, delayMs, null);
    }

    String scheduleRecurring(Task task, long initialDelayMs, Long intervalMs) {
        ScheduledTask scheduledTask = new ScheduledTask(task, initialDelayMs, intervalMs);

        lock.lock();
        try {
            taskQueue.offer(scheduledTask);
            taskMap.put(scheduledTask.getTaskId(), scheduledTask);
            if (taskQueue.peek() == scheduledTask) {
                newEarliestTaskCondition.signal();
            }
            return scheduledTask.getTaskId();
        } finally {
            lock.unlock();
        }
    }

    boolean cancel(String taskId) {
        lock.lock();
        try {
            ScheduledTask task = taskMap.remove(taskId);
            if (task != null) {
                task.cancel();
                return true;
            }
            return false;
        } finally {
            lock.unlock();
        }
    }

    private void runScheduler() {
        while (isRunning) {
            lock.lock();
            try {
                while (isRunning && taskQueue.isEmpty()) {
                    newEarliestTaskCondition.await();
                }
                if (!isRunning) break;

                while (!taskQueue.isEmpty()) {
                    ScheduledTask current = taskQueue.peek();

                    if (current.isCancelled()) {
                        taskQueue.poll();
                        continue;
                    }

                    long sleepNano = current.getScheduledExecutionNano() - System.nanoTime();

                    if (sleepNano <= 0) {
                        taskQueue.poll();
                        submitWithExceptionShield(current.getTask());

                        if (current.isRecurring() && !current.isCancelled()) {
                            ScheduledTask next = current.createNextRecurringTask();
                            taskQueue.offer(next);
                            taskMap.put(next.getTaskId(), next);
                        } else {
                            taskMap.remove(current.getTaskId());
                        }
                    } else {
                        newEarliestTaskCondition.await(sleepNano, TimeUnit.NANOSECONDS);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                lock.unlock();
            }
        }
    }

    private void submitWithExceptionShield(Task task) {
        taskExecutionPool.submit(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                System.err.println("[Error] Task (" + task.getDescription() + "): " + t.getMessage());
            }
        });
    }

    void stop() {
        isRunning = false;
        lock.lock();
        try {
            newEarliestTaskCondition.signalAll();
        } finally {
            lock.unlock();
        }
        schedulerThread.interrupt();
        taskExecutionPool.shutdown();
        try {
            if (!taskExecutionPool.awaitTermination(3, TimeUnit.SECONDS)) {
                taskExecutionPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            taskExecutionPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        CustomTaskScheduler scheduler = new CustomTaskScheduler(3);

        System.out.println("=== TEST 1: DELAYED & CANCEL ===");

        Task task1 = new Task("Task1-OneOff") {
            @Override
            public void run() {
                System.out.println("-> Executing Task 1 (Delayed 1000ms)");
            }
        };

        Task task2 = new Task("Task2-Cancelled") {
            @Override
            public void run() {
                System.out.println("-> Executing Task 2 (Should never print)");
            }
        };

        scheduler.schedule(task1, 1000);
        String id2 = scheduler.schedule(task2, 1500);

        Thread.sleep(500);
        scheduler.cancel(id2);

        Thread.sleep(1200);
        scheduler.stop();
        System.out.println("=== DONE ===");
    }
}
