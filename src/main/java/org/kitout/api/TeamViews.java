package org.kitout.api;

import org.kitout.api.dto.RequirementView;
import org.kitout.api.dto.RequirementsView;
import org.kitout.api.dto.TeamStatusView;
import org.kitout.api.dto.TeamValueView;
import org.kitout.application.clone.CloneService;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.team.RequirementsService;
import org.kitout.application.team.TeamOverlay;
import org.kitout.application.team.TeamService;
import org.kitout.domain.team.TeamSetup;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Team status and requirement reports as the API shows them. */
@ApplicationScoped
public class TeamViews {

    private final EnvironmentService environment;
    private final CloneService clones;

    public TeamViews(EnvironmentService environment, CloneService clones) {
        this.environment = environment;
        this.clones = clones;
    }

    public TeamStatusView status(TeamService.Status s) {
        Optional<TeamSetup> setup = s.setup();
        List<String> missingRepos = List.of();
        List<TeamValueView> secrets = new ArrayList<>();
        List<TeamValueView> variables = new ArrayList<>();
        if (setup.isPresent()) {
            try {
                missingRepos = clones.repositories(setup.get().repositories()).stream().filter(c -> !c.alreadyLocal())
                        .map(CloneService.Candidate::fullPath).toList();
            } catch (RuntimeException e) {
                missingRepos = List.of();   // no GitLab host yet
            }
            List<String> scopes = new ArrayList<>(List.of(EnvironmentService.SHARED));
            setup.get().services().forEach(svc -> scopes.add(TeamOverlay.serviceId(svc.repo())));
            for (String scope : scopes) {
                for (EnvironmentService.TeamValue v : environment.teamValues(scope)) {
                    (v.secret() ? secrets : variables).add(new TeamValueView(v.name(), v.secret() ? null : v.value(), v.secret(), v.set(), apiScope(scope)));
                }
            }
        }
        return new TeamStatusView(s.configured(), s.location(), setup.isPresent(), setup.map(TeamSetup::team).orElse(""), s.version(),
                s.loadedAt(), s.problems(), s.warnings(), setup.map(t -> t.services().size()).orElse(0),
                setup.map(t -> t.groups().stream().map(TeamSetup.Group::name).toList()).orElse(List.of()),
                setup.map(t -> t.compose().stream().map(TeamSetup.ComposeEnvironment::name).toList()).orElse(List.of()),
                setup.map(t -> t.presets().stream().map(TeamSetup.Preset::name).toList()).orElse(List.of()),
                missingRepos, secrets, variables);
    }

    /** The API names the shared scope "shared"; inside kitout it is {@link EnvironmentService#SHARED}. */
    public static String apiScope(String scope) {
        return EnvironmentService.SHARED.equals(scope) ? "shared" : scope;
    }

    public static String internalScope(String apiScope) {
        return apiScope == null || apiScope.isBlank() || apiScope.equals("shared") ? EnvironmentService.SHARED : apiScope;
    }

    public static RequirementsView requirements(RequirementsService.Report r) {
        return new RequirementsView(r.configured(), r.team(), r.blocked(), r.results().stream()
                .map(x -> new RequirementView(x.id(), x.name(), x.required(), x.found(), x.detail(), x.outcome().name(), x.problem(), x.fix(), x.note()))
                .toList(), r.checkedAt());
    }
}
