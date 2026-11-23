package org.kitout.api.dto;

import java.util.List;

/** @param added repositories now in the team setup; @param loaded services kitout loaded right after */
public record AddedServicesView(List<String> added, List<String> loaded) {
}
