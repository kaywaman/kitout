package org.kitout.api.dto;

import java.util.List;

/** @param skipped one sentence per service that was not built, e.g. "orders is already building (running)" */
public record BuildBatchView(List<BuildView> started, List<String> skipped) {
}
