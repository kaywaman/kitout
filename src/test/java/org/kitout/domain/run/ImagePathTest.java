package org.kitout.domain.run;

import org.kitout.domain.identity.RepoIdentity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImagePathTest {

    private static final RepoIdentity ORDERS = new RepoIdentity("gitlab.example.com", "Acme/Shop", "Orders");

    @Test
    void theGroupIsOptional() {
        assertEquals("acme/shop/orders", ImagePath.render("{namespace}/{project}", ORDERS));
        assertEquals("orders", ImagePath.render("{project}", ORDERS));
        assertEquals("acme/orders", ImagePath.render("{group}/{project}", ORDERS));
        assertEquals("services/orders", ImagePath.render("services/{project}", ORDERS));
    }

    @Test
    void rejectsPatternsThatCannotWork() {
        assertThrows(IllegalArgumentException.class, () -> ImagePath.validate("{namespace}"), "all services would share one image");
        assertThrows(IllegalArgumentException.class, () -> ImagePath.validate("{projekt}"));
        assertThrows(IllegalArgumentException.class, () -> ImagePath.validate("My Images/{project}"));
        ImagePath.validate("{project}");
    }

    @Test
    void propertyNamesBecomeEnvironmentVariables() {
        assertEquals("QUARKUS_DATASOURCE_USERNAME", EnvNames.fromProperty("quarkus.datasource.username"));
        assertEquals("QUARKUS_DATASOURCE__PG__JDBC_URL", EnvNames.fromProperty("quarkus.datasource.\"pg\".jdbc.url"));
        assertEquals("_DEV_QUARKUS_HTTP_PORT", EnvNames.fromProperty("%dev.quarkus.http.port"));
        assertEquals("DB_HOST", EnvNames.fromProperty("DB_HOST"));
        assertThrows(IllegalArgumentException.class, () -> EnvNames.validate("QUARKUS_PROFILE"));
        assertThrows(IllegalArgumentException.class, () -> EnvNames.validate("1ABC"));
    }
}
