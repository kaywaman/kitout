package org.kitout.api.dto;

/**
 * Where kitout keeps its files, so the UI can show them instead of the developer guessing.
 *
 * @param logFile    kitout's own log, also streamed by {@code GET /api/kitout/log}
 * @param configFile the settings
 * @param stateDir   run state, service logs and build logs
 */
public record KitoutInfoView(String logFile, String configFile, String stateDir) {
}
