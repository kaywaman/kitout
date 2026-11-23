package org.kitout.domain.doctor;

/** Errors block a group start (with an override); warnings do not. */
public enum Severity {
    OK, INFO, WARNING, ERROR;

    public boolean blocksStart() {
        return this == ERROR;
    }
}
