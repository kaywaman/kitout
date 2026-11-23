package org.kitout.api.dto;

import java.util.List;

public record CloneJobView(String id, List<CloneItemView> items, boolean finished) {
}
