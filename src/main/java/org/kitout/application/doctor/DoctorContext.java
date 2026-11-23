package org.kitout.application.doctor;

import org.kitout.application.config.KitoutConfig;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import org.kitout.domain.run.RunMode;

import java.util.List;
import java.util.Optional;

/**
 * What a check may look at: the configuration, the selected group (if any) and its members as
 * read from disk. Built once per doctor run and shared by all checks.
 *
 * @param members the group's members, or all known projects when no group is selected
 */
public record DoctorContext(KitoutConfig config, Optional<Group> group, List<Project> members) {

    public DoctorContext {
        members = List.copyOf(members);
    }

    public RunMode runMode(Project project) {
        return config.serviceSettings(project.id()).runMode();
    }

    /** The selected group's BFFs, as read from disk; empty without a group or BFF. */
    public List<Project> bffs() {
        List<String> ids = group.map(Group::bffs).orElse(List.of());
        return members.stream().filter(m -> ids.contains(m.id())).toList();
    }

    /** A git host is set (Settings, Git); any kind of server. */
    public boolean hasGitLab() {
        return config.gitlab().isConfigured();
    }

    /** The git host is a GitLab: its API (token check) applies. */
    public boolean isGitLab() {
        return config.gitlab().isConfigured() && config.gitlab().kind() == org.kitout.domain.git.GitHost.GITLAB;
    }
}
