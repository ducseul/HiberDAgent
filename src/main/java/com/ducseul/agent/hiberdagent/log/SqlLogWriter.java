package com.ducseul.agent.hiberdagent.log;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe SQL log writer that supports writing to console or file.
 *
 * Configuration via system property:
 *   -Dhibernate.agent.stdout=console     (default - writes to System.out)
 *   -Dhibernate.agent.stdout=D:\Temp\log.sql  (writes to specified file)
 */
public final class SqlLogWriter {

    private static final String PROPERTY_KEY = "hibernate.agent.stdout";
    private static final String CONSOLE_MODE = "console";
    private static final long LOCK_TIMEOUT_MS = 5000; // 5 second timeout for lock

    private static final SqlLogWriter INSTANCE = new SqlLogWriter();

    private final ReentrantLock lock = new ReentrantLock();
    private final boolean fileMode;
    private final String filePath;
    private volatile boolean disabled = false; // Emergency disable flag

    // Diagnostics
    private final AtomicLong writeCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong lastWriteTime = new AtomicLong(System.currentTimeMillis());

    private SqlLogWriter() {
        String value = System.getProperty(PROPERTY_KEY, CONSOLE_MODE).trim();
        if (value.isEmpty() || CONSOLE_MODE.equalsIgnoreCase(value)) {
            this.fileMode = false;
            this.filePath = null;
        } else {
            this.fileMode = true;
            this.filePath = value;
        }
    }

    public static SqlLogWriter getInstance() {
        return INSTANCE;
    }

    /**
     * Returns true if logging to file, false if logging to console.
     */
    public boolean isFileMode() {
        return fileMode;
    }

    /**
     * Returns the configured file path, or null if console mode.
     */
    public String getFilePath() {
        return filePath;
    }

    /**
     * Writes a log line. Thread-safe for both console and file modes.
     * Each call writes a complete line (includes newline).
     * This method will never throw or block forever.
     */
    public void writeLine(String message) {
        if (disabled) {
            // Emergency fallback - just print to console
            safeConsole(message);
            return;
        }

        try {
            if (fileMode) {
                writeToFile(message);
            } else {
                writeToConsole(message);
            }
            writeCount.incrementAndGet();
            lastWriteTime.set(System.currentTimeMillis());
        } catch (Throwable t) {
            handleWriteError("writeLine", t, message);
        }
    }

    /**
     * Logs an error that occurred in the agent. Always writes to stderr and optionally to file.
     * Use this to report agent internal errors.
     */
    public void writeError(String message, Throwable error) {
        errorCount.incrementAndGet();

        try {
            String errorLine = "[HiberDAgent] ERROR: " + message;
            if (error != null) {
                errorLine += " - " + error.getClass().getName() + ": " + error.getMessage();
            }

            // Always write to stderr for visibility
            safeStderr(errorLine);
            if (error != null) {
                safePrintStackTrace(error);
            }

            // Also write to log file if in file mode (but don't block)
            if (fileMode && !disabled) {
                tryWriteToFile(errorLine);
                if (error != null) {
                    tryWriteToFile(getStackTraceString(error));
                }
            }
        } catch (Throwable t) {
            // Absolute last resort
            safeStderr("[HiberDAgent] CRITICAL: Failed to log error: " + t.getMessage());
        }
    }

    private void writeToConsole(String message) {
        System.out.println(message);
    }

    /**
     * Write to file using RandomAccessFile + FileChannel with force(true).
     * force(true) syncs both data AND metadata (modification time) to disk,
     * which is required for IntelliJ's file watcher to detect changes on Windows.
     */
    private void writeToFile(String message) {
        if (disabled) {
            safeConsole(message);
            return;
        }

        boolean lockAcquired = false;
        try {
            // Use tryLock with timeout to prevent deadlocks
            lockAcquired = lock.tryLock(LOCK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            if (!lockAcquired) {
                // Lock timeout - possible deadlock, fall back to console
                safeStderr("[HiberDAgent] WARNING: Lock timeout, falling back to console");
                safeConsole(message);
                return;
            }

            // Use RandomAccessFile for append with FileChannel.force(true) for metadata sync
            try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw");
                 FileChannel channel = raf.getChannel()) {
                // Seek to end for append
                channel.position(channel.size());

                // Write data
                String line = message + System.lineSeparator();
                ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                channel.write(buffer);

                // force(true) = sync data AND metadata (modification time) to disk
                channel.force(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            safeConsole(message);
        } catch (IOException e) {
            handleIOError(e, message);
        } catch (Throwable t) {
            handleWriteError("writeToFile", t, message);
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    /**
     * Try to write to file without blocking - used for error logging.
     */
    private void tryWriteToFile(String message) {
        if (disabled) {
            return;
        }

        boolean lockAcquired = false;
        try {
            lockAcquired = lock.tryLock(100, TimeUnit.MILLISECONDS); // Short timeout for errors
            if (!lockAcquired) {
                return; // Don't block for error logging
            }

            try (RandomAccessFile raf = new RandomAccessFile(filePath, "rw");
                 FileChannel channel = raf.getChannel()) {
                channel.position(channel.size());
                String line = message + System.lineSeparator();
                ByteBuffer buffer = ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8));
                channel.write(buffer);
                channel.force(true);
            }
        } catch (Throwable ignored) {
            // Already logged to stderr, don't recurse
        } finally {
            if (lockAcquired) {
                lock.unlock();
            }
        }
    }

    private void handleIOError(IOException e, String message) {
        long errors = errorCount.incrementAndGet();
        safeStderr("[HiberDAgent] IO Error writing to log file: " + e.getMessage());

        // After 10 consecutive IO errors, disable file logging
        if (errors > 10) {
            safeStderr("[HiberDAgent] Too many errors, disabling file logging");
            disabled = true;
        }

        safeConsole(message);
    }

    private void handleWriteError(String context, Throwable t, String message) {
        long errors = errorCount.incrementAndGet();
        safeStderr("[HiberDAgent] CRITICAL: Error in " + context + ": " + t.getClass().getName() + ": " + t.getMessage());
        safePrintStackTrace(t);

        // After 10 errors, disable file logging to prevent cascading failures
        if (errors > 10) {
            safeStderr("[HiberDAgent] Too many errors (" + errors + "), disabling file logging permanently");
            disabled = true;
        }

        safeConsole(message);
    }

    private void safeConsole(String message) {
        try {
            System.out.println(message);
        } catch (Throwable ignored) {
        }
    }

    private void safeStderr(String message) {
        try {
            System.err.println(message);
            System.err.flush();
        } catch (Throwable ignored) {
        }
    }

    private void safePrintStackTrace(Throwable t) {
        try {
            t.printStackTrace(System.err);
            System.err.flush();
        } catch (Throwable ignored) {
        }
    }

    private String getStackTraceString(Throwable t) {
        StringBuilder sb = new StringBuilder();
        sb.append("  ").append(t.getClass().getName()).append(": ").append(t.getMessage());
        StackTraceElement[] stack = t.getStackTrace();
        int limit = Math.min(stack.length, 30); // Limit stack trace length
        for (int i = 0; i < limit; i++) {
            sb.append(System.lineSeparator()).append("    at ").append(stack[i].toString());
        }
        if (stack.length > limit) {
            sb.append(System.lineSeparator()).append("    ... ").append(stack.length - limit).append(" more");
        }
        Throwable cause = t.getCause();
        if (cause != null && cause != t) {
            sb.append(System.lineSeparator()).append("  Caused by: ").append(cause.getClass().getName())
              .append(": ").append(cause.getMessage());
        }
        return sb.toString();
    }

    /**
     * Returns diagnostic info about the writer state.
     */
    public String getDiagnostics() {
        long lastWrite = lastWriteTime.get();
        long sinceLastWrite = System.currentTimeMillis() - lastWrite;
        return String.format("SqlLogWriter[mode=%s, writes=%d, errors=%d, lastWrite=%dms ago, disabled=%s]",
            fileMode ? "file" : "console",
            writeCount.get(),
            errorCount.get(),
            sinceLastWrite,
            disabled);
    }
}
