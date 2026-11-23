package org.kitout.cli.commands;

import org.kitout.application.clone.CloneService;
import org.kitout.application.clone.CloneService.Candidate;
import org.kitout.application.clone.CloneService.Selection;
import org.kitout.application.env.EnvironmentService;
import org.kitout.application.team.SetupService;
import org.kitout.application.team.TeamService;
import org.kitout.application.team.TeamService.Status;
import org.kitout.cli.Console;
import jakarta.inject.Inject;
import picocli.CommandLine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/** {@code kitout team}: the team file kitout follows (services, groups, compose, variables, machine requirements). */
@CommandLine.Command(name = "team",
        description = {"The team file kitout follows: services, groups, compose environments, variables and machine requirements.",
                "Without a subcommand: show where it comes from and whether it is valid."},
        subcommands = {TeamCommand.Use.class, TeamCommand.Refresh.class, TeamCommand.Forget.class, TeamCommand.Secret.class, TeamCommand.AddLibrary.class,
                TeamCommand.Clone.class, TeamCommand.Load.class, TeamCommand.Pull.class, TeamCommand.Create.class, TeamCommand.Add.class, TeamCommand.Export.class, TeamCommand.Init.class})
public class TeamCommand implements Callable<Integer> {

    @Inject
    SetupService setup;

    @Override
    public Integer call() {
        return show(setup);
    }

    /** Every check at once: the file, the machine, your secrets, the team's repositories, and what to do next. */
    static int show(SetupService setupService) {
        SetupService.Setup s = setupService.status(false);
        Status t = s.team();
        if (!t.configured()) {
            Console.out("No team file is in use. Your lead can tell you where it is; then run: kitout team use <location>");
            Console.out("  e.g. kitout team use https://gitlab.example.com/acme/platform/kitout-team");
            return Console.OK;
        }
        Console.out("Team file: " + t.location() + (t.version().isBlank() ? "" : " @ " + abbreviate(t.version())));
        for (SetupService.FileCheck c : s.fileChecks()) {
            Console.out("  " + (c.ok() ? Console.ok("✓") : Console.fail("✗")) + " " + c.name());
            c.problems().forEach(p -> Console.out("      " + p));
        }
        t.warnings().forEach(w -> Console.out("  " + Console.warn("!") + " " + w));
        if (t.setup().isEmpty()) {
            Console.out("");
            Console.out("Fix the team file (or ask whoever maintains it), then: kitout team refresh");
            return Console.FAILED;
        }
        Console.out("");
        if (!s.requirements().results().isEmpty()) {
            org.kitout.cli.RequirementsGate.printOut(s.requirements());
            Console.out("");
        }
        if (!s.missingSecrets().isEmpty()) {
            Console.out("Your secrets:");
            s.missingSecrets().forEach(n -> Console.out("  " + Console.warn("!") + " " + n + " not entered: kitout team secret "
                    + n.replace(" (", " --service ").replace(")", "")));
            Console.out("");
        }
        Console.out("Projects of the team (" + s.workspaceRoot() + "):");
        for (SetupService.Project p : s.projects()) {
            String state = switch (p.state()) {
                case CONFIGURED -> Console.ok("✓") + " loaded";
                case LOCAL -> Console.warn("•") + " on this machine, not loaded";
                case MISSING -> Console.warn("•") + " not on this machine";
                case NOT_LOADABLE -> Console.fail("✗") + " cannot be loaded";
                case REMOVED -> Console.warn("•") + " removed by you";
            };
            Console.out("  " + state + "  " + p.repo() + (!p.service()
                    ? "  (" + kindLabel(p.kind()) + (p.description() == null || p.description().isBlank() ? "" : ": " + p.description()) + ")  " + p.path() : ""));
            if (p.kind().equals("service") && p.description() != null) {
                Console.out("      " + p.description());
            }
        }
        Console.out("");
        if (s.complete()) {
            Console.out(Console.ok("✓") + " Setup complete. Start working: kitout, or kitout up <group>");
        } else if (!s.ready()) {
            Console.out("Next: fix the requirements above, then: kitout team");
        } else {
            boolean missing = s.projects().stream().anyMatch(p -> p.state() == SetupService.ProjectState.MISSING);
            boolean local = s.projects().stream().anyMatch(p -> p.state() == SetupService.ProjectState.LOCAL);
            if (missing) {
                Console.out("Next: kitout team clone      (clones only the repositories the team file names)");
            }
            if (local || missing) {
                Console.out((missing ? "Then" : "Next") + ": kitout team load       (loads the team's projects found on this machine)");
            }
            if (!s.missingSecrets().isEmpty()) {
                Console.out("And enter your secrets (see above).");
            }
        }
        return Console.OK;
    }

    private static String abbreviate(String version) {
        return version.matches("[0-9a-f]{40}") ? version.substring(0, 8) : version;
    }

    @CommandLine.Command(name = "use", description = {"Follow a team file in a git repository, named like a team compose file:",
            "  kitout team use --repo acme/infrastructure --path kitout/kitout-team.json [--ref main] [--host github.com]",
            "or a file on this machine: kitout team use --file ~/team/kitout-team.json.",
            "A link to the file (…/-/blob/<ref>/<path>) still works as LOCATION."})
    public static class Use implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "LOCATION", arity = "0..1",
                description = "Older form: a GitLab or GitHub link, or a local path; use --repo and --path instead")
        String location;

        @CommandLine.Option(names = "--repo", paramLabel = "GROUP/PROJECT", description = "The repository with the team file")
        String repo;

        @CommandLine.Option(names = "--path", paramLabel = "PATH", description = "The file's path in the repository (default kitout-team.json)")
        String path;

        @CommandLine.Option(names = "--ref", paramLabel = "BRANCH", description = "Branch or tag (default main)")
        String ref;

        @CommandLine.Option(names = "--host", paramLabel = "HOST", description = "The git host (default: the one in Settings, Git)")
        String host;

        @CommandLine.Option(names = "--file", paramLabel = "FILE", description = "A team file on this machine")
        String file;

        @Inject
        org.kitout.application.config.ConfigRepository config;

        @Inject
        TeamService team;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            int given = (repo != null ? 1 : 0) + (file != null ? 1 : 0) + (location != null ? 1 : 0);
            if (given != 1) {
                Console.err("Give either --repo (with --path) or --file, e.g. kitout team use --repo acme/infrastructure --path kitout/kitout-team.json");
                return 2; // picocli's usage error
            }
            if (repo != null) {
                team.use(org.kitout.application.config.KitoutConfig.TeamSettings.of(host != null ? host : config.load().gitlab().host(), repo, path, ref));
            } else if (file != null) {
                team.use(org.kitout.application.config.KitoutConfig.TeamSettings.ofFile(file));
            } else {
                team.use(location);
            }
            return show(setup);
        }
    }

    @CommandLine.Command(name = "refresh", description = "Read the team file again now (kitout also does this every hour).")
    public static class Refresh implements Callable<Integer> {

        @Inject
        TeamService team;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            team.refresh();
            return show(setup);
        }
    }

    @CommandLine.Command(name = "forget", description = "Stop following the team file. Your own settings stay.")
    public static class Forget implements Callable<Integer> {

        @Inject
        TeamService team;

        @Override
        public Integer call() {
            team.forget();
            Console.out("kitout no longer follows a team file.");
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "secret", description = {"Enter your value for a secret the team file asks for (e.g. MONGO_PASSWORD).",
            "Read from the terminal without echo, or from stdin; kept in the keyring."})
    public static class Secret implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "NAME")
        String name;

        @CommandLine.Option(names = {"-s", "--service"}, description = "For a secret one service asks for.")
        String service;

        @Inject
        EnvironmentService environment;

        @Override
        public Integer call() throws IOException {
            if (name.contains("=")) {
                Console.err("Do not put a secret on the command line (it stays in your shell history). Run: kitout team secret "
                        + name.substring(0, name.indexOf('=')));
                return Console.FAILED;
            }
            String value = System.console() != null
                    ? new String(System.console().readPassword("Your %s: ", name))
                    : new java.io.BufferedReader(new java.io.InputStreamReader(System.in, java.nio.charset.StandardCharsets.UTF_8)).readLine();
            environment.setTeamSecret(service == null ? EnvironmentService.SHARED : service, name, value);
            Console.out(Console.ok("✓") + " " + name + " is in your keyring.");
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "clone", description = "Clone the team's repositories that are not on this machine yet.")
    public static class Clone implements Callable<Integer> {

        @CommandLine.Option(names = "--light", description = "Shallow clones of the default branch only.")
        boolean light;

        @Inject
        TeamService team;

        @Inject
        CloneService clones;

        @Inject
        SetupService setupService;

        @Override
        public Integer call() {
            if (!setupService.status(false).ready()) {
                Console.err("First a valid team file and a machine that meets its requirements: kitout team");
                return Console.REQUIREMENTS;
            }
            var setup = team.status().setup().orElseThrow();
            List<Candidate> missing = clones.repositories(setup.repositories()).stream().filter(c -> !c.alreadyLocal()).toList();
            if (missing.isEmpty()) {
                Console.out(Console.ok("✓") + " All " + setup.repositories().size() + " repositories of the team are on this machine.");
                return Console.OK;
            }
            CloneService.Job job = clones.cloneAndWait(missing.stream().map(c -> new Selection(c.fullPath(), light)).toList());
            job.items().forEach(i -> Console.out((i.status() == CloneService.ItemStatus.FAILED ? Console.fail("✗") : Console.ok("✓"))
                    + " " + i.fullPath() + ": " + i.message()));
            var loaded = setupService.loadLocal();
            Console.out("Loaded " + loaded.found().size() + " of the team's projects (" + loaded.added().size() + " new).");
            return job.items().stream().anyMatch(i -> i.status() == CloneService.ItemStatus.FAILED) ? Console.FAILED : Console.OK;
        }
    }

    @CommandLine.Command(name = "pull", description = {"Pull the team's repositories that are not services (compose files, common Java and UI libraries):",
            "fast-forward only, never over local changes. Services: kitout pull."})
    public static class Pull implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "REPO", description = "One repository; without it, all of them.")
        String repo;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            int failed = 0;
            for (SetupService.Project p : setup.repositories()) {
                if (repo != null && !p.repo().equalsIgnoreCase(repo)) {
                    continue;
                }
                if (p.state() == SetupService.ProjectState.MISSING) {
                    Console.out(Console.warn("•") + " " + p.repo() + ": not on this machine (kitout team clone)");
                    continue;
                }
                var o = setup.pullRepository(p.repo());
                boolean bad = o.status() == org.kitout.application.git.GitSyncService.PullStatus.FAILED;
                failed += bad ? 1 : 0;
                Console.out((bad ? Console.fail("✗") : Console.ok("✓")) + " " + p.repo() + ": " + o.message());
            }
            return failed > 0 ? Console.FAILED : Console.OK;
        }
    }

    @CommandLine.Command(name = "create", description = {"Build a team setup from scratch when there is no kitout-team.json yet:",
            "your infrastructure compose file(s), the git host if the projects are on a server, and this machine's tools as",
            "the team's requirements. Written to ~/.config/kitout/kitout-team.local.json and followed right away.",
            "Without options it shows what kitout found on this machine."})
    public static class Create implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", arity = "0..1", paramLabel = "TEAM", description = "The team's name, e.g. acme-shop.")
        String name;

        @CommandLine.Option(names = "--compose", description = "A compose file with the infrastructure (repeatable).")
        List<String> compose;

        @CommandLine.Option(names = "--env-file", description = "A .env to take variables from; passwords and keys go to the keyring.")
        String envFile;

        @CommandLine.Option(names = {"--git", "--gitlab"}, description = "The git host (github.com, gitlab.example.com, ...), if the projects are on a server. Without it: this machine only.")
        String gitlab;

        @CommandLine.Option(names = "--clone-folder", description = "Where the team's repositories go (default ~/work).")
        String cloneFolder;

        @CommandLine.Option(names = "--registry", description = "The container registry, only if CI/CD publishes service images.")
        String registry;

        @CommandLine.Option(names = "--no-requirements", description = "Do not record this machine's tools as the team's requirements.")
        boolean noRequirements;

        @Inject
        org.kitout.application.team.TeamCreator creator;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            if (name == null) {
                var s = creator.suggestions();
                Console.out("Found on this machine:");
                Console.out("  Git host:       " + (s.gitlabHost().isEmpty() ? "(none)" : s.gitlabHost()));
                s.composeFiles().forEach(f -> Console.out("  compose file:   " + f));
                s.envFiles().forEach(f -> Console.out("  .env:           " + f));
                s.requirements().forEach(r -> Console.out("  requirement:    " + r.get("name") + " " + r.get("require")));
                Console.out("");
                Console.out("Create it, e.g.: kitout team create acme-shop --compose " + (s.composeFiles().isEmpty() ? "~/infra/docker-compose-dev.yml"
                        : s.composeFiles().get(0)) + (s.gitlabHost().isEmpty() ? "" : " --gitlab " + s.gitlabHost()));
                return Console.OK;
            }
            creator.create(new org.kitout.application.team.TeamCreator.Draft(name, gitlab, cloneFolder, registry, null,
                    compose == null ? List.of() : compose, envFile, !noRequirements));
            Console.out(Console.ok("✓") + " Team setup written to " + creator.file() + " and followed.");
            Console.out("  Add your services as they come: kitout team add <folder>. Share it later: kitout team export");
            Console.out("");
            return show(setup);
        }
    }

    @CommandLine.Command(name = "add", description = {"Add the projects in these folders to the team setup kitout wrote (kitout team create),",
            "then load them. A team's shared file changes by merge request instead."})
    public static class Add implements Callable<Integer> {

        @CommandLine.Parameters(arity = "1..*", paramLabel = "FOLDER")
        List<String> folders;

        @Inject
        org.kitout.application.team.TeamCreator creator;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            creator.addServices(folders).forEach(r -> Console.out(Console.ok("✓") + " " + r + " is a service of the team"));
            setup.loadLocal();
            return show(setup);
        }
    }

    @CommandLine.Command(name = "add-library", description = {"Add the git checkouts in these folders to the team setup kitout wrote as common",
            "libraries: cloned and pulled with the team's repositories, never started as services."})
    public static class AddLibrary implements Callable<Integer> {

        @CommandLine.Parameters(arity = "1..*", paramLabel = "FOLDER")
        List<String> folders;

        @CommandLine.Option(names = "--ui", description = "A UI library (default: a Java library).")
        boolean ui;

        @Inject
        org.kitout.application.team.TeamCreator creator;

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            String kind = ui ? "ui-library" : "java-library";
            creator.addLibraries(folders, kind).forEach(r -> Console.out(Console.ok("✓") + " " + r + " is a " + kindLabel(kind) + " of the team"));
            return show(setup);
        }
    }

    @CommandLine.Command(name = "export", description = {"Print the team setup kitout wrote, to commit as kitout-team.json in a repository",
            "the team shares; then everyone runs: kitout team use <that repository>."})
    public static class Export implements Callable<Integer> {

        @Inject
        org.kitout.application.team.TeamCreator creator;

        @Override
        public Integer call() {
            System.out.print(creator.export());
            return Console.OK;
        }
    }

    @CommandLine.Command(name = "load", description = {"Load the team's projects that are already on this machine",
            "(in the clone folder and your workspace folders). Projects the team file does not name are left out."})
    public static class Load implements Callable<Integer> {

        @Inject
        SetupService setup;

        @Override
        public Integer call() {
            var r = setup.loadLocal();
            r.found().forEach(p -> Console.out(Console.ok("✓") + " " + p.id() + "  " + p.path()));
            r.outsideTeam().forEach(p -> Console.out("- " + p + " (not in the team file, left out)"));
            Console.out(r.found().size() + " loaded, " + r.added().size() + " new.");
            return show(setup) == Console.OK ? Console.OK : Console.FAILED;
        }
    }

    @CommandLine.Command(name = "init", description = {"Write a starter team file into a folder (e.g. a new repository):",
            "kitout-team.json with <<REPLACE: …>> placeholders, its JSON schema, and a CODEOWNERS example."})
    public static class Init implements Callable<Integer> {

        @CommandLine.Parameters(index = "0", paramLabel = "DIR", defaultValue = ".")
        Path dir;

        @Override
        public Integer call() throws IOException {
            Files.createDirectories(dir);
            int written = 0;
            for (String[] f : List.of(new String[] {"kitout-team.example.json", "kitout-team.json"},
                    new String[] {"kitout-team.schema.json", "kitout-team.schema.json"},
                    new String[] {"CODEOWNERS.example", "CODEOWNERS.example"})) {
                Path target = dir.resolve(f[1]);
                if (Files.exists(target)) {
                    Console.out(Console.warn("!") + " " + target + " exists; left as it is");
                    continue;
                }
                try (InputStream in = TeamCommand.class.getResourceAsStream("/team/" + f[0])) {
                    if (in == null) {
                        throw new IllegalStateException("The starter files are missing from this kitout build");
                    }
                    Files.copy(in, target);
                    written++;
                }
                Console.out(Console.ok("✓") + " " + target);
            }
            if (written > 0) {
                Console.out("");
                Console.out("Replace every <<REPLACE: …>> in kitout-team.json (kitout refuses the file while one is left), commit it,");
                Console.out("then every developer runs: kitout team use <the project's URL>");
            }
            return Console.OK;
        }
    }

    /** How a repository that is not a service is named in the CLI. */
    static String kindLabel(String kind) {
        return switch (kind) {
            case "java-library" -> "Java library";
            case "ui-library" -> "UI library";
            default -> "not a service";
        };
    }
}
