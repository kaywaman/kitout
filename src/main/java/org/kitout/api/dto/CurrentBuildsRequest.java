package org.kitout.api.dto;

import java.util.List;

public record CurrentBuildsRequest(List<Item> items) {

    public record Item(String serviceId, String mode) {
    }
}
