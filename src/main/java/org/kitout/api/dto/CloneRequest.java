package org.kitout.api.dto;

import java.util.List;

public record CloneRequest(List<CloneItemRequest> items) {
}
