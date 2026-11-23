package org.kitout.api.dto;

/**
 * One compose file as an environment.
 *
 * @param running containers of this environment that run now
 * @param total   containers of this environment that exist (running or stopped); 0 after a down
 * @param lastJob the latest up or down of this environment since kitout started; null if none
 * @param picked  the developer picked this file by hand (it can be forgotten); false if found in a folder
 * @param files   all files in {@code -f} order (the first is {@code file}); more than one for team environments with overrides
 * @param team    defined by the team file
 * @param autoStart kitout brings it up on start and before services start
 * @param missing team files not on this machine yet (repository not cloned); up is refused until they are
 * @param downloaded the repository is not cloned: kitout uses a copy of the files read from GitLab
 */
public record ComposeEnvironmentView(String id, String name, String file, String folder, int running, int total,
                                     ComposeJobView lastJob, boolean picked, java.util.List<String> files, java.util.List<String> profiles,
                                     boolean team, String description, java.util.List<String> missing, boolean autoStart,
                                     boolean downloaded) {
}
