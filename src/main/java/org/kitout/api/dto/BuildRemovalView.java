package org.kitout.api.dto;

import java.util.List;

/** @param removed ids taken off the list; @param kept one sentence per build that stayed, with the reason */
public record BuildRemovalView(List<String> removed, List<String> kept) {
}
