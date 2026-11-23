package org.kitout.api.dto;

import java.util.List;

/** @param bffs the group's entry points, started together once the other members are up */
public record GroupView(String name, List<String> members, List<String> bffs, boolean sequentialStart, boolean suggested) {
}
