package org.kitout.application.doctor.checks;

import org.kitout.application.git.CredentialService.Source;
import org.kitout.application.port.GitLabApi.TokenInfo;
import org.kitout.domain.doctor.CheckResult;
import org.kitout.domain.doctor.Severity;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The decision logic of checks, without running commands. */
class ChecksLogicTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 28);
    private final GitLabTokenCheck tokenCheck = new GitLabTokenCheck(null, null);

    private List<CheckResult> evaluate(List<String> scopes, Optional<LocalDate> expires, Source source) {
        return tokenCheck.evaluate(new TokenInfo("t", true, scopes, expires), source, TODAY);
    }

    @Test
    void goodTokenIsOk() {
        List<CheckResult> r = evaluate(List.of("read_api", "read_repository", "write_repository"),
                Optional.of(TODAY.plusDays(60)), Source.GIT_CREDENTIAL_HELPER);
        assertEquals(1, r.size());
        assertEquals(Severity.OK, r.get(0).severity());
    }

    @Test
    void warnsAboutBroadScopeMissingExpiryAndSoonExpiry() {
        assertTrue(evaluate(List.of("api"), Optional.of(TODAY.plusDays(60)), Source.GIT_CREDENTIAL_HELPER)
                .stream().anyMatch(c -> c.detail().contains("'api'")));
        assertTrue(evaluate(List.of("read_api", "read_repository"), Optional.empty(), Source.GIT_CREDENTIAL_HELPER)
                .stream().anyMatch(c -> c.detail().contains("never expires")));
        assertTrue(evaluate(List.of("read_api", "read_repository"), Optional.of(TODAY.plusDays(10)), Source.GIT_CREDENTIAL_HELPER)
                .stream().anyMatch(c -> c.detail().contains("expires in 10 days")));
    }

    @Test
    void namesMissingScopes() {
        assertTrue(evaluate(List.of("read_repository"), Optional.of(TODAY.plusDays(60)), Source.GIT_CREDENTIAL_HELPER)
                .stream().anyMatch(c -> c.detail().contains("read_api")));
    }

    @Test
    void recommendsTheHelperWhenUsingTheFallback() {
        List<CheckResult> r = evaluate(List.of("read_api", "read_repository"), Optional.of(TODAY.plusDays(60)), Source.KITOUT_KEYRING);
        assertEquals(Severity.OK, r.get(0).severity());
        assertTrue(r.stream().anyMatch(c -> c.severity() == Severity.INFO));
    }

    @Test
    void parsesJavaVersions() {
        assertEquals(21, JdkCheck.parseMajor("openjdk version \"21.0.12.1\" 2026-08-18").getAsInt());
        assertEquals(8, JdkCheck.parseMajor("java version \"1.8.0_402\"").getAsInt());
        assertEquals(25, JdkCheck.parseMajor("openjdk version \"25\" 2025-09-16").getAsInt());
    }
}
