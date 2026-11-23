package org.kitout.api.dto;

import java.time.Instant;
import java.util.List;

/** Progress of a group start. */
public record GroupStartView(String group, Instant startedAt, List<String> messages, boolean finished) {
}
