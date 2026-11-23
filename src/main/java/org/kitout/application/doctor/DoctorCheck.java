package org.kitout.application.doctor;

import org.kitout.domain.doctor.CheckResult;

import java.util.List;

/**
 * One doctor check. To add a check, write a new {@code @ApplicationScoped} class implementing
 * this interface: the {@link DoctorService} picks up every implementation automatically
 * (open for extension, closed for modification). No other class needs to change.
 *
 * <p>Rules for a good check:
 * <ul>
 *   <li>read-only: never create, start or change anything</li>
 *   <li>fast: finish within a few seconds; the doctor runs checks in parallel with a timeout</li>
 *   <li>every non-OK result names the fix</li>
 *   <li>results based on files, not runtime, are marked {@link CheckResult#asAdvisory()}</li>
 * </ul>
 */
public interface DoctorCheck {

    /** Stable id, also the default check id of its results. */
    String id();

    /** Lower runs earlier in the report. Environment checks first, group-specific checks later. */
    default int order() {
        return 100;
    }

    /** True if this check is useful for the context; e.g. group checks skip when no group is selected. */
    default boolean appliesTo(DoctorContext context) {
        return true;
    }

    List<CheckResult> run(DoctorContext context);
}
