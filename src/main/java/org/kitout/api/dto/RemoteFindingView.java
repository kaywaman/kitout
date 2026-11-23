package org.kitout.api.dto;

/** A remote URL with a token; only the clean URL is ever sent. */
public record RemoteFindingView(String serviceId, String serviceName, String remote, String key, String cleanUrl) {
}
