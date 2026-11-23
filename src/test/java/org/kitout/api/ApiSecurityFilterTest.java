package org.kitout.api;

import org.kitout.api.security.SessionToken;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;

/** The three guards of the local API: loopback Host, same Origin, session token. */
@QuarkusTest
class ApiSecurityFilterTest {

    @Inject
    SessionToken token;

    @Test
    void rejectsRequestsWithoutToken() {
        given().log().ifValidationFails().when().get("/api/settings")
                .then().log().ifValidationFails().statusCode(403).body("error", equalTo("invalid-token"));
    }

    @Test
    void rejectsWrongToken() {
        given().log().ifValidationFails().header(SessionToken.HEADER, "guess").when().get("/api/settings")
                .then().log().ifValidationFails().statusCode(403);
    }

    @Test
    void rejectsForeignHost() {
        given().log().ifValidationFails().header(SessionToken.HEADER, token.value()).header("Host", "evil.example.com")
                .when().get("/api/settings")
                .then().log().ifValidationFails().statusCode(403).body("error", equalTo("forbidden-host"));
    }

    @Test
    void rejectsCrossOriginCalls() {
        given().log().ifValidationFails().header(SessionToken.HEADER, token.value()).header("Origin", "https://evil.example.com")
                .when().get("/api/settings")
                .then().log().ifValidationFails().statusCode(403).body("error", equalTo("forbidden-origin"));
    }

    @Test
    void acceptsTheUi() {
        given().log().ifValidationFails().header(SessionToken.HEADER, token.value())
                .when().get("/api/settings")
                .then().log().ifValidationFails().statusCode(200);
    }

    @Test
    void neverReturnsTheGitLabToken() {
        given().log().ifValidationFails().header(SessionToken.HEADER, token.value())
                .when().get("/api/credentials")
                // no GitLab host configured in the test home: a clear 409, and no token field in any case
                .then().log().ifValidationFails().statusCode(409).body("error", equalTo("not-possible"));
    }
}
