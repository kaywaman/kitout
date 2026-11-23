package org.kitout.domain.doctor;

import java.util.List;
import java.util.Optional;

/**
 * One line of doctor output. Every failure names the fix.
 *
 * @param checkId  stable id, e.g. {@code docker}, {@code embedded-credentials}
 * @param title    what was checked, e.g. "Docker daemon"
 * @param detail   what was found, already redacted
 * @param fix      what to do about it; empty only when the result is OK
 * @param advisory true if the result is based on files and may not match runtime
 * @param action   id of a one-click action the UI can offer, e.g. {@code clean-remotes}
 * @param items    affected things (repos, ports), for lists in the UI
 */
public record CheckResult(
        String checkId,
        String title,
        Severity severity,
        String detail,
        Optional<String> fix,
        boolean advisory,
        Optional<String> action,
        List<String> items) {

    public CheckResult {
        items = List.copyOf(items);
    }

    public static CheckResult ok(String checkId, String title, String detail) {
        return new CheckResult(checkId, title, Severity.OK, detail, Optional.empty(), false, Optional.empty(), List.of());
    }

    public static CheckResult info(String checkId, String title, String detail) {
        return new CheckResult(checkId, title, Severity.INFO, detail, Optional.empty(), false, Optional.empty(), List.of());
    }

    public static CheckResult warning(String checkId, String title, String detail, String fix) {
        return new CheckResult(checkId, title, Severity.WARNING, detail, Optional.of(fix), false, Optional.empty(), List.of());
    }

    public static CheckResult error(String checkId, String title, String detail, String fix) {
        return new CheckResult(checkId, title, Severity.ERROR, detail, Optional.of(fix), false, Optional.empty(), List.of());
    }

    public CheckResult asAdvisory() {
        return new CheckResult(checkId, title, severity, detail, fix, true, action, items);
    }

    public CheckResult withAction(String actionId) {
        return new CheckResult(checkId, title, severity, detail, fix, advisory, Optional.of(actionId), items);
    }

    public CheckResult withItems(List<String> newItems) {
        return new CheckResult(checkId, title, severity, detail, fix, advisory, action, newItems);
    }
}
