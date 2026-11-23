package org.kitout.api;

import org.kitout.api.dto.CloneJobView;
import org.kitout.api.dto.FileCheckView;
import org.kitout.api.dto.ScanView;
import org.kitout.api.dto.SetupProjectView;
import org.kitout.api.dto.SetupView;
import org.kitout.application.team.SetupService;
import org.kitout.application.workspace.WorkspaceService.ScanResult;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

/** The first-call walk-through: everything checked at once, then load or clone the team's projects. */
@Path("/api")
public class SetupResource {

    private final SetupService setup;
    private final TeamViews team;
    private final ServiceViews views;

    public SetupResource(SetupService setup, TeamViews team, ServiceViews views) {
        this.setup = setup;
        this.team = team;
        this.views = views;
    }

    @GET
    @Path("/setup")
    public SetupView status() {
        return view(setup.status(false));
    }

    /** Reads every tool again, e.g. after installing the required Java. */
    @POST
    @Path("/setup/check")
    public SetupView check() {
        return view(setup.status(true));
    }

    /** Loads the team's projects already on this machine; others are left out. Needs the machine check passed. */
    @POST
    @Path("/setup/local")
    public ScanView loadLocal() {
        ScanResult r = setup.loadLocal();
        return new ScanView(r.found().stream().map(views::of).toList(), r.added(), r.removed(), r.outsideTeam());
    }

    /** Clones the team's repositories that are not on this machine. Needs the machine check passed. */
    @POST
    @Path("/setup/clone")
    public CloneJobView cloneMissing(@QueryParam("light") boolean light) {
        return ApiMapper.cloneJob(setup.cloneMissing(light));
    }

    /** The team's repositories that are not services (e.g. compose files): where they are, and whether they are cloned. */
    @GET
    @Path("/team/repositories")
    public java.util.List<SetupProjectView> repositories() {
        return setup.repositories().stream().map(SetupResource::project).toList();
    }

    @POST
    @Path("/team/repositories/clone")
    public CloneJobView cloneRepository(@QueryParam("repo") String repo, @QueryParam("light") boolean light) {
        return ApiMapper.cloneJob(setup.cloneRepository(repo, light));
    }

    /** Fast-forward only; skipped with a reason when there are local changes. */
    @POST
    @Path("/team/repositories/pull")
    public org.kitout.api.dto.PullOutcomeView pullRepository(@QueryParam("repo") String repo) {
        var o = setup.pullRepository(repo);
        return new org.kitout.api.dto.PullOutcomeView(o.serviceId(), o.name(), o.status().name(), o.message(), o.newCommits());
    }

    static SetupProjectView project(SetupService.Project p) {
        return new SetupProjectView(p.repo(), p.serviceId(), p.kind(), p.state().name(), p.path(), p.description());
    }

    private SetupView view(SetupService.Setup s) {
        return new SetupView(team.status(s.team()), s.defaultLocation().orElse(null),
                s.fileChecks().stream().map(c -> new FileCheckView(c.id(), c.name(), c.ok(), c.problems())).toList(),
                TeamViews.requirements(s.requirements()), s.missingSecrets(),
                s.projects().stream().map(SetupResource::project).toList(),
                s.workspaceRoot(), s.ready(), s.complete(), s.canCreate(), s.editable(), s.localOnly(),
                s.suggested().map(t -> new org.kitout.api.dto.TeamSourceView(t.host(), t.project(), t.project() == null ? null : t.path(),
                        t.project() == null ? null : t.ref(), t.file())).orElse(null),
                s.presetProblem().orElse(null));
    }
}
