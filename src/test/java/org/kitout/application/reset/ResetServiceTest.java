package org.kitout.application.reset;

import org.kitout.application.config.KitoutConfig;
import org.kitout.application.config.KitoutConfig.EnvVar;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ResetServiceTest {

    @Test
    void findsEveryKeyringEntryKitoutCreated() {
        KitoutConfig cfg = KitoutConfig.defaults()
                .withGitlab(new KitoutConfig.GitLabSettings("gitlab.example.com", "https", "~/work", "", null))
                .withRuntime(new KitoutConfig.RuntimeSettings(List.of(new EnvVar("DB_PASSWORD", null, true), new EnvVar("DB_USER", "dev", false))))
                .withService("acme.orders", KitoutConfig.ServiceSettings.defaults("/w/orders")
                        .withEnvironment(List.of(new EnvVar("API_TOKEN", null, true))));

        assertEquals(List.of("gitlab.example.com", "kitout-env/shared/DB_PASSWORD", "kitout-env/acme.orders/API_TOKEN"),
                ResetService.secretKeys(cfg), "the fallback token and every secret variable, nothing else");
    }

    @Test
    void resettingSettingsAlsoRemovesYourTeamSecrets() {
        java.util.List<String> keys = ResetService.secretKeys(org.kitout.application.config.KitoutConfig.defaults(),
                java.util.Optional.of(org.kitout.testsupport.TeamFixtures.filled()));
        org.junit.jupiter.api.Assertions.assertTrue(keys.contains("kitout-env/shared/MONGO_PASSWORD"), keys.toString());
    }
}
