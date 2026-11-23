package org.kitout.api.dto;

import java.util.List;

/** @param added the repositories added as libraries, e.g. {@code acme/common/java-commons} */
public record AddedLibrariesView(List<String> added) {
}
