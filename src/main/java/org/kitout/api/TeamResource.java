package org.kitout.api;

import org.kitout.api.dto.CloneJobView;
import org.kitout.api.dto.RequirementsView;
import org.kitout.api.dto.SecretValueRequest;
import org.kitout.api.dto.TeamSourceRequest;
import org.kitout.api.dto.TeamStatusView;
import org.kitout.application.clone.CloneService;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.team.RequirementsService;
import org.kitout.application.team.SetupService;
import org.kitout.application.team.TeamService;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;



/** The team file and the machine requirements it sets. */
@Path("/api")
public class TeamResource {

    private final TeamService team;
    private final RequirementsService requirements;
    private final EnvironmentService environment;
    private final CloneService clones;
    private final SetupService setup;
    private final TeamViews views;

    public TeamResource(TeamService team, RequirementsService requirements, EnvironmentService environment, CloneService clones,
                        SetupService setup, TeamViews views) {
        this.setup = setup;
        this.views = views;
        this.team = team;
        this.requirements = requirements;
        this.environment = environment;
        this.clones = clones;
    }

    /** The machine checked against the team's requirements; kept readings are used while the tools are unchanged. */
    @GET
    @Path("/requirements")
    public RequirementsView requirements() {
        return TeamViews.requirements(requirements.check());
    }

    /** Reads every tool again ("Check again"). */
    @POST
    @Path("/requirements/check")
    public RequirementsView checkAgain() {
        return TeamViews.requirements(requirements.check(true));
    }

    @GET
    @Path("/team")
    public TeamStatusView status() {
        return views.status(team.status());
    }

    @PUT
    @Path("/team")
    public TeamStatusView use(TeamSourceRequest request) {
        TeamStatusView view = views.status(team.use(source(request)));
        requirements.check(true);
        return view;
    }

    static org.kitout.application.config.KitoutConfig.TeamSettings source(TeamSourceRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Give the team file: repository and path on the git host, or a file on this machine");
        }
        if (request.repo() != null && !request.repo().isBlank()) {
            return org.kitout.application.config.KitoutConfig.TeamSettings.of(request.host(), request.repo(), request.path(), request.ref());
        }
        if (request.file() != null && !request.file().isBlank()) {
            return org.kitout.application.config.KitoutConfig.TeamSettings.ofFile(request.file());
        }
        if (request.location() != null && !request.location().isBlank()) {
            return org.kitout.application.config.KitoutConfig.TeamSettings.parse(request.location().strip());
        }
        throw new IllegalArgumentException("Give the team file: repository and path on the git host, or a file on this machine");
    }

    @DELETE
    @Path("/team")
    public TeamStatusView forget() {
        return views.status(team.forget());
    }

    @POST
    @Path("/team/refresh")
    public TeamStatusView refresh() {
        TeamStatusView view = views.status(team.refresh());
        requirements.check(true);
        return view;
    }

    /** This developer's value for a secret the team file asks for; it goes to the keyring and is never sent back. */
    @PUT
    @Path("/team/secrets/{name}")
    public TeamStatusView secret(@PathParam("name") String name, SecretValueRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Send the value");
        }
        environment.setTeamSecret(TeamViews.internalScope(request.scope()),
                name, request.value());
        return views.status(team.status());
    }

    /** Clones the team's repositories that are not on this machine yet. */
    @POST
    @Path("/team/clone")
    public CloneJobView cloneMissing(@QueryParam("light") boolean light) {
        return ApiMapper.cloneJob(setup.cloneMissing(light));
    }
}
