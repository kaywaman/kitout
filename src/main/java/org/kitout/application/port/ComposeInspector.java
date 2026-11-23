package org.kitout.application.port;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Reads a compose file the way Compose itself does ({@code docker compose config}), with overrides and variables applied. */
public interface ComposeInspector {

    /**
     * The host ports each compose service publishes, by service name.
     *
     * @throws IllegalStateException with Compose's own reason when the file cannot be read
     */
    Map<String, List<Integer>> publishedPorts(Path composeFile);
}
