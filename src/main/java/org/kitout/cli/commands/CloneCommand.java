package org.kitout.cli.commands;

import org.kitout.application.clone.CloneService;
import org.kitout.application.clone.CloneService.Candidate;
import org.kitout.application.clone.CloneService.ItemStatus;
import org.kitout.application.clone.CloneService.Job;
import org.kitout.application.clone.CloneService.Selection;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "clone", description = "Clone a GitLab group's or a GitHub organization's repositories into the workspace, then scan them.")
public class CloneCommand implements Callable<Integer> {

    @CommandLine.Parameters(index = "0", paramLabel = "GROUP", description = "GitLab group path (e.g. acme/shop), or a GitHub organization or user (e.g. acme).")
    String group;

    @CommandLine.Parameters(index = "1..*", arity = "0..*", paramLabel = "PROJECT",
            description = "Project names to clone. Default: list them; with --all: every project not yet cloned.")
    List<String> projects = new ArrayList<>();

    @CommandLine.Option(names = "--all", description = "Clone every project of the group that is missing locally.")
    boolean all;

    @CommandLine.Option(names = "--light", description = "Blob-less clones: enough for services you only run from images.")
    boolean light;

    @Inject
    CloneService clones;

    @Override
    public Integer call() {
        List<Candidate> candidates = clones.candidates(group);
        if (!all && projects.isEmpty()) {
            List<String[]> rows = new ArrayList<>();
            rows.add(new String[] {"PROJECT", "LOCAL", "TARGET"});
            candidates.forEach(c -> rows.add(new String[] {c.name(), c.alreadyLocal() ? "yes" : "no", c.target().toString()}));
            Console.table(rows);
            Console.out("Clone with: kitout clone " + group + " --all   or   kitout clone " + group + " <project>...");
            return Console.OK;
        }
        List<Selection> selection = candidates.stream()
                .filter(c -> !c.alreadyLocal())
                .filter(c -> all || projects.contains(c.name()) || projects.contains(c.fullPath()))
                .map(c -> new Selection(c.fullPath(), light))
                .toList();
        if (selection.isEmpty()) {
            Console.out("Nothing to clone: the selected projects are already local.");
            return Console.OK;
        }
        Console.out("Cloning " + selection.size() + " projects...");
        Job job = clones.cloneAndWait(selection);
        job.items().forEach(i -> Console.out(i.status() + "  " + i.fullPath() + "  " + i.message()));
        return job.items().stream().anyMatch(i -> i.status() == ItemStatus.FAILED) ? Console.FAILED : Console.OK;
    }
}
