package org.kitout.application.team;

import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.team.TeamSetup;
import org.kitout.testsupport.TeamFixtures;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetupTest {

    private final Optional<TeamSetup> team = Optional.of(TeamFixtures.filled());

    @Test
    void onlyTheTeamsRepositoriesMayBeLoadedOrCloned() {
        assertTrue(TeamScope.allows(team, Optional.of(new RepoIdentity("gitlab.example.com", "acme/shop", "orders"))));
        assertTrue(TeamScope.allows(team, Optional.of(new RepoIdentity("GITLAB.example.com", "Acme/Shop", "Orders"))));
        assertFalse(TeamScope.allows(team, Optional.of(new RepoIdentity("gitlab.example.com", "me", "playground"))));
        assertFalse(TeamScope.allows(team, Optional.of(new RepoIdentity("github.com", "acme/shop", "orders"))), "same path, other host");
        assertFalse(TeamScope.allows(team, Optional.empty()), "no git origin: not the team's");
        assertFalse(TeamScope.allows(team, Optional.of(new RepoIdentity("gitlab.example.com", "acme/platform", "infrastructure"))),
                "the compose repository is cloned, but it is not a service");
        assertTrue(TeamScope.allowsClone(team, "acme/platform/infrastructure"));
        assertFalse(TeamScope.allowsClone(team, "acme/shop/legacy"));
        assertTrue(TeamScope.allows(Optional.empty(), Optional.empty()), "without a team file, anything");
    }

    @Test
    void everyProblemLandsInTheCheckItBelongsTo() {
        TeamService.Status status = new TeamService.Status(true, "x", Optional.empty(), List.of(
                "team still has the placeholder <<REPLACE: team name>>",
                "variables.GITLAB_TOKEN looks like a credential. Secrets never go into the team file",
                "The team file needs kitout 9.0.0 or newer; this is 1.0.0. Update kitout",
                "services[1].repo acme/shop/orders is listed twice: one repository is one service",
                "groups[0].members: acme/x is not in services",
                "requirements.checks[9].id python is unknown",
                "Not valid JSON at line 3, column 1: oops",
                "Could not read the team file from x: 404"), List.of(), "", Instant.now());
        List<SetupService.FileCheck> checks = SetupService.fileChecks(status);
        assertEquals(10, checks.size());
        for (String id : List.of("read", "format", "version", "placeholders", "credentials", "services", "groups", "requirements")) {
            assertEquals(1, checks.stream().filter(c -> c.id().equals(id)).findFirst().orElseThrow().problems().size(), id);
        }
        assertTrue(checks.stream().filter(c -> c.id().equals("compose")).findFirst().orElseThrow().ok());
    }
}
