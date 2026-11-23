package org.kitout.api.dto;

/** What to reset; see ResetService.Options. */
public record ResetRequest(boolean settings, boolean logs, boolean builds, boolean buildOutput, boolean images) {
}
