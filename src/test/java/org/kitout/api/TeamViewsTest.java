package org.kitout.api;

import org.kitout.application.env.EnvironmentService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TeamViewsTest {

    @Test
    void theSharedScopeIsCalledSharedInTheApi() {
        assertEquals("shared", TeamViews.apiScope(EnvironmentService.SHARED));
        assertEquals(EnvironmentService.SHARED, TeamViews.internalScope("shared"));
        assertEquals(EnvironmentService.SHARED, TeamViews.internalScope(null));
        assertEquals("acme.shop.orders", TeamViews.internalScope("acme.shop.orders"));
    }
}
