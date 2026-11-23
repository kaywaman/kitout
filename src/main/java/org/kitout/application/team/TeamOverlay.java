package org.kitout.application.team;

import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.kitout.domain.group.Group;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;
import org.kitout.domain.team.TeamSetup;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Layers the team file under the developer's settings: built-in defaults, then the team, then the
 * developer. Pure: tested without files or network.
 *
 * <ul>
 *   <li><b>Fixed by the team</b> (the same for everyone): GitLab host, registry and image path,
 *       build profiles, the doctor's credential rule (from its date on).</li>
 *   <li><b>Team default, developer may differ</b>: clone protocol, workspace folder, native
 *       container builds, and per service run mode, runtime profile, start timeout and the health check switch. A personal
 *       value equal to kitout's built-in default counts as "not chosen", so the team value shows.</li>
 *   <li><b>Added</b>: the team's groups, as suggested groups, unless the developer has a group of that name (theirs wins).</li>
 * </ul>
 * Variables and secrets are layered by {@code EnvironmentService}, compose environments by {@code ComposeService}.
 */
public final class TeamOverlay {

    private static final ServiceSettings BUILT_IN = ServiceSettings.defaults(null);

    private TeamOverlay() {
    }

    public static KitoutConfig apply(KitoutConfig personal, TeamSetup team, LocalDate today) {
        KitoutConfig.GitLabSettings g = personal.gitlab();
        KitoutConfig.GitLabSettings defaults = KitoutConfig.GitLabSettings.defaults();
        String registry = team.registry().host().isEmpty() ? g.registryHost() : team.registry().host();
        String imagePath = team.registry().imagePath().isEmpty() ? g.imagePath() : team.registry().imagePath();
        KitoutConfig result = personal.withGitlab(new KitoutConfig.GitLabSettings(
                team.localOnly() ? g.host() : team.gitlab().host(),
                !g.cloneProtocol().equals(defaults.cloneProtocol()) || team.gitlab().cloneProtocol().isEmpty() ? g.cloneProtocol() : team.gitlab().cloneProtocol(),
                !g.workspaceRoot().equals(defaults.workspaceRoot()) || team.gitlab().workspaceRoot().isEmpty() ? g.workspaceRoot() : team.gitlab().workspaceRoot(),
                registry, imagePath, team.localOnly() || team.gitlab().provider().isEmpty() ? g.provider() : team.gitlab().provider()));

        KitoutConfig.BuildSettings b = personal.build();
        boolean nativeInContainer = !b.nativeContainerBuild() || team.build().nativeContainerBuild() == null
                ? b.nativeContainerBuild() : team.build().nativeContainerBuild();
        result = result.withBuild(new KitoutConfig.BuildSettings(nativeInContainer, b.nativeMinFreeMemoryMb(),
                team.build().profiles().isEmpty() ? b.profiles() : team.build().profiles()));

        if (team.doctor().embeddedCredentialsAsErrorFrom().isPresent()) {
            result = result.withDoctor(new KitoutConfig.DoctorSettings(!today.isBefore(team.doctor().embeddedCredentialsAsErrorFrom().get())));
        }

        Map<String, ServiceSettings> services = new LinkedHashMap<>();
        result.services().forEach((id, s) -> services.put(id, service(id, s, team, registry)));
        result = result.withServices(services);

        List<Group> groups = new ArrayList<>(result.groups());
        for (TeamSetup.Group tg : team.groups()) {
            if (groups.stream().noneMatch(g2 -> g2.name().equals(tg.name()))) {
                groups.add(new Group(tg.name(), tg.members().stream().map(TeamOverlay::serviceId).toList(),
                        tg.bffs().stream().map(TeamOverlay::serviceId).toList(), tg.sequentialStart(), true));   // shown like a suggested group: editing saves your own copy
            }
        }
        return result.withGroups(groups);
    }

    /**
     * What to save after a change: the developer's own values, not the team's. {@code shown} is
     * what the change started from ({@code apply(before)}); wherever the change left {@code shown}
     * as it was, the developer's previous value is kept, so team values are never copied into
     * config.json and later changes to the team file keep reaching everyone.
     */
    public static KitoutConfig unapply(KitoutConfig before, KitoutConfig shown, KitoutConfig after) {
        KitoutConfig result = after
                .withGitlab(after.gitlab().equals(shown.gitlab()) ? before.gitlab() : after.gitlab())
                .withBuild(after.build().equals(shown.build()) ? before.build() : after.build())
                .withDoctor(after.doctor().equals(shown.doctor()) ? before.doctor() : after.doctor());
        Map<String, ServiceSettings> services = new LinkedHashMap<>();
        after.services().forEach((id, s) -> {
            ServiceSettings old = before.services().get(id);
            services.put(id, old != null && s.equals(shown.services().get(id)) ? old : s);
        });
        List<Group> groups = new ArrayList<>();
        for (Group g : after.groups()) {
            boolean teamAdded = before.groups().stream().noneMatch(b -> b.name().equals(g.name()));
            boolean unchanged = shown.groups().stream().anyMatch(sh -> sh.equals(g));
            if (!(teamAdded && unchanged)) {
                groups.add(g);
            }
        }
        return result.withServices(services).withGroups(groups);
    }

    private static ServiceSettings service(String id, ServiceSettings s, TeamSetup team, String registry) {
        Optional<TeamSetup.Service> ts = team.services().stream().filter(t -> serviceId(t.repo()).equals(id)).findFirst();
        TeamSetup.ServiceDefaults d = team.serviceDefaults();
        String mode = ts.map(TeamSetup.Service::runMode).filter(m -> m != null).orElse(d.runMode());
        String profile = ts.map(TeamSetup.Service::runtimeProfile).filter(p -> p != null).orElse(d.runtimeProfile());
        int timeout = ts.map(TeamSetup.Service::startTimeoutSeconds).filter(t -> t != null).orElse(d.startTimeoutSeconds());
        String image = s.image() != null ? s.image()
                : ts.map(TeamSetup.Service::imagePath).filter(p -> p != null && !p.isBlank()).map(p -> registry + "/" + p).orElse(null);
        return new ServiceSettings(s.path(),
                s.runMode() != BUILT_IN.runMode() ? s.runMode() : RunMode.valueOf(mode),
                image,
                !s.runtimeProfile().equals(BUILT_IN.runtimeProfile()) ? s.runtimeProfile() : profile,
                s.startTimeoutSeconds() != BUILT_IN.startTimeoutSeconds() ? s.startTimeoutSeconds() : timeout,
                s.jvmArgs(), s.imageTag(), s.environment(), s.volumes(), s.dockerArgs(),
                s.healthCheck() != null ? s.healthCheck() : ts.map(TeamSetup.Service::healthCheck).orElse(null),
                s.port(), s.script());
    }

    /** The kitout service id of a repository path: {@code acme/shop/orders} is {@code acme.shop.orders}, as the scanner names it. */
    public static String serviceId(String repo) {
        int slash = repo.lastIndexOf('/');
        if (slash < 0) {   // an image-only service, named without a path
            return repo.toLowerCase().replaceAll("[^a-z0-9._-]+", ".");
        }
        RepoIdentity identity = new RepoIdentity("team", slash < 0 ? repo : repo.substring(0, slash), repo.substring(slash + 1));
        return Project.idFor(Optional.of(identity), Path.of(repo));
    }
}
