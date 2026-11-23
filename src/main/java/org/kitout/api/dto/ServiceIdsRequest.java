package org.kitout.api.dto;

import java.util.List;

/** A selection of services; empty or missing means all known services. */
public record ServiceIdsRequest(List<String> serviceIds) {
}
