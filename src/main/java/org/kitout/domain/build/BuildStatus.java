package org.kitout.domain.build;

public enum BuildStatus {
    QUEUED, RUNNING, SUCCEEDED, FAILED, CANCELLED;

    public boolean isFinished() {
        return this == SUCCEEDED || this == FAILED || this == CANCELLED;
    }
}
