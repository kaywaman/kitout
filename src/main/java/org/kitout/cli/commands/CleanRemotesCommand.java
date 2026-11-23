package org.kitout.cli.commands;

import org.kitout.application.git.RemoteCleanupService;
import org.kitout.application.git.RemoteCleanupService.Finding;
import org.kitout.application.git.RemoteCleanupService.Outcome;
import org.kitout.application.git.RemoteCleanupService.Status;
import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.cli.Console;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "clean-remotes",
        description = "Find tokens inside git remote URLs and remove them (dry run unless --apply).")
public class CleanRemotesCommand implements Callable<Integer> {

    @CommandLine.Option(names = {"-g", "--group"}, description = "Only this group's repositories. Default: all known.")
    String group;

    @CommandLine.Option(names = "--apply", description = "Rewrite the remotes, test each repository, roll back on failure.")
    boolean apply;

    @Inject
    RemoteCleanupService cleanup;

    @Inject
    WorkspaceService workspace;

    @Inject
    GroupService groups;

    @Override
    public Integer call() {
        List<Project> projects = workspace.projects().stream().filter(Project::hasCheckout).toList();
        if (group != null) {
            Group g = groups.require(group);
            projects = projects.stream().filter(p -> g.members().contains(p.id())).toList();
        }
        List<Finding> findings = cleanup.find(projects);
        if (findings.isEmpty()) {
            Console.out("No tokens in remote URLs.");
            return Console.OK;
        }
        findings.forEach(f -> Console.out(f.describe()));
        if (!apply) {
            Console.out(findings.size() + " remote URL(s) contain credentials. Store your new token first, then run with --apply.");
            return Console.FAILED;
        }
        List<Outcome> outcomes = cleanup.apply(projects);
        outcomes.forEach(o -> Console.out(o.status() + "  " + o.serviceName() + ": " + o.message()));
        boolean allClean = outcomes.stream().allMatch(o -> o.status() == Status.CLEANED);
        Console.out(allClean
                ? "Done. Now revoke the old token on the git server."
                : "Some repositories were not changed; see above. Revoke the old token only when all are clean.");
        return allClean ? Console.OK : Console.FAILED;
    }
}
