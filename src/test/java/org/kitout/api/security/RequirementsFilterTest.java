package org.kitout.api.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementsFilterTest {

    @Test
    void buildingAndStartingWaitForTheRequirementsFixingAndStoppingDoNot() {
        for (String p : new String[] {"/api/services/acme.orders/start", "/api/services/acme.orders/restart", "/api/services/acme.orders/build",
                "/api/builds", "/api/groups/shop/start", "/api/docker/environments/dev/up", "/api/clone", "/api/git/pull", "/api/scan", "/api/team/clone", "/api/setup/local", "/api/setup/clone"}) {
            assertFalse(RequirementsFilter.allowed(p), p);
        }
        for (String p : new String[] {"/api/requirements/check", "/api/team", "/api/team/secrets/MONGO_PASSWORD", "/api/team/refresh", "/api/setup/check",
                "/api/settings", "/api/env/shared/import", "/api/services/acme.orders/env", "/api/services/acme.orders/stop",
                "/api/groups/shop/stop", "/api/docker/environments/dev/down", "/api/builds/20261030-1", "/api/reset", "/api/registry/login"}) {
            assertTrue(RequirementsFilter.allowed(p), p);
        }
        assertTrue(RequirementsFilter.isRead("GET"));
    }
}
