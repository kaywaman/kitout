package org.kitout.application.workspace;

import org.kitout.application.config.ConfigRepository;
import org.kitout.domain.group.Group;
import org.kitout.domain.identity.RepoIdentity;
import org.kitout.domain.project.Project;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Groups: saved ones from the configuration, plus suggestions (one per GitLab namespace) for
 * namespaces that have no saved group yet. A suggestion becomes a saved group when it is saved,
 * edited or not; saved groups may span namespaces.
 */
@ApplicationScoped
public class GroupService {

    private final ConfigRepository config;
    private final WorkspaceService workspace;

    public GroupService(ConfigRepository config, WorkspaceService workspace) {
        this.config = config;
        this.workspace = workspace;
    }

    public List<Group> groups() {
        List<Group> saved = config.load().groups();
        Set<String> savedNames = saved.stream().map(Group::name).collect(Collectors.toSet());
        List<Group> result = new ArrayList<>(saved);
        suggestions(workspace.projects()).stream().filter(g -> !savedNames.contains(g.name())).forEach(result::add);
        return result;
    }

    public Group require(String name) {
        return groups().stream().filter(g -> g.name().equals(name)).findFirst()
                .orElseThrow(() -> new NoSuchElementException("Unknown group '" + name + "'"));
    }

    public Group save(Group group) {
        Set<String> known = workspace.projects().stream().map(Project::id).collect(Collectors.toSet());
        List<String> unknown = group.members().stream().filter(m -> !known.contains(m)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown services: " + String.join(", ", unknown));
        }
        Group saved = new Group(group.name().trim(), group.members(), group.bffs(), group.sequentialStart(), false);
        config.update(c -> c.withGroup(saved));
        return saved;
    }

    public void delete(String name) {
        config.update(c -> c.withGroups(c.groups().stream().filter(g -> !g.name().equals(name)).toList()));
    }

    /** One group per namespace; the members that route to others are its BFFs. */
    static List<Group> suggestions(List<Project> projects) {
        Map<String, List<Project>> byNamespace = new LinkedHashMap<>();
        for (Project p : projects) {
            p.identity().map(RepoIdentity::namespace)
                    .ifPresent(ns -> byNamespace.computeIfAbsent(ns, k -> new ArrayList<>()).add(p));
        }
        List<Group> result = new ArrayList<>();
        byNamespace.forEach((namespace, members) -> {
            List<String> bffs = members.stream().filter(Project::looksLikeBff).map(Project::id).toList();
            List<String> ids = members.stream().map(Project::id).toList();
            result.add(new Group(groupName(namespace), ids, bffs, false, true));
        });
        return result;
    }

    static String groupName(String namespace) {
        return namespace.toLowerCase().replaceAll("[^a-z0-9_-]+", "-");
    }
}
