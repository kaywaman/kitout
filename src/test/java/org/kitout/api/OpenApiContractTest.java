package org.kitout.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Keeps {@code src/main/webui/openapi.json} equal to the API the daemon really serves.
 * The UI build generates its TypeScript types from that file, so an API change that is not
 * carried over to the UI breaks the build instead of the UI at runtime.
 *
 * <p>After an intended API change: {@code ./mvnw test -Dkitout.updateApi=true}, then commit the file.
 * On the very first build (no file yet) the test writes it and passes.
 */
@QuarkusTest
class OpenApiContractTest {

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);

    @Test
    void committedSpecMatchesTheServedApi() throws Exception {
        String served = given().queryParam("format", "json").when().get("/q/openapi")
                .then().statusCode(200).extract().asString();
        JsonNode servedTree = normalize(JSON.readTree(served));
        Path committed = Path.of(System.getProperty("kitout.webui.dir", "src/main/webui")).resolve("openapi.json");

        boolean update = Boolean.getBoolean("kitout.updateApi");
        if (update || !Files.exists(committed)) {
            Files.writeString(committed, JSON.writeValueAsString(servedTree) + "\n");
            return;
        }
        JsonNode committedTree = normalize(JSON.readTree(committed.toFile()));
        if (!committedTree.equals(servedTree)) {
            Path actual = Path.of("target", "openapi.actual.json");
            Files.createDirectories(actual.getParent());
            Files.writeString(actual, JSON.writeValueAsString(servedTree));
            fail("The API changed but " + committed + " did not. If the change is intended, run "
                    + "'./mvnw test -Dkitout.updateApi=true', check that the UI still compiles, and commit the file. "
                    + "Served version: " + actual);
        }
    }

    /** Server URLs and the info block may differ between machines; the contract is paths and schemas. */
    private static JsonNode normalize(JsonNode tree) {
        JsonNode copy = tree.deepCopy();
        if (copy instanceof com.fasterxml.jackson.databind.node.ObjectNode o) {
            o.remove("servers");
            o.remove("info");
        }
        return JSON.valueToTree(JSON.convertValue(copy, Object.class));
    }
}
