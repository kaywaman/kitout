package org.kitout.api.dto;

import java.util.List;

/**
 * The first-call walk-through: team file and its checks, machine, secrets, projects.
 *
 * @param defaultLocation the team file location kitout's build or environment suggests, for people; null if none
 * @param suggested       what the form to follow a team file starts with; null if nothing is known
 * @param presetProblem   why the team file set in kitout's build cannot be used; null if it can
 * @param ready           the machine meets the requirements, so projects may be loaded or cloned
 * @param complete        nothing left to do
 * @param canCreate       a team setup may be built here (no kitout.team.required)
 * @param editable        the team file in use is the one kitout wrote here: projects can be added to it
 * @param localOnly       the team file names no GitLab: nothing to clone
 */
public record SetupView(TeamStatusView team, String defaultLocation, List<FileCheckView> fileChecks, RequirementsView requirements,
                        List<String> missingSecrets, List<SetupProjectView> projects, String workspaceRoot, boolean ready, boolean complete,
                        boolean canCreate, boolean editable, boolean localOnly,
                        TeamSourceView suggested, String presetProblem) {
}
