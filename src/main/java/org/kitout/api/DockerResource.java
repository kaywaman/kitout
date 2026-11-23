package org.kitout.api;

import org.kitout.api.dto.ComposeEnvironmentView;
import org.kitout.api.dto.ComposeBrowseView;
import org.kitout.api.dto.ComposeCandidateView;
import org.kitout.api.dto.FolderEntryView;
import org.kitout.api.dto.PathRequest;
import org.kitout.api.dto.PathStatusView;
import org.kitout.api.dto.PickedComposeFileView;
import org.kitout.api.dto.ComposeJobView;
import org.kitout.api.dto.ContainerView;
import org.kitout.api.dto.DockerOverviewView;
import org.kitout.application.docker.ComposeJob;
import org.kitout.application.docker.ComposeService;
import org.kitout.application.docker.ComposeService.Containers;
import org.kitout.application.docker.ComposeService.Environment;
import org.kitout.application.run.LogService;
import org.kitout.application.workspace.FolderBrowser;
import org.kitout.application.workspace.FolderBrowser.Listing;
import org.kitout.domain.docker.Container;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;

/** The Docker page: compose environments (up, down, live output) and the containers on this machine. */
@Path("/api/docker")
public class DockerResource {

    private final ComposeService compose;
    private final LogService logs;
    private final FolderBrowser folders;

    public DockerResource(ComposeService compose, LogService logs, FolderBrowser folders) {
        this.compose = compose;
        this.logs = logs;
        this.folders = folders;
    }

    /** Environments with their container counts, and {@code docker ps} (with {@code all=true} also stopped containers). */
    @GET
    public DockerOverviewView overview(@QueryParam("all") boolean all) {
        List<Environment> environments = compose.environments();
        // counts need stopped containers too, so ask for all and filter the list afterwards
        Containers everything = compose.containers(true);
        List<ComposeEnvironmentView> envViews = environments.stream().map(e -> {
            String file = e.file().toString();
            List<Container> mine = everything.list().stream().filter(c -> c.createdFrom(file)).toList();
            int running = (int) mine.stream().filter(Container::running).count();
            return new ComposeEnvironmentView(e.id(), e.name(), file, e.file().getParent().toString(), running, mine.size(),
                    compose.latestJob(e.id()).map(DockerResource::job).orElse(null), e.picked(),
                    e.files().stream().map(java.nio.file.Path::toString).toList(), e.profiles(), e.team(), e.description(), e.missing(), e.autoStart(), e.downloaded());
        }).toList();
        List<ContainerView> containers = everything.list().stream()
                .filter(c -> all || c.running())
                .map(c -> new ContainerView(c.id().length() > 12 ? c.id().substring(0, 12) : c.id(), c.name(), c.image(),
                        c.state(), c.status(), c.ports(), c.composeProject(),
                        environments.stream().filter(e -> c.createdFrom(e.file().toString())).map(Environment::id).findFirst().orElse(null),
                        c.kitoutService().isEmpty() ? null : c.kitoutService()))
                .toList();
        List<PathStatusView> dirs = compose.folders().entrySet().stream()
                .map(f -> new PathStatusView(f.getKey(), f.getValue())).toList();
        List<PathStatusView> picked = compose.pickedFiles().entrySet().stream()
                .map(f -> new PathStatusView(f.getKey(), f.getValue())).toList();
        return new DockerOverviewView(everything.available(), everything.problem(), envViews, containers, dirs, picked);
    }

    /** The compose file picker: sub-folders and YAML files of one folder inside home, compose files marked. */
    @GET
    @Path("/browse")
    public ComposeBrowseView browse(@QueryParam("path") String path) {
        Listing l = folders.list(path);
        return new ComposeBrowseView(l.path(), l.parent(), l.home(),
                l.entries().stream().map(e -> new FolderEntryView(e.name(), e.path(), e.quarkusProject(), e.gitRepository())).toList(),
                compose.candidates(l.path()).stream()
                        .map(c -> new ComposeCandidateView(c.name(), c.path(), c.compose(), c.environment(), c.picked())).toList());
    }

    /** Adds a compose file by hand; it becomes an environment. 400 if it is not a compose file. */
    @POST
    @Path("/files")
    public PickedComposeFileView pick(PathRequest request) {
        ComposeService.Environment e = compose.pick(request == null ? null : request.path());
        return new PickedComposeFileView(e.id(), e.file().toString());
    }

    /** Forgets a picked compose file; the file stays on disk. */
    @DELETE
    @Path("/files")
    public void forget(@QueryParam("path") String path) {
        compose.forget(path);
    }

    /** {@code docker compose -f <file> up -d}, as a job; follow its output with {@code /jobs/{id}/log}. */
    @POST
    @Path("/environments/{id}/up")
    public ComposeJobView up(@PathParam("id") String id) {
        return job(compose.start(id, ComposeJob.Action.UP));
    }

    /** {@code docker compose -f <file> down}: stops and removes the containers; volumes (data) stay. */
    @POST
    @Path("/environments/{id}/down")
    public ComposeJobView down(@PathParam("id") String id) {
        return job(compose.start(id, ComposeJob.Action.DOWN));
    }

    @GET
    @Path("/jobs/{id}")
    public ComposeJobView jobStatus(@PathParam("id") String id) {
        return job(compose.requireJob(id));
    }

    @GET
    @Path("/jobs/{id}/log")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> jobLog(@PathParam("id") String id) {
        return logs.follow(compose.requireJob(id).logFile(), 2000);
    }

    static ComposeJobView job(ComposeJob j) {
        return new ComposeJobView(j.id(), j.environmentId(), j.action().name(), j.status().name(), j.startedAt(),
                j.finishedAt().orElse(null), j.exitCode().orElse(null));
    }
}
