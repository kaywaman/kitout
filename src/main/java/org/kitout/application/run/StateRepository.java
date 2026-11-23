package org.kitout.application.run;

import java.util.function.UnaryOperator;

/** Loads and saves {@link RunState} under a file lock shared by the daemon and the CLI. */
public interface StateRepository {

    RunState load();

    RunState update(UnaryOperator<RunState> change);
}
