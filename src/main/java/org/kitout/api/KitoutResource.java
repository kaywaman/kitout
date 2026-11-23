package org.kitout.api;

import org.kitout.api.dto.KitoutInfoView;
import org.kitout.application.KitoutPaths;
import org.kitout.application.run.LogService;
import io.smallrye.mutiny.Multi;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import org.jboss.resteasy.reactive.RestStreamElementType;

/** kitout itself: where its files are, and its own log, so nobody needs a terminal to look. */
@Path("/api/kitout")
public class KitoutResource {

    private final KitoutPaths paths;
    private final LogService logs;

    public KitoutResource(KitoutPaths paths, LogService logs) {
        this.paths = paths;
        this.logs = logs;
    }

    @GET
    public KitoutInfoView info() {
        return new KitoutInfoView(paths.kitoutLog().toString(), paths.configFile().toString(), paths.stateDir().toString());
    }

    /**
     * kitout's own log as server-sent events, like a service log: the last {@code lines} lines,
     * then new ones. Redacted like every log kitout shows.
     */
    @GET
    @Path("/log")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.TEXT_PLAIN)
    public Multi<String> log(@QueryParam("lines") @DefaultValue("500") int lines) {
        return logs.follow(paths.kitoutLog(), Math.max(0, Math.min(lines, 5000)));
    }
}
