package org.kitout.api;

import org.kitout.api.dto.CloneCandidateView;
import org.kitout.api.dto.CloneJobView;
import org.kitout.api.dto.CloneRequest;
import org.kitout.application.clone.CloneService;
import org.kitout.application.clone.CloneService.Selection;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.util.List;

/**
 * Cloning from GitLab. The group is a query parameter because GitLab group paths contain
 * slashes ({@code acme/shop}).
 */
@Path("/api")
public class CloneResource {

    private final CloneService clones;

    public CloneResource(CloneService clones) {
        this.clones = clones;
    }

    @GET
    @Path("/gitlab/projects")
    public List<CloneCandidateView> projects(@QueryParam("group") String group) {
        if (group == null || group.isBlank()) {
            throw new IllegalArgumentException("Give a GitLab group path (e.g. acme/shop) or a GitHub organization or user (e.g. acme)");
        }
        return clones.candidates(group).stream().map(ApiMapper::candidate).toList();
    }

    @POST
    @Path("/clone")
    public CloneJobView clone(CloneRequest request) {
        if (request == null || request.items() == null) {
            throw new IllegalArgumentException("Select at least one project");
        }
        List<Selection> selection = request.items().stream().map(i -> new Selection(i.fullPath(), i.light())).toList();
        return ApiMapper.cloneJob(clones.clone(selection));
    }

    @GET
    @Path("/clone/{id}")
    public CloneJobView job(@PathParam("id") String id) {
        return ApiMapper.cloneJob(clones.job(id));
    }
}
