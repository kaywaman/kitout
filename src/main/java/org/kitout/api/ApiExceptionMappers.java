package org.kitout.api;

import org.kitout.api.dto.ErrorView;
import org.kitout.application.port.GitLabApi.GitLabException;
import org.kitout.application.run.GroupRunner.BlockedByDoctorException;
import org.kitout.domain.identity.Redactor;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

import java.io.UncheckedIOException;
import java.util.NoSuchElementException;

/**
 * One mapping from exceptions to HTTP answers, so every resource behaves the same:
 * unknown thing 404, bad input 400, "not possible right now" 409, GitLab trouble 502.
 * Messages are written for the developer and always redacted.
 */
public class ApiExceptionMappers {

    private static final Logger LOG = Logger.getLogger(ApiExceptionMappers.class);

    @ServerExceptionMapper
    public Response notFound(NoSuchElementException e) {
        return error(Response.Status.NOT_FOUND, "not-found", e.getMessage());
    }

    @ServerExceptionMapper
    public Response badRequest(IllegalArgumentException e) {
        return error(Response.Status.BAD_REQUEST, "bad-request", e.getMessage());
    }

    @ServerExceptionMapper
    public Response blocked(BlockedByDoctorException e) {
        return Response.status(Response.Status.CONFLICT).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(ApiMapper.report(e.report())).header("X-Kitout-Blocked", "doctor").build();
    }

    @ServerExceptionMapper
    public Response conflict(IllegalStateException e) {
        return error(Response.Status.CONFLICT, "not-possible", e.getMessage());
    }

    @ServerExceptionMapper
    public Response gitlab(GitLabException e) {
        return error(Response.Status.BAD_GATEWAY, "gitlab", e.getMessage());
    }

    @ServerExceptionMapper
    public Response io(UncheckedIOException e) {
        LOG.warn(Redactor.redact(e.getMessage()));
        return error(Response.Status.INTERNAL_SERVER_ERROR, "io", e.getMessage());
    }

    private static Response error(Response.Status status, String code, String message) {
        return Response.status(status).type(MediaType.APPLICATION_JSON_TYPE)
                .entity(new ErrorView(code, Redactor.redact(message == null ? status.getReasonPhrase() : message)))
                .build();
    }
}
