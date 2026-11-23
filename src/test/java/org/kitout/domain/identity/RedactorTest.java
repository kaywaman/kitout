package org.kitout.domain.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RedactorTest {

    @Test
    void removesUserAndTokenFromUrlsInText() {
        String out = Redactor.redact("fatal: unable to access 'https://99999:secret123@gitlab.example.com/a/b.git/'");
        assertEquals("fatal: unable to access 'https://gitlab.example.com/a/b.git/'", out);
    }

    @Test
    void removesTokenOnlyUserInfo() {
        assertEquals("https://host/a/b.git", Redactor.redact("https://secret@host/a/b.git"));
    }

    @Test
    void removesBareGitLabTokens() {
        assertEquals("token is [redacted]", Redactor.redact("token is glpat-AbCdEfGhIjKlMnOpQrSt"));
    }

    @Test
    void leavesSshUrlsAlone() {
        assertEquals("git@host:a/b.git", Redactor.redact("git@host:a/b.git"));
        assertEquals("ssh://git@host/a/b.git", Redactor.redact("ssh://git@host/a/b.git"));
    }

    @Test
    void detectsEmbeddedCredentials() {
        assertTrue(Redactor.hasEmbeddedCredentials("https://99999:tok@host/a/b.git"));
        assertFalse(Redactor.hasEmbeddedCredentials("https://host/a/b.git"));
        assertFalse(Redactor.hasEmbeddedCredentials("git@host:a/b.git"));
        assertFalse(Redactor.hasEmbeddedCredentials("ssh://git@host/a/b.git"));
    }

    @Test
    void stripsCredentials() {
        assertEquals("https://host/a/b.git", Redactor.stripCredentials("https://99999:tok@host/a/b.git"));
    }

    @Test
    void handlesNullAndEmpty() {
        assertEquals(null, Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }
}
