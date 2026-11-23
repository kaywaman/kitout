package org.kitout.domain.run;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DockerOptionsTest {

    @Test
    void splitsLikeAShellWithoutRunningOne() {
        assertEquals(List.of("--memory", "1g", "--add-host", "db.local:10.0.0.5"), DockerOptions.parse("  --memory 1g   --add-host db.local:10.0.0.5 "));
        assertEquals(List.of("--label-file", "/a b/x"), DockerOptions.parse("--label-file '/a b/x'"));
        assertEquals(List.of("--entrypoint", "sh -c \"x\""), DockerOptions.parse("--entrypoint \"sh -c \\\"x\\\"\""));
        assertEquals(List.of("--user", "$HOME"), DockerOptions.parse("--user $HOME"), "nothing is expanded");
        assertEquals(List.of(), DockerOptions.parse(null));
        assertEquals(List.of(), DockerOptions.parse("   "));
    }

    @Test
    void acceptsFlagsValuesAndEqualsForms() {
        assertEquals(List.of("--privileged", "--cpus=2", "-v", "/a:/b", "-iP"), DockerOptions.parse("--privileged --cpus=2 -v /a:/b -iP"));
    }

    @Test
    void refusesWhatKitoutSetsItselfWithTheReason() {
        for (String typed : List.of("--name x", "--network bridge", "--net=bridge", "-p 8080:8080", "--rm", "-d", "--detach",
                "-t", "-e A=1", "--env-file .env", "--label a=b", "-it")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse(typed), typed);
            assertTrue(e.getMessage().contains("cannot be"), typed + ": " + e.getMessage());
        }
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse("--network host")).getMessage()
                .contains("localhost URLs"));
    }

    @Test
    void refusesWordsThatWouldReplaceTheImage() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse("--init busybox")).getMessage()
                .contains("'busybox' is not one"));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse("--memory"));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse("-vx /a:/b"));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.parse("--memory '1g"));
    }

    @Test
    void mountsAreBindMountsWithAbsolutePaths() {
        assertEquals("type=bind,source=/home/dev/certs,target=/deployments/certs,readonly",
                DockerOptions.mount("/home/dev/certs", "/deployments/certs", true));
        assertEquals("type=bind,source=/home/dev/my data,target=/data", DockerOptions.mount("/home/dev/my data", "/data", false));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.mount("relative", "/data", false));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.mount("/a", "data", false));
        assertThrows(IllegalArgumentException.class, () -> DockerOptions.mount("/a,b", "/data", false));
    }
}
