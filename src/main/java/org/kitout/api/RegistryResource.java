package org.kitout.api;

import org.kitout.api.dto.ImageTagsView;
import org.kitout.api.dto.RegistryLoginRequest;
import org.kitout.api.dto.RegistryStatusView;
import org.kitout.api.dto.ServiceView;
import org.kitout.api.dto.TagRequest;
import org.kitout.application.registry.RegistryService;
import org.kitout.application.registry.RegistryService.Status;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;

/** The container registry: the docker login, and the image tag each service runs. */
@Path("/api")
public class RegistryResource {

    private final RegistryService registry;
    private final ServiceViews views;

    public RegistryResource(RegistryService registry, ServiceViews views) {
        this.registry = registry;
        this.views = views;
    }

    @GET
    @Path("/registry")
    public RegistryStatusView status() {
        return view(registry.status());
    }

    /** {@code docker login}; the password goes to Docker on stdin and is not stored by kitout. */
    @PUT
    @Path("/registry/login")
    public RegistryStatusView login(RegistryLoginRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Send the registry user and password");
        }
        return view(registry.login(request.username(), request.password()));
    }

    @DELETE
    @Path("/registry/login")
    public RegistryStatusView logout() {
        return view(registry.logout());
    }

    /** The tags of the service's image repository, read from the registry with the docker login. 409 with the reason if it cannot. */
    @GET
    @Path("/services/{id}/image-tags")
    public ImageTagsView tags(@PathParam("id") String id) {
        RegistryService.TagList t = registry.tags(id);
        return new ImageTagsView(t.serviceId(), t.repository(), t.tags(), t.current());
    }

    /** Chooses the tag the service runs in "Registry image" mode, from its next start on. */
    @PUT
    @Path("/services/{id}/image-tag")
    public ServiceView chooseTag(@PathParam("id") String id, TagRequest request) {
        registry.chooseTag(id, request == null ? null : request.tag());
        return views.one(id);
    }

    private static RegistryStatusView view(Status s) {
        return new RegistryStatusView(s.host(), s.loggedIn(), s.username(), s.detail());
    }
}
