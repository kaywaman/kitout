package org.kitout.application.run;

import org.kitout.domain.identity.Redactor;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Log files and live log streams.
 *
 * <p>A service writes straight into its log file (the process outlives kitout), so the live log
 * window follows that file. Files rotate at {@link #MAX_BYTES}, keeping {@link #KEEP} old files.
 * Every line leaving this class is redacted.
 */
@ApplicationScoped
public class LogService {

    static final long MAX_BYTES = 20L * 1024 * 1024;
    static final int KEEP = 3;
    private static final Duration POLL = Duration.ofMillis(250);

    /** Appends a marker line, e.g. "=== kitout: start (IMAGE) 2026-10-28T09:00Z ===", creating the file if needed. */
    public void mark(Path logFile, String message) {
        try {
            Files.createDirectories(logFile.getParent());
            Files.writeString(logFile, "=== kitout: " + message + " " + Instant.now() + " ===\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // logging must never stop a start
        }
    }

    /** Rotates before a start: {@code x.log -> x.log.1 -> x.log.2 ...}. */
    public void rotateIfLarge(Path logFile) {
        try {
            if (!Files.exists(logFile) || Files.size(logFile) < MAX_BYTES) {
                return;
            }
            for (int i = KEEP - 1; i >= 1; i--) {
                Path older = Path.of(logFile + "." + i);
                if (Files.exists(older)) {
                    Files.move(older, Path.of(logFile + "." + (i + 1)), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            // copy-truncate: a running process keeps appending to the same file descriptor
            Files.copy(logFile, Path.of(logFile + ".1"), StandardCopyOption.REPLACE_EXISTING);
            try (RandomAccessFile f = new RandomAccessFile(logFile.toFile(), "rw")) {
                f.setLength(0);
            }
        } catch (IOException e) {
            // a failed rotation only means a bigger file
        }
    }

    public List<String> tail(Path logFile, int lines) {
        Deque<String> last = new ArrayDeque<>();
        if (!Files.exists(logFile)) {
            return List.of();
        }
        try (var reader = Files.newBufferedReader(logFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (last.size() == lines) {
                    last.removeFirst();
                }
                last.addLast(Redactor.redact(line));
            }
        } catch (IOException e) {
            return List.of();
        }
        return new ArrayList<>(last);
    }

    /**
     * The last {@code initialLines} lines, then every new line as it is written. Survives
     * rotation (the file shrinks: start again from the top) and a file that does not exist yet.
     */
    public Multi<String> follow(Path logFile, int initialLines) {
        return Multi.createFrom().emitter(emitter -> Thread.ofVirtual().name("log-follow").start(() -> {
            tail(logFile, initialLines).forEach(emitter::emit);
            long offset = size(logFile);
            StringBuilder partial = new StringBuilder();
            while (!emitter.isCancelled()) {
                long size = size(logFile);
                if (size < offset) {
                    offset = 0;
                }
                if (size > offset) {
                    offset = readFrom(logFile, offset, partial, emitter::emit);
                }
                try {
                    Thread.sleep(POLL);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            emitter.complete();
        }));
    }

    private static long readFrom(Path file, long offset, StringBuilder partial, java.util.function.Consumer<String> sink) {
        try (RandomAccessFile f = new RandomAccessFile(file.toFile(), "r")) {
            f.seek(offset);
            byte[] buffer = new byte[(int) Math.min(f.length() - offset, 1024 * 1024)];
            int read = f.read(buffer);
            if (read <= 0) {
                return offset;
            }
            partial.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            int nl;
            while ((nl = partial.indexOf("\n")) >= 0) {
                sink.accept(Redactor.redact(partial.substring(0, nl)));
                partial.delete(0, nl + 1);
            }
            return offset + read;
        } catch (IOException e) {
            return offset;
        }
    }

    private static long size(Path file) {
        try {
            return Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException e) {
            return 0;
        }
    }
}
