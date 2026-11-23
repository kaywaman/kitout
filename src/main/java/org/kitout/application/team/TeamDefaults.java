package org.kitout.application.team;

import org.kitout.domain.team.TeamSetup;

import java.util.Optional;

/** The team setup in effect, if there is one. Services that layer team values under personal ones depend on this. */
@FunctionalInterface
public interface TeamDefaults {

    Optional<TeamSetup> current();

    TeamDefaults NONE = Optional::empty;
}
