package org.kitout.api;

import org.kitout.api.dto.PullOutcomeView;
import org.kitout.api.dto.ServiceIdsRequest;
import org.kitout.api.dto.SyncStatusView;
import org.kitout.application.git.GitSyncService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.project.Project;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

import java.util.List;

/** Keeping the local checkouts up to date with GitLab: check (fetch only), then pull. */
@Path("/api")
public class GitSyncResource {

    private final GitSyncService sync;
    private final WorkspaceService workspace;

    public GitSyncResource(GitSyncService sync, WorkspaceService workspace) {
        this.sync = sync;
        this.workspace = workspace;
    }

    /** Fetches and reports branch, ahead/behind and local changes per service. Changes no files. */
    @POST
    @Path("/git/check")
    public List<SyncStatusView> check(ServiceIdsRequest request) {
        return sync.check(projects(request)).stream()
                .map(s -> new SyncStatusView(s.serviceId(), s.name(), s.branch(), s.upstream(), s.ahead(), s.behind(),
                        s.localChanges(), s.pullable(), s.problem()))
                .toList();
    }

    /** Fast-forward pull; checkouts with local changes, no upstream or a diverged branch are left alone. */
    @POST
    @Path("/git/pull")
    public List<PullOutcomeView> pull(ServiceIdsRequest request) {
        return sync.pull(projects(request)).stream()
                .map(o -> new PullOutcomeView(o.serviceId(), o.name(), o.status().name(), o.message(), o.newCommits()))
                .toList();
    }

    private List<Project> projects(ServiceIdsRequest request) {
        if (request == null || request.serviceIds() == null || request.serviceIds().isEmpty()) {
            return workspace.projects().stream().filter(Project::hasCheckout).toList();   // image-only services have no checkout
        }
        return request.serviceIds().stream().map(workspace::require).toList();
    }
}
