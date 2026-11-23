package org.kitout.api.dto;

import java.util.List;

public record ResetOutcomeView(List<String> done, List<String> problems) {
}
