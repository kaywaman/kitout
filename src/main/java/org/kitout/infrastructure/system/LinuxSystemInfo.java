package org.kitout.infrastructure.system;

import org.kitout.application.port.SystemInfo;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalLong;

/** {@code /proc/meminfo} for memory, the file store for disk. */
@ApplicationScoped
public class LinuxSystemInfo implements SystemInfo {

    @Override
    public OptionalLong availableMemoryMb() {
        try {
            for (String line : Files.readAllLines(Path.of("/proc/meminfo"))) {
                if (line.startsWith("MemAvailable:")) {
                    String kb = line.replaceAll("[^0-9]", "");
                    return OptionalLong.of(Long.parseLong(kb) / 1024);
                }
            }
        } catch (IOException | NumberFormatException e) {
            // not Linux, or unreadable
        }
        return OptionalLong.empty();
    }

    @Override
    public OptionalLong freeDiskMb(Path path) {
        Path existing = path.toAbsolutePath();
        while (existing != null && !Files.exists(existing)) {
            existing = existing.getParent();
        }
        if (existing == null) {
            return OptionalLong.empty();
        }
        try {
            return OptionalLong.of(Files.getFileStore(existing).getUsableSpace() / (1024 * 1024));
        } catch (IOException e) {
            return OptionalLong.empty();
        }
    }
}
