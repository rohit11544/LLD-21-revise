import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;

/**
 * Concurrent Logger Framework — ONE FILE
 * Patterns: Facade/Singleton + CoR (levels) + Strategy (formatter/sink) + Producer-Consumer (async)
 *
 *   javac Main.java && java Main
 */

// ========== LEVELS + MESSAGE ==========

enum LogLevel {
    DEBUG(1),
    INFO(2),
    WARN(3),
    ERROR(4),
    FATAL(5);

    private final int level;
    LogLevel(int level) { this.level = level; }
    int getLevel() { return level; }
}

class LogMessage {
    private final LogLevel level;
    private final String message;
    private final String threadName;
    private final long timestamp;

    LogMessage(LogLevel level, String message) {
        this.level = level;
        this.message = message;
        this.threadName = Thread.currentThread().getName();
        this.timestamp = System.currentTimeMillis();
    }

    LogLevel getLevel() { return level; }
    String getMessage() { return message; }
    String getThreadName() { return threadName; }
    long getTimestamp() { return timestamp; }
}

// ========== FORMATTER STRATEGY ==========

interface LogFormatter {
    String format(LogMessage logMessage);
}

class PlainTextFormatter implements LogFormatter {
    @Override
    public String format(LogMessage logMessage) {
        return String.format("[%s] [%s] [%s] - %s",
                Instant.ofEpochMilli(logMessage.getTimestamp()),
                logMessage.getThreadName(),
                logMessage.getLevel(),
                logMessage.getMessage());
    }
}

// ========== SINK STRATEGY ==========

interface LogSink {
    void write(LogMessage message);
    void close();
}

class ConsoleSink implements LogSink {
    private final LogFormatter formatter;

    ConsoleSink(LogFormatter formatter) {
        this.formatter = formatter;
    }

    @Override
    public void write(LogMessage message) {
        System.out.println(formatter.format(message));
    }

    @Override
    public void close() {}
}

class FileSink implements LogSink {
    private final LogFormatter formatter;
    private PrintWriter printWriter;

    FileSink(String filePath, LogFormatter formatter) {
        this.formatter = formatter;
        try {
            this.printWriter = new PrintWriter(new FileWriter(filePath, true));
        } catch (IOException e) {
            System.err.println("Failed to initialize FileSink: " + e.getMessage());
        }
    }

    @Override
    public synchronized void write(LogMessage message) {
        if (printWriter != null) {
            printWriter.println(formatter.format(message));
            printWriter.flush();
        }
    }

    @Override
    public synchronized void close() {
        if (printWriter != null) printWriter.close();
    }
}

// ========== CHAIN OF RESPONSIBILITY ==========
// Exact-level handlers: INFO handler logs INFO only; ERROR handler logs ERROR only.
// Avoids double-dispatch into the same sink manager.

abstract class AbstractLoggerHandler {
    protected final LogLevel level;
    protected AbstractLoggerHandler nextHandler;

    AbstractLoggerHandler(LogLevel level) {
        this.level = level;
    }

    void setNextHandler(AbstractLoggerHandler nextHandler) {
        this.nextHandler = nextHandler;
    }

    void logMessage(LogLevel messageLevel, LogMessage message, AsyncLogSinkManager sinkManager) {
        if (this.level == messageLevel) {
            write(message, sinkManager);
        }
        if (nextHandler != null) {
            nextHandler.logMessage(messageLevel, message, sinkManager);
        }
    }

    protected abstract void write(LogMessage message, AsyncLogSinkManager sinkManager);
}

class InfoLoggerHandler extends AbstractLoggerHandler {
    InfoLoggerHandler() { super(LogLevel.INFO); }

    @Override
    protected void write(LogMessage message, AsyncLogSinkManager sinkManager) {
        sinkManager.dispatch(message);
    }
}

class ErrorLoggerHandler extends AbstractLoggerHandler {
    ErrorLoggerHandler() { super(LogLevel.ERROR); }

    @Override
    protected void write(LogMessage message, AsyncLogSinkManager sinkManager) {
        // Could also fan-out to alert sinks / metrics here
        sinkManager.dispatch(message);
    }
}

// ========== ASYNC SINK MANAGER ==========

class AsyncLogSinkManager {
    private final List<LogSink> sinks = new CopyOnWriteArrayList<>();
    private final BlockingQueue<LogMessage> queue = new LinkedBlockingQueue<>(10_000);
    private final ExecutorService workerPool = Executors.newSingleThreadExecutor();
    private volatile boolean isRunning = true;

    AsyncLogSinkManager() {
        workerPool.submit(this::processQueue);
    }

    void addSink(LogSink sink) {
        sinks.add(sink);
    }

    void dispatch(LogMessage message) {
        if (!queue.offer(message)) {
            System.err.println("[LogManager] Buffer full! Dropping: " + message.getMessage());
        }
    }

    private void processQueue() {
        while (isRunning || !queue.isEmpty()) {
            try {
                LogMessage msg = queue.poll(100, TimeUnit.MILLISECONDS);
                if (msg != null) {
                    for (LogSink sink : sinks) {
                        sink.write(msg);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    void shutdown() {
        isRunning = false;
        workerPool.shutdown();
        try {
            if (!workerPool.awaitTermination(3, TimeUnit.SECONDS)) {
                workerPool.shutdownNow();
            }
        } catch (InterruptedException e) {
            workerPool.shutdownNow();
            Thread.currentThread().interrupt();
        }
        for (LogSink sink : sinks) sink.close();
    }
}

// ========== LOGGER FACADE (SINGLETON) ==========

class Logger {
    private static volatile Logger instance;
    private final AbstractLoggerHandler chain;
    private final AsyncLogSinkManager sinkManager;

    private Logger() {
        this.sinkManager = new AsyncLogSinkManager();
        AbstractLoggerHandler infoHandler = new InfoLoggerHandler();
        AbstractLoggerHandler errorHandler = new ErrorLoggerHandler();
        infoHandler.setNextHandler(errorHandler);
        this.chain = infoHandler;
    }

    static Logger getInstance() {
        if (instance == null) {
            synchronized (Logger.class) {
                if (instance == null) {
                    instance = new Logger();
                }
            }
        }
        return instance;
    }

    void addSink(LogSink sink) {
        sinkManager.addSink(sink);
    }

    void info(String message) { log(LogLevel.INFO, message); }
    void error(String message) { log(LogLevel.ERROR, message); }

    private void log(LogLevel level, String message) {
        chain.logMessage(level, new LogMessage(level, message), sinkManager);
    }

    void shutdown() {
        sinkManager.shutdown();
    }
}

// ========== DRIVER ==========

public class Main {
    public static void main(String[] args) throws InterruptedException {
        Logger logger = Logger.getInstance();
        logger.addSink(new ConsoleSink(new PlainTextFormatter()));

        System.out.println("=== TEST 1: CONCURRENT ASYNC LOGGING ===");

        ExecutorService clientThreads = Executors.newFixedThreadPool(3);
        for (int i = 1; i <= 3; i++) {
            final int threadId = i;
            clientThreads.submit(() -> {
                logger.info("Worker thread " + threadId + " initialized.");
                if (threadId == 2) {
                    logger.error("Worker thread " + threadId + " encountered an unexpected error!");
                }
            });
        }

        clientThreads.shutdown();
        clientThreads.awaitTermination(2, TimeUnit.SECONDS);
        Thread.sleep(500); // let async worker flush
        logger.shutdown();
    }
}
