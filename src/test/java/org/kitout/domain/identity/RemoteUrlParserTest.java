package org.kitout.domain.identity;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RemoteUrlParserTest {

    private static final RepoIdentity ORDERS = new RepoIdentity("gitlab.com", "acme/shop", "orders");

    @Test
    void parsesScpLikeSsh() {
        assertEquals(Optional.of(ORDERS), RemoteUrlParser.parse("git@gitlab.com:acme/shop/orders.git"));
    }

    @Test
    void parsesSshUrlWithPort() {
        assertEquals(Optional.of(ORDERS), RemoteUrlParser.parse("ssh://git@gitlab.com:2222/acme/shop/orders.git"));
    }

    @Test
    void parsesHttps() {
        assertEquals(Optional.of(ORDERS), RemoteUrlParser.parse("https://gitlab.com/acme/shop/orders.git"));
    }

    @Test
    void parsesHttpsWithoutDotGit() {
        assertEquals(Optional.of(ORDERS), RemoteUrlParser.parse("https://gitlab.com/acme/shop/orders"));
    }

    @Test
    void ignoresEmbeddedCredentials() {
        assertEquals(Optional.of(ORDERS),
                RemoteUrlParser.parse("https://99999:glpat-abcdefghijklmnopqrst@gitlab.com/acme/shop/orders.git"));
    }

    @Test
    void sameIdentityForEveryForm() {
        assertEquals(RemoteUrlParser.parse("git@gitlab.com:acme/shop/orders.git"),
                RemoteUrlParser.parse("https://user:token@gitlab.com/acme/shop/orders.git"));
    }

    @Test
    void supportsDeeplyNestedGroups() {
        RepoIdentity id = RemoteUrlParser.parse("git@gitlab.example.com:a/b/c/d/svc.git").orElseThrow();
        assertEquals("a/b/c/d", id.namespace());
        assertEquals("svc", id.project());
        assertEquals("a/b/c/d/svc", id.fullPath());
    }

    @Test
    void lowercasesHost() {
        assertEquals("gitlab.com", RemoteUrlParser.parse("https://GitLab.com/acme/orders.git").orElseThrow().host());
    }

    @Test
    void rejectsPathsWithoutNamespace() {
        assertTrue(RemoteUrlParser.parse("https://gitlab.com/orders.git").isEmpty());
    }

    @Test
    void rejectsGarbage() {
        assertTrue(RemoteUrlParser.parse("").isEmpty());
        assertTrue(RemoteUrlParser.parse(null).isEmpty());
        assertTrue(RemoteUrlParser.parse("/local/path/repo").isEmpty());
        assertTrue(RemoteUrlParser.parse("file:///tmp/acme/orders.git").isEmpty());
    }

    @Test
    void derivesDefaultImage() {
        assertEquals("acme/shop/orders", org.kitout.domain.run.ImagePath.render("{namespace}/{project}", ORDERS));
    }
}
