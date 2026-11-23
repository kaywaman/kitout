package org.kitout.api.dto;

import java.util.List;

/** @param skipped services whose type has no such mode, one sentence each */
public record RunModeResultView(int changed, int running, List<String> skipped) {
}
