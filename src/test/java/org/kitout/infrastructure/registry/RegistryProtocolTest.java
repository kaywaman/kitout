package org.kitout.infrastructure.registry;

import org.junit.jupiter.api.Test;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The parts of the registry protocol and Docker's credential format that need no network. */
class RegistryProtocolTest {

    @Test
    void readsTheBearerChallenge() {
        Map<String, String> p = HttpContainerRegistry.challengeParams(
                "Bearer realm=\"https://gitlab.example.com/jwt/auth\",service=\"container_registry\",scope=\"repository:acme/shop/orders:pull\"");
        assertEquals("https://gitlab.example.com/jwt/auth", p.get("realm"));
        assertEquals("container_registry", p.get("service"));
        assertEquals("repository:acme/shop/orders:pull", p.get("scope"));
    }

    @Test
    void followsTheNextLink() {
        assertEquals(Optional.of("/v2/acme/orders/tags/list?last=mr-99&n=1000"),
                HttpContainerRegistry.nextLink("</v2/acme/orders/tags/list?last=mr-99&n=1000>; rel=\"next\""));
        assertEquals(Optional.empty(), HttpContainerRegistry.nextLink(""));
        assertEquals("http://localhost:5000", HttpContainerRegistry.baseUrl("localhost:5000"));
        assertEquals("https://registry.example.com", HttpContainerRegistry.baseUrl("registry.example.com"));
    }

    @Test
    void namesTheSameRegistryHoweverDockerSpelledIt() {
        assertEquals("images.example.com", DockerCredentialStore.normalize("https://images.example.com/v1/"));
        assertEquals("images.example.com", DockerCredentialStore.normalize("images.example.com:443"));
        assertEquals("images.example.com:5000", DockerCredentialStore.normalize("images.example.com:5000/team"));
        assertEquals("docker.io", DockerCredentialStore.normalize("https://index.docker.io/v1/"));
        assertEquals("{\"Username\":\"u\",\"Secret\":\"s\"}",
                DockerCredentialStore.jsonPart("gpg: WARNING: unsafe ownership\n{\"Username\":\"u\",\"Secret\":\"s\"}\n"),
                "warnings around the JSON are ignored");
        assertEquals("images.example.com", org.kitout.application.registry.RegistryLogins.hostOf("https://Images.example.com/team"));
    }

    @Test
    void decodesDockerAuthEntries() {
        String auth = Base64.getEncoder().encodeToString("dev.user:s3cr:et".getBytes());
        var c = DockerCredentialStore.decodeAuth(auth).orElseThrow();
        assertEquals("dev.user", c.username());
        assertEquals("s3cr:et", c.secret(), "only the first colon separates user and secret");
        assertTrue(DockerCredentialStore.decodeAuth("not base64!").isEmpty());
        assertTrue(!c.toString().contains("s3cr"), "toString never shows the secret");
        assertEquals(List.of("registry.example.com", "https://registry.example.com", "http://registry.example.com"),
                DockerCredentialStore.serverKeys("registry.example.com"));
    }
}
