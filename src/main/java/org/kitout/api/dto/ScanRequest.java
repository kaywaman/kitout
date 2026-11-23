package org.kitout.api.dto;

import java.util.List;

/** Folders to scan; empty means the configured workspace roots. */
public record ScanRequest(List<String> roots) {
}
