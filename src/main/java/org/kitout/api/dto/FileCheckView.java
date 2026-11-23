package org.kitout.api.dto;

import java.util.List;

/** One check made on the team file; it passed when {@code problems} is empty. */
public record FileCheckView(String id, String name, boolean ok, List<String> problems) {
}
