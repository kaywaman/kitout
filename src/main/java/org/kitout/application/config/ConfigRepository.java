package org.kitout.application.config;

import java.util.function.UnaryOperator;

/** Loads and saves {@link KitoutConfig}. The daemon and the CLI share the same file. */
public interface ConfigRepository {

    KitoutConfig load();

    /** Read-modify-write under a file lock, so the CLI and the daemon never lose each other's changes. */
    KitoutConfig update(UnaryOperator<KitoutConfig> change);
}
