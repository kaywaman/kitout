package org.kitout.infrastructure.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

/**
 * Shared mechanics for kitout's JSON files: an exclusive lock (in-process and across processes,
 * so the CLI and the daemon never overwrite each other) and atomic writes (write a temporary
 * file, then rename), so a crash leaves either the old or the new file, never half of one.
 */
final class JsonFileStore {

    private final Path file;
    private final Path lockFile;
    private final ObjectMapper json;
    private final ReentrantLock inProcess = new ReentrantLock();

    JsonFileStore(Path file, ObjectMapper json) {
        this.file = file;
        this.lockFile = file.resolveSibling(file.getFileName() + ".lock");
        this.json = json.copy().enable(SerializationFeature.INDENT_OUTPUT)
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    Path file() {
        return file;
    }

    ObjectMapper json() {
        return json;
    }

    @SuppressWarnings("try")   // the lock is held by the try block itself
    <T> T locked(Supplier<T> action) {
        inProcess.lock();
        try {
            Files.createDirectories(file.getParent());
            try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return action.get();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot lock " + file + ": " + e.getMessage(), e);
        } finally {
            inProcess.unlock();
        }
    }

    void writeAtomically(Object value) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            json.writeValue(tmp.toFile(), value);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write " + file + ": " + e.getMessage(), e);
        }
    }
}
