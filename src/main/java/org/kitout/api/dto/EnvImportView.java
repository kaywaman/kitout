package org.kitout.api.dto;

import java.util.List;

public record EnvImportView(List<String> added, List<String> secrets, List<String> skipped) {
}
