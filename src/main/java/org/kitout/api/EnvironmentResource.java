package org.kitout.api;

import org.kitout.api.dto.EnvImportRequest;
import org.kitout.api.dto.EnvImportView;
import org.kitout.api.dto.EnvRequest;
import org.kitout.api.dto.EnvVarView;
import org.kitout.application.config.KitoutConfig;
import org.kitout.application.env.EnvironmentService;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

import java.util.List;

/** Environment variables for the services kitout starts: shared ones and per service. Secret values are write-only. */
@Path("/api")
public class EnvironmentResource {

    private final EnvironmentService environment;

    public EnvironmentResource(EnvironmentService environment) {
        this.environment = environment;
    }

    @GET
    @Path("/env/shared")
    public List<EnvVarView> shared() {
        return views(environment.list(EnvironmentService.SHARED));
    }

    @PUT
    @Path("/env/shared")
    public List<EnvVarView> saveShared(EnvRequest request) {
        return views(environment.save(EnvironmentService.SHARED, changes(request)));
    }

    @GET
    @Path("/services/{id}/env")
    public List<EnvVarView> service(@PathParam("id") String id) {
        return views(environment.list(id));
    }

    @PUT
    @Path("/services/{id}/env")
    public List<EnvVarView> saveService(@PathParam("id") String id, EnvRequest request) {
        return views(environment.save(id, changes(request)));
    }

    /** Takes over a {@code .env} file's variables (shared); passwords and keys go to the keyring. */
    @POST
    @Path("/env/shared/import")
    public EnvImportView importShared(EnvImportRequest request) {
        return importInto(EnvironmentService.SHARED, request);
    }

    @POST
    @Path("/services/{id}/env/import")
    public EnvImportView importService(@PathParam("id") String id, EnvImportRequest request) {
        return importInto(id, request);
    }

    private EnvImportView importInto(String scope, EnvImportRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            throw new IllegalArgumentException("The .env file is empty");
        }
        EnvironmentService.ImportResult r = environment.importDotEnv(scope, request.text());
        return new EnvImportView(r.added(), r.secrets(), r.skipped());
    }

    private static List<EnvironmentService.Change> changes(EnvRequest request) {
        if (request == null || request.variables() == null) {
            return List.of();
        }
        return request.variables().stream().map(v -> new EnvironmentService.Change(v.name(), v.value(), v.secret(), KitoutConfig.EnvUse.parse(v.use()))).toList();
    }

    private static List<EnvVarView> views(List<EnvironmentService.Entry> entries) {
        return entries.stream().map(e -> new EnvVarView(e.name(), e.value(), e.secret(), e.set(), e.use().name())).toList();
    }
}
