package org.kitout.api.security;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionTokenTest {

    @Test
    void theKeySurvivesARestartAndOnlyTheDeveloperCanReadIt() throws Exception {
        Path file = Files.createTempDirectory("kitout-key").resolve("state/session-token");
        String first = new SessionToken(file).value();
        assertEquals(first, new SessionToken(file).value(), "a restart keeps the key, so the browser needs no new link");
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
        Files.delete(file);
        assertFalse(first.equals(new SessionToken(file).value()), "deleting the file makes a new key");
        assertTrue(new SessionToken(file).matches(Files.readString(file).strip()));
    }
}
