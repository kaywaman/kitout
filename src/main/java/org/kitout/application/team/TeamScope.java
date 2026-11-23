package org.kitout.application.team;

import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.team.TeamSetup;

import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Which repositories kitout may load and clone. Without a team file: any. With one: only the
 * services it names, on the team's GitLab host, so everyone works with the same set and nothing
 * foreign is started by accident.
 */
public final class TeamScope {

    private TeamScope() {
    }

    public static boolean restricted(Optional<TeamSetup> team) {
        return team.isPresent();
    }

    /** A local project, by the identity of its git origin. A project without an origin is outside a team's scope. */
    public static boolean allows(Optional<TeamSetup> team, Optional<RepoIdentity> identity) {
        if (team.isEmpty()) {
            return true;
        }
        if (identity.isEmpty()) {
            return false;
        }
        String host = team.get().gitlab() == null ? "" : team.get().gitlab().host();
        boolean sameHost = host == null || host.isBlank() || host.equalsIgnoreCase(identity.get().host());
        return sameHost && serviceRepos(team.get()).contains(identity.get().fullPath().toLowerCase(Locale.ROOT));
    }

    /** A GitLab path to clone: a service or a repository with the team's compose files. */
    public static boolean allowsClone(Optional<TeamSetup> team, String fullPath) {
        return team.isEmpty() || team.get().repositories().stream().anyMatch(r -> r.equalsIgnoreCase(fullPath));
    }

    static Set<String> serviceRepos(TeamSetup team) {
        return team.services().stream().map(s -> s.repo().toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
    }
}
