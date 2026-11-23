package org.kitout.domain.run;

/**
 * The five lifecycle states from the proposal.
 *
 * <pre>
 * STOPPED --start--> STARTING --health ok--> RUNNING --stop--> STOPPING --> STOPPED
 *                       |  timeout or exit        | crash
 *                       +-------> FAILED <---------+
 *                                   | start again --> STARTING
 * </pre>
 */
public enum ServiceState {
    STOPPED, STARTING, RUNNING, STOPPING, FAILED;

    public boolean isActive() {
        return this == STARTING || this == RUNNING || this == STOPPING;
    }
}
