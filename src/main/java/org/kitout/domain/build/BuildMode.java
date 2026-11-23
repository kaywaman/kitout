package org.kitout.domain.build;

public enum BuildMode {
    JVM,
    /** Limited to one at a time by the native build queue. */
    NATIVE
}
