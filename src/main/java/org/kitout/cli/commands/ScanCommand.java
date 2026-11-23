package org.kitout.cli.commands;

import org.kitout.application.workspace.WorkspaceService;
import org.kitout.application.workspace.WorkspaceService.ScanResult;
import org.kitout.cli.Console;
import org.kitout.domain.identity.RepoIdentity;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

@CommandLine.Command(name = "scan", description = "Find projects in folders (Quarkus, Spring Boot, Java apps, Node) and remember them.")
public class ScanCommand implements Callable<Integer> {

    @CommandLine.Parameters(arity = "0..*", paramLabel = "FOLDER",
            description = "Folders to scan. Default: the folders scanned before.")
    List<String> roots = new ArrayList<>();

    @Inject
    WorkspaceService workspace;

    @Override
    public Integer call() {
        ScanResult result = workspace.scan(roots);
        if (!result.outsideTeam().isEmpty()) {
            Console.out("Not loaded, the team file does not name them: " + String.join(", ", result.outsideTeam()));
        }
        if (result.found().isEmpty()) {
            Console.out("No projects found. Give a folder: kitout scan ~/work" + (result.outsideTeam().isEmpty() ? "" : ", or clone the team's: kitout team clone"));
            return Console.OK;
        }
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"SERVICE", "PORT", "GITLAB PATH", "FOLDER"});
        result.found().forEach(p -> rows.add(new String[] {
                p.id(), String.valueOf(p.devPort()), p.identity().map(RepoIdentity::fullPath).orElse("-"), p.path().toString()}));
        Console.table(rows);
        Console.out(result.found().size() + " projects, " + result.added().size() + " new, " + result.removed().size() + " gone.");
        return Console.OK;
    }
}
