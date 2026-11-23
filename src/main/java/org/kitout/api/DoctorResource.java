package org.kitout.api;

import org.kitout.api.dto.CleanupOutcomeView;
import org.kitout.api.dto.DoctorReportView;
import org.kitout.api.dto.RemoteFindingView;
import org.kitout.application.doctor.DoctorService;
import org.kitout.application.git.RemoteCleanupService;
import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.QueryParam;

import java.util.List;
import java.util.Optional;

/** The doctor, and the guided cleanup of tokens in remote URLs it offers. */
@Path("/api")
public class DoctorResource {

    private final DoctorService doctor;
    private final RemoteCleanupService cleanup;
    private final WorkspaceService workspace;
    private final GroupService groups;

    public DoctorResource(DoctorService doctor, RemoteCleanupService cleanup, WorkspaceService workspace, GroupService groups) {
        this.doctor = doctor;
        this.cleanup = cleanup;
        this.workspace = workspace;
        this.groups = groups;
    }

    @GET
    @Path("/doctor")
    public DoctorReportView doctor(@QueryParam("group") String group) {
        return ApiMapper.report(doctor.run(Optional.ofNullable(group)));
    }

    /** Dry run: which remote URLs contain credentials. Shows only the clean URLs. */
    @GET
    @Path("/git/remote-credentials")
    public List<RemoteFindingView> findings(@QueryParam("group") String group) {
        return cleanup.find(projects(group)).stream()
                .map(f -> new RemoteFindingView(f.serviceId(), f.serviceName(), f.remote(), f.key(), f.cleanUrl()))
                .toList();
    }

    /** Rewrites the affected remotes, tests each repository and rolls back the ones whose test fails. */
    @POST
    @Path("/git/clean-remotes")
    public List<CleanupOutcomeView> clean(@QueryParam("group") String group) {
        return cleanup.apply(projects(group)).stream()
                .map(o -> new CleanupOutcomeView(o.serviceId(), o.serviceName(), o.status().name(), o.message()))
                .toList();
    }

    private List<Project> projects(String groupName) {
        List<Project> all = workspace.projects();
        if (groupName == null || groupName.isBlank()) {
            return all;
        }
        Group g = groups.require(groupName);
        return all.stream().filter(p -> g.members().contains(p.id())).toList();
    }
}
