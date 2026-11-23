package org.kitout.api;

import org.kitout.api.dto.GroupStartView;
import org.kitout.api.dto.GroupView;
import org.kitout.api.dto.MessagesView;
import org.kitout.api.dto.PortView;
import org.kitout.application.run.GroupRunner;
import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import org.kitout.domain.routing.PortMap;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Groups: list, save, delete, start and stop in tiers, and the port map. */
@Path("/api")
public class GroupsResource {

    private final GroupService groups;
    private final GroupRunner runner;
    private final WorkspaceService workspace;

    public GroupsResource(GroupService groups, GroupRunner runner, WorkspaceService workspace) {
        this.groups = groups;
        this.runner = runner;
        this.workspace = workspace;
    }

    @GET
    @Path("/groups")
    public List<GroupView> groups() {
        return groups.groups().stream().map(ApiMapper::group).toList();
    }

    @PUT
    @Path("/groups/{name}")
    public GroupView save(@PathParam("name") String name, GroupView view) {
        return ApiMapper.group(groups.save(ApiMapper.group(view, name)));
    }

    @DELETE
    @Path("/groups/{name}")
    public void delete(@PathParam("name") String name) {
        groups.delete(name);
    }

    /** 409 with the doctor report when the doctor finds errors and {@code force} is false. */
    @POST
    @Path("/groups/{name}/start")
    public GroupStartView start(@PathParam("name") String name, @QueryParam("force") @DefaultValue("false") boolean force) {
        return ApiMapper.progress(runner.startAsync(name, force));
    }

    @GET
    @Path("/groups/{name}/start")
    public GroupStartView startProgress(@PathParam("name") String name) {
        return runner.progress(name).map(ApiMapper::progress)
                .orElseThrow(() -> new NoSuchElementException("No start of group '" + name + "' since kitout started"));
    }

    @POST
    @Path("/groups/{name}/stop")
    public MessagesView stop(@PathParam("name") String name) {
        List<String> messages = new ArrayList<>();
        runner.stop(name, messages::add);
        return new MessagesView(messages);
    }

    @GET
    @Path("/ports")
    public List<PortView> ports(@QueryParam("group") String groupName) {
        List<Project> projects = workspace.projects();
        if (groupName != null && !groupName.isBlank()) {
            Group g = groups.require(groupName);
            projects = projects.stream().filter(p -> g.members().contains(p.id())).toList();
        }
        Map<String, Integer> ports = new LinkedHashMap<>();
        projects.forEach(p -> ports.put(p.name(), p.devPort()));
        PortMap map = new PortMap(ports);
        return map.all().entrySet().stream()
                .map(e -> new PortView(e.getKey(), e.getValue(), e.getValue().size() > 1))
                .toList();
    }
}
