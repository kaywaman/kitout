package org.kitout.api.dto;

import java.util.List;

/** One doctor result. {@code action} names a one-click fix the UI can offer, e.g. clean-remotes. */
public record CheckView(String checkId, String title, String severity, String detail, String fix,
                        boolean advisory, String action, List<String> items) {
}
