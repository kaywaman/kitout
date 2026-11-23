package org.kitout.api.dto;

import java.time.Instant;

/** @param action UP or DOWN; @param status RUNNING, SUCCEEDED or FAILED */
public record ComposeJobView(String id, String environment, String action, String status, Instant startedAt,
                             Instant finishedAt, Integer exitCode) {
}
