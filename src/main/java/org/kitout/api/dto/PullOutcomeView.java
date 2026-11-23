package org.kitout.api.dto;

/** @param status UPDATED, UP_TO_DATE, SKIPPED or FAILED */
public record PullOutcomeView(String serviceId, String serviceName, String status, String message, int newCommits) {
}
