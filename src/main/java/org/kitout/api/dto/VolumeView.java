package org.kitout.api.dto;

/** A host folder mounted into a service's container: {@code hostPath} on this machine ({@code ~} allowed), {@code containerPath} inside. */
public record VolumeView(String hostPath, String containerPath, boolean readOnly) {
}
