package org.kitout.cli.commands;

import org.kitout.application.git.GitSyncService;
import org.kitout.application.git.GitSyncService.PullOutcome;
import org.kitout.application.git.GitSyncService.PullStatus;
import org.kitout.application.git.GitSyncService.SyncStatus;
import org.kitout.application.workspace.GroupService;
import org.kitout.application.workspace.WorkspaceService;
import org.kitout.cli.Console;
import org.kitout.domain.group.Group;
import org.kitout.domain.project.Project;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "pull",
        description = {"Bring the local checkouts up to date with the git server (git pull --ff-only).",
                "Checkouts with uncommitted changes, without an upstream branch or diverged from origin are left alone."})
public class PullCommand implements Callable<Integer> {

    @CommandLine.Parameters(arity = "0..*", paramLabel = "SERVICE", description = "Services to pull. Default: all, or the --group.")
    List<String> services = new ArrayList<>();

    @CommandLine.Option(names = {"-g", "--group"}, description = "Only this group's services.")
    String group;

    @CommandLine.Option(names = "--check", description = "Only fetch and show what a pull would bring; change nothing.")
    boolean check;

    @Inject
    GitSyncService sync;

    @Inject
    WorkspaceService workspace;

    @Inject
    GroupService groups;

    @Override
    public Integer call() {
        List<Project> projects = (services.isEmpty() ? workspace.projects() : services.stream().map(workspace::require).toList())
                .stream().filter(Project::hasCheckout).toList();   // image-only services have no checkout
        if (group != null) {
            Group g = groups.require(group);
            projects = projects.stream().filter(p -> g.members().contains(p.id())).toList();
        }
        if (projects.isEmpty()) {
            Console.out("No services to pull. Run 'kitout scan' first.");
            return Console.OK;
        }
        if (check) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"SERVICE", "BRANCH", "BEHIND", "AHEAD", "LOCAL CHANGES", "NOTE"});
            List<SyncStatus> statuses = sync.check(projects);
            for (SyncStatus s : statuses) {
                rows.add(new String[] {s.name(), s.branch(), String.valueOf(s.behind()), String.valueOf(s.ahead()),
                        s.localChanges() ? "yes" : "", s.problem() == null ? "" : s.problem()});
            }
            Console.table(rows);
            long pullable = statuses.stream().filter(SyncStatus::pullable).count();
            Console.out(pullable == 0 ? "Nothing to pull." : pullable + " checkout(s) can be pulled: run 'kitout pull' without --check.");
            return Console.OK;
        }
        List<PullOutcome> outcomes = sync.pull(projects);
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"SERVICE", "RESULT", "DETAIL"});
        outcomes.forEach(o -> rows.add(new String[] {o.name(), o.status().name(), o.message()}));
        Console.table(rows);
        long updated = outcomes.stream().filter(o -> o.status() == PullStatus.UPDATED).count();
        boolean failed = outcomes.stream().anyMatch(o -> o.status() == PullStatus.FAILED);
        Console.out(updated + " updated. Services running from a local build pick up new code after a rebuild and restart.");
        return failed ? Console.FAILED : Console.OK;
    }
}
