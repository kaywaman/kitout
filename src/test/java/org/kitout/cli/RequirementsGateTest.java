package org.kitout.cli;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementsGateTest {

    @Test
    void findsTheCommand() {
        assertEquals("up", RequirementsGate.command("up", "shop"));
        assertEquals("build", RequirementsGate.command("--verbose", "build", "orders"));
        assertEquals("", RequirementsGate.command("--help"));
    }

    @Test
    void commandsThatBuildOrStartAreStoppedTheOthersRun() {
        for (String c : new String[] {"up", "start", "restart", "build", "clone", "pull", "compose", "scan", "mode", "tag"}) {
            assertFalse(RequirementsGate.ALWAYS.contains(c), c);
        }
        for (String c : new String[] {"check", "doctor", "team", "env", "registry", "reset", "status", "logs", "down", "stop", "serve"}) {
            assertTrue(RequirementsGate.ALWAYS.contains(c), c);
        }
    }
}
