package org.kitout.api.dto;

import java.time.Instant;
import java.util.List;

/** @param blocked true when a failed requirement stops building and starting */
public record RequirementsView(boolean configured, String team, boolean blocked, List<RequirementView> results, Instant checkedAt) {
}
