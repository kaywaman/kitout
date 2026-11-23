package org.kitout.api;

import org.kitout.api.dto.AddedServicesView;
import org.kitout.api.dto.AddedLibrariesView;
import org.kitout.api.dto.FoldersRequest;
import org.kitout.api.dto.LibrariesRequest;
import org.kitout.api.dto.TeamDraftRequest;
import org.kitout.api.dto.TeamStatusView;
import org.kitout.api.dto.TeamSuggestionsView;
import org.kitout.application.team.SetupService;
import org.kitout.application.team.TeamCreator;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.List;

/** Building a team setup from scratch, for a developer (or a new team) without a kitout-team.json. */
@Path("/api")
public class TeamCreateResource {

    private final TeamCreator creator;
    private final SetupService setup;
    private final TeamViews views;

    public TeamCreateResource(TeamCreator creator, SetupService setup, TeamViews views) {
        this.creator = creator;
        this.setup = setup;
        this.views = views;
    }

    /** What kitout found on this machine: GitLab host, compose files, .env files, and the requirements it would record. */
    @GET
    @Path("/team/create")
    public TeamSuggestionsView suggestions() {
        TeamCreator.Suggestions s = creator.suggestions();
        return new TeamSuggestionsView(s.gitlabHost(), s.cloneFolder(), s.composeFiles(), s.envFiles(),
                s.requirements().stream().map(r -> new TeamSuggestionsView.RequirementRuleView(String.valueOf(r.get("id")),
                        String.valueOf(r.get("name")), String.valueOf(r.get("require")))).toList(), s.allowed());
    }

    /** Writes ~/.config/kitout/kitout-team.local.json from the answers and follows it. */
    @POST
    @Path("/team/create")
    public TeamStatusView create(TeamDraftRequest d) {
        if (d == null) {
            throw new IllegalArgumentException("Send the team setup");
        }
        return views.status(creator.create(new TeamCreator.Draft(d.team(), d.gitlabHost(), d.cloneFolder(), d.registryHost(), d.imagePath(),
                d.composeFiles() == null ? List.of() : d.composeFiles(), d.envFile(), d.recordRequirements())));
    }

    /** Adds the projects in these folders to the team setup kitout wrote, then loads them. */
    @POST
    @Path("/team/services")
    public AddedServicesView addServices(FoldersRequest request) {
        if (request == null || request.folders() == null || request.folders().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one folder");
        }
        List<String> added = creator.addServices(request.folders());
        List<String> loaded = setup.loadLocal().added();
        return new AddedServicesView(added, loaded);
    }

    /** Adds the git checkouts in these folders to the team setup kitout wrote as common Java or UI libraries. */
    @POST
    @Path("/team/libraries")
    public AddedLibrariesView addLibraries(LibrariesRequest request) {
        if (request == null || request.folders() == null || request.folders().isEmpty()) {
            throw new IllegalArgumentException("Choose at least one folder");
        }
        return new AddedLibrariesView(creator.addLibraries(request.folders(), request.kind()));
    }

    /** The team setup kitout wrote, as kitout-team.json to commit to a repository the team shares. */
    @GET
    @Path("/team/export")
    @Produces(MediaType.APPLICATION_JSON)
    public Response export() {
        return Response.status(Response.Status.OK)
                .type(MediaType.APPLICATION_JSON_TYPE)
                .header("Content-Disposition", "attachment; filename=\"kitout-team.json\"")
                .entity(creator.export())
                .build();
    }
}
