package org.kitout.application.run;

import org.kitout.application.config.KitoutConfig.ServiceSettings;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** There is no default tag: a service in IMAGE mode needs one chosen. */
class ImageSelectionTest {

    @Test
    void aTagMustBeChosen() {
        ServiceSettings fresh = ServiceSettings.defaults("/w/orders");
        assertTrue(ServiceSupervisor.needsTag(fresh), "new services have no tag, and none is assumed");
        assertFalse(ServiceSupervisor.needsTag(fresh.withImageTag("mr-123")));
        ServiceSettings legacyOverride = new ServiceSettings("/w/orders", null, "registry.example.com/x/orders:1.2.0", null, 0, null, null, null);
        assertFalse(ServiceSupervisor.needsTag(legacyOverride), "an override that names a tag still works");
        ServiceSettings repoOverride = new ServiceSettings("/w/orders", null, "registry.example.com/x/orders", null, 0, null, null, null);
        assertTrue(ServiceSupervisor.needsTag(repoOverride));
    }

    @Test
    void pullFailuresSayWhatToDo() {
        String denied = ServiceSupervisor.pullFailure("registry.example.com/acme/orders:mr-1", "Error response from daemon: unauthorized: authentication required");
        assertTrue(denied.contains("Settings, Container registry"), denied);
        String gone = ServiceSupervisor.pullFailure("registry.example.com/acme/orders:mr-1", "manifest unknown");
        assertTrue(gone.contains("mr-1 does not exist"), gone);
    }
}
