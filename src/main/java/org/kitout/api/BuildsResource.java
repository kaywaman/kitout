package org.kitout.api;

import org.kitout.api.dto.BuildBatchRequest;
import org.kitout.api.dto.BuildBatchView;
import org.kitout.api.dto.BuildRequestView;
import org.kitout.api.dto.BuildView;
import org.kitout.application.build.BuildJob;
import org.kitout.application.build.BuildService;
import org.kitout.application.run.LogService;
import org.kitout.domain.build.BuildMode;
import org.kitout.domain.build.BuildRequest;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.util.List;
import java.util.Locale;

/** Local builds and the native build queue. */
@Path("/api")
public class BuildsResource {

    private final BuildService builds;
    private final LogService logs;
    private final org.kitout.application.run.ServiceSupervisor supervisor;
    private final org.kitout.application.build.CurrentBuilds current;

    public BuildsResource(BuildService builds, LogService logs, org.kitout.application.run.ServiceSupervisor supervisor,
                          org.kitout.application.build.CurrentBuilds current) {
        this.current = current;
        this.builds = builds;
        this.logs = logs;
        this.supervisor = supervisor;
    }

    /** Takes finished build attempts off the list and deletes their logs; the build output is not touched. */
    @POST
    @Path("/builds/remove")
    public org.kitout.api.dto.BuildRemovalView remove(org.kitout.api.dto.BuildIdsRequest request) {
        BuildService.Removal r = builds.remove(request == null ? List.of() : request.ids());
        return new org.kitout.api.dto.BuildRemovalView(r.removed(), r.kept());
    }

    /** What can run now: the build output on disk, one per service and mode. */
    @GET
    @Path("/builds/current")
    public List<org.kitout.api.dto.CurrentBuildView> current() {
        return current.list(supervisor::runningMode).stream().map(c -> new org.kitout.api.dto.CurrentBuildView(c.serviceId(), c.serviceName(),
                c.mode().name(), c.version().orElse(null), c.builtAt(), c.sizeBytes(), c.path().toString(), c.attemptId().orElse(null),
                c.removable(), c.usedBy().orElse(null))).toList();
    }

    /** Deletes build output (target/quarkus-app or the native runner); output a running service uses stays. */
    @POST
    @Path("/builds/current/remove")
    public org.kitout.api.dto.BuildRemovalView removeCurrent(org.kitout.api.dto.CurrentBuildsRequest request) {
        List<org.kitout.application.build.CurrentBuilds.Key> keys = request == null || request.items() == null ? List.of()
                : request.items().stream().map(i -> new org.kitout.application.build.CurrentBuilds.Key(i.serviceId(),
                        BuildMode.valueOf(i.mode().toUpperCase(Locale.ROOT)))).toList();
        var r = current.remove(keys, supervisor::runningMode);
        return new org.kitout.api.dto.BuildRemovalView(r.removed(), r.kept());
    }

    @POST
    @Path("/services/{id}/build")
    public BuildView build(@PathParam("id") String serviceId, BuildRequestView request) {
        if (request == null) {
            throw new IllegalArgumentException("Send profile, mode and skipTests");
        }
        BuildMode mode = request.mode() == null ? BuildMode.JVM : BuildMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        supervisor.cleanBuildConflict(serviceId).ifPresent(why -> {
            throw new IllegalStateException(why);
        });
        BuildJob job = builds.submit(new BuildRequest(serviceId, request.profile(), mode, request.skipTests()));
        return view(job);
    }

    /** Builds several services: JVM builds all start at once, native builds queue one at a time. */
    @POST
    @Path("/builds")
    public BuildBatchView buildMany(BuildBatchRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Send serviceIds, profile, mode and skipTests");
        }
        BuildMode mode = request.mode() == null ? BuildMode.JVM : BuildMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        List<String> skipped = new java.util.ArrayList<>();
        List<String> ids = request.serviceIds() == null ? List.of() : request.serviceIds().stream().filter(id -> {
            var why = supervisor.cleanBuildConflict(id);
            why.ifPresent(skipped::add);
            return why.isEmpty();
        }).toList();
        BuildService.Batch batch = builds.submitAll(ids, request.profile(), mode, request.skipTests());
        skipped.addAll(batch.skipped());
        return new BuildBatchView(batch.started().stream().map(this::view).toList(), skipped);
    }

    @GET
    @Path("/builds")
    public List<BuildView> builds() {
        return builds.jobs().stream().map(this::view).toList();
    }

    @DELETE
    @Path("/builds/{id}")
    public BuildView cancel(@PathParam("id") String id) {
        builds.cancel(id);
        return view(builds.job(id));
    }

    @GET
    @Path("/builds/{id}/log")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> log(@PathParam("id") String id) {
        return logs.follow(builds.job(id).logFile(), 2000);
    }

    private BuildView view(BuildJob job) {
        return ApiMapper.build(job, builds.queuePosition(job), builds.whyNotRemovable(job), current.attemptNote(job));
    }
}
