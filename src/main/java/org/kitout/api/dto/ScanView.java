package org.kitout.api.dto;

import java.util.List;

/** @param outsideTeam folders with Quarkus projects the team file does not name; not added */
public record ScanView(List<ServiceView> found, List<String> added, List<String> removed, List<String> outsideTeam) {
}
