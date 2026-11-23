package org.kitout.api;

import org.kitout.api.dto.CredentialStatusView;
import org.kitout.api.dto.SettingsView;
import org.kitout.api.dto.TokenRequest;
import org.kitout.application.config.ConfigRepository;
import org.kitout.application.git.CredentialService;
import org.kitout.application.git.CredentialService.Source;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;

/** Settings, and the status of the GitLab token. The token itself is write-only. */
@Path("/api")
public class SettingsResource {

    private final ConfigRepository config;
    private final CredentialService credentials;

    public SettingsResource(ConfigRepository config, CredentialService credentials) {
        this.config = config;
        this.credentials = credentials;
    }

    @GET
    @Path("/settings")
    public SettingsView settings() {
        return ApiMapper.settings(config.load());
    }

    @PUT
    @Path("/settings")
    public SettingsView update(SettingsView view) {
        if (view == null) {
            throw new IllegalArgumentException("Send the settings");
        }
        return ApiMapper.settings(config.update(c -> ApiMapper.apply(c, view)));
    }

    @GET
    @Path("/credentials")
    public CredentialStatusView credentials() {
        String host = requireHost();
        Source source = credentials.lookup(host).source();
        return new CredentialStatusView(host, source.name(), credentials.hasFallbackToken(host), credentials.fallbackLocation());
    }

    /** Stores kitout's fallback token. Prefer git's credential helper; this is for developers without one. */
    @PUT
    @Path("/credentials/token")
    public CredentialStatusView storeToken(TokenRequest request) {
        credentials.storeFallbackToken(requireHost(), request == null ? null : request.token());
        return credentials();
    }

    @DELETE
    @Path("/credentials/token")
    public CredentialStatusView deleteToken() {
        credentials.deleteFallbackToken(requireHost());
        return credentials();
    }

    private String requireHost() {
        String host = config.load().gitlab().host();
        if (host.isEmpty()) {
            throw new IllegalStateException("Set the git host in Settings, Git first");
        }
        return host;
    }
}
