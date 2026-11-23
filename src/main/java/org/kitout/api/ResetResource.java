package org.kitout.api;

import org.kitout.api.dto.ResetOutcomeView;
import org.kitout.api.dto.ResetPreviewView;
import org.kitout.api.dto.ResetRequest;
import org.kitout.application.reset.ResetService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;

/** Start kitout over: preview first, then reset the chosen parts. */
@Path("/api/reset")
public class ResetResource {

    private final ResetService reset;

    public ResetResource(ResetService reset) {
        this.reset = reset;
    }

    @GET
    public ResetPreviewView preview() {
        ResetService.Preview p = reset.preview();
        return new ResetPreviewView(p.runningServices(), p.logFiles(), p.logBytes(), p.buildLogs(), p.builds(), p.buildOutputs(),
                p.images(), p.configFile(), p.secrets());
    }

    @POST
    public ResetOutcomeView reset(ResetRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Choose what to reset");
        }
        ResetService.Outcome o = reset.reset(new ResetService.Options(request.settings(), request.logs(), request.builds(),
                request.buildOutput(), request.images()));
        return new ResetOutcomeView(o.done(), o.problems());
    }
}
