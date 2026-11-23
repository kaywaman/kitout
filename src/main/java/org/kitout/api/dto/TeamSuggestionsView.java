package org.kitout.api.dto;

import java.util.List;

/**
 * What kitout found on this machine to prefill "Build a team setup".
 *
 * @param requirements the rules "record this machine's tools" would write, as id, name and require
 * @param allowed      false when kitout's build requires the team's own file
 */
public record TeamSuggestionsView(String gitlabHost, String cloneFolder, List<String> composeFiles, List<String> envFiles,
                                  List<RequirementRuleView> requirements, boolean allowed) {

    public record RequirementRuleView(String id, String name, String require) {
    }
}
