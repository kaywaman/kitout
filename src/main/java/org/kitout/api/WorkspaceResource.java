package org.kitout.api;

import org.kitout.api.dto.FolderEntryView;
import org.kitout.api.dto.FolderListingView;
import org.kitout.api.dto.FolderPreviewProjectView;
import org.kitout.api.dto.FolderPreviewView;
import org.kitout.api.dto.HiddenProjectView;
import org.kitout.api.dto.RemoveAllView;
import org.kitout.api.dto.RunModeRequest;
import org.kitout.api.dto.RunModeResultView;
import org.kitout.application.workspace.FolderBrowser;
import org.kitout.application.workspace.FolderBrowser.Listing;
import org.kitout.application.workspace.ServiceAdminService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.application.workspace.WorkspaceService.Preview;
import org.kitout.domain.run.RunMode;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;

import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/**
 * Managing the list of services: remove one or all, show removed ones again, set the run mode
 * of many at once, and the folder picker that adds projects.
 */
@Path("/api")
public class WorkspaceResource {

    private final ServiceAdminService admin;
    private final FolderBrowser folders;
    private final WorkspaceService workspace;

    public WorkspaceResource(ServiceAdminService admin, FolderBrowser folders, WorkspaceService workspace) {
        this.admin = admin;
        this.folders = folders;
        this.workspace = workspace;
    }

    /** Forgets the service and hides its folder from scans. Files on disk are not touched. 409 while it runs. */
    @DELETE
    @Path("/services/{id}")
    public void remove(@PathParam("id") String id) {
        admin.remove(id);
    }

    /** Forgets all services, groups and workspace folders. 409 while kitout runs any service. */
    @DELETE
    @Path("/services")
    public RemoveAllView removeAll() {
        return new RemoveAllView(admin.removeAll().removed());
    }

    @PUT
    @Path("/services/run-mode")
    public RunModeResultView runMode(RunModeRequest request) {
        if (request == null || request.mode() == null) {
            throw new IllegalArgumentException("Send a mode: IMAGE, JVM, NATIVE or IDE");
        }
        RunMode mode = RunMode.valueOf(request.mode().toUpperCase(Locale.ROOT));
        ServiceAdminService.RunModeResult r = admin.setRunMode(mode, request.serviceIds());
        return new RunModeResultView(r.changed(), r.running(), r.skipped());
    }

    @GET
    @Path("/hidden-projects")
    public List<HiddenProjectView> hidden() {
        return admin.hiddenProjects().stream()
                .map(p -> new HiddenProjectView(p, String.valueOf(Paths.get(p).getFileName())))
                .toList();
    }

    /** Shows a removed project again. */
    @DELETE
    @Path("/hidden-projects")
    public void restore(@QueryParam("path") String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Give the path of the project to show again");
        }
        admin.restore(path);
    }

    @GET
    @Path("/folders")
    public FolderListingView folders(@QueryParam("path") String path) {
        Listing l = folders.list(path);
        return new FolderListingView(l.path(), l.parent(), l.home(),
                l.entries().stream().map(e -> new FolderEntryView(e.name(), e.path(), e.quarkusProject(), e.gitRepository())).toList(),
                l.truncated());
    }

    /** The Quarkus projects a scan of this folder would add; nothing is saved. */
    @GET
    @Path("/folders/preview")
    public FolderPreviewView preview(@QueryParam("path") String path) {
        String folder = folders.resolve(path).toString();
        Preview p = workspace.preview(folder);
        return new FolderPreviewView(folder,
                p.projects().stream().map(q -> new FolderPreviewProjectView(q.id(), q.name(), q.path().toString(), q.devPort())).toList(),
                p.hiddenPaths(), p.outsideTeam());
    }
}
