package org.kitout.application.port;

import java.nio.file.Path;
import java.util.OptionalLong;

/** Memory and disk facts for the native build and clone checks. */
public interface SystemInfo {

    OptionalLong availableMemoryMb();

    OptionalLong freeDiskMb(Path path);
}
