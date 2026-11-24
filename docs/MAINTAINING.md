# Maintaining and improving kitout

How to change kitout safely. Read [ARCHITECTURE.md](ARCHITECTURE.md) first if you have not: it
explains the layers this guide refers to.

## Contents

1. [Daily workflow](#daily-workflow)
2. [Where things are](#where-things-are)
3. [Conventions](#conventions)
4. [Recipes](#recipes): doctor check, run mode, endpoint and UI, setting, CLI command, native build error, migration
5. [Tests](#tests)
6. [Upgrades](#upgrades)
7. [Releasing](#releasing)
8. [Troubleshooting](#troubleshooting)
9. [Known limitations and next steps](#known-limitations-and-next-steps)

---

## Daily workflow

```bash
./mvnw quarkus:dev                    # daemon on :7879, UI via Vite, live reload
./mvnw test                           # Java tests (domain, services with real git/processes, API)
cd src/main/webui && npm test         # UI logic tests
cd src/main/webui && npm run typecheck
```

Dev mode uses your real `~/.config/kitout` and `~/.local/state/kitout`. To try things without touching
them: `KITOUT_HOME=/tmp/kitout-play ./mvnw quarkus:dev`.

To try the CLI from a build: `kitout status` (the command `./install.sh` adds), or
`java -jar target/quarkus-app/quarkus-run.jar status`.

To build the packaged app (JVM mode, UI included): `./install.sh` (`--test` runs every test too). It
refreshes `openapi.json` on every build (`-Dkitout.updateApi=true`), so the UI always compiles against the
current API, and runs `npm run build` itself, so a UI error shows its file and line. To start it and open
the browser: `./start.sh`, which never builds (it opens the browser on a kitout that already runs).

## Where things are

```
src/main/java/org/kitout/
  domain/            rules without I/O; start here for logic changes
    identity/        RepoIdentity, RemoteUrlParser, Redactor
    config/          QuarkusConfig (profiles, placeholders), ConfigFileParser, EndpointExtractor,
                     SpringBootConfig (port, actuator, infrastructure), NodePackage (scripts, port guess)
    project/         Project (one service: type, port, health, scripts, image), ProjectType (run modes, capabilities), BuildTool
    group/           Group: members, BFFs (several), start tiers
    run/             RunMode, ServiceState, LaunchPlanner (LABEL, LEGACY_LABEL), LaunchSpec,
                     DockerOptions (extra docker run options: split without a shell, refused options; --mount values)
    build/           BuildRequest, BuildCommandFactory, NativeBuildErrorExplainer
    routing/         BffRoutingAnalyzer, PortMap
    clone/           CloneTarget (safe paths)
    git/             GitHost (GitLab, GitHub or any git server: guessed or set; default registry, clone URLs)
    docker/          ComposeFile (environment from file name), Container (a docker ps line)
    run/ImageRef, ImageTags, ImagePath, EnvNames, DotEnv, ImageRetention  image references, tag order (no latest), image path patterns, variable names, .env files
    team/            TeamSetup (the team file as records), TeamSetupParser (validation, placeholders, credentials),
                     VersionRule (24.04, 21.*, >=3.9.0 <4.0.0), JavaVendor
    doctor/          CheckResult, Severity
  application/       use cases; one package per area
    KitoutPaths
    port/            interfaces the use cases need from the outside world
    config/          KitoutConfig (+ settings records), ConfigRepository
    workspace/       ProjectReader, WorkspaceService (scan, preview), GroupService,
                     ServiceAdminService (remove, remove all, run mode for many), FolderBrowser
    doctor/          DoctorService, DoctorCheck, DoctorContext; checks/ one class per check
    run/             ServiceSupervisor (incl. the health check switch, host folders), GroupRunner, LogService, DockerCli,
                     ArtifactLocator, RunState, ServiceOptions (volumes, docker options, health check: checked and saved)
    build/           BuildService, BuildJob
    clone/           CloneService
    git/             GitCli, CredentialService, GitEnvironment, RemoteCleanupService, GitSyncService (check, pull), SelfCommand,
                     RepositoryFiles + GitFileReader (one file of a repository without a clone: GitLab API or git)
    docker/          ComposeService (environments, up/down jobs, docker ps), ComposeJob, TeamComposeDownloads
    registry/        RegistryService (login, tag list, choose tag), RegistryLogins (logged in? Docker's store, then Docker)
    env/             EnvironmentService (shared and per-service variables, secrets in the keyring)
    reset/           ResetService (preview and reset: settings, logs, builds, images, build output)
    team/            SetupService (first-call report, load local, clone missing), TeamScope (only the team's projects),
                     TeamCreator (a team setup from scratch: suggestions, create, add services and libraries, export), RepositoryLocator,
                     TeamService (read, cache, refresh the team file), TeamOverlay (team over personal settings, and back),
                     TeamAwareConfigRepository, MachineProbe (one fixed reading per requirement id, fingerprints),
                     RequirementsService (evaluate, keep readings, report)
  infrastructure/    port implementations: process/, persistence/, secrets/, gitlab/, github/, network/, system/
  api/               REST resources, dto/, ApiMapper, ApiExceptionMappers, security/
  cli/               Main, KitoutCommand, Console, RequirementsGate (checks before every command), commands/
src/main/resources/team/  the team starter kit "kitout team init" writes; must equal docs/team/ (a test checks)
docs/team/           kitout-team.example.json (<<REPLACE: …>> markers), kitout-team.schema.json, CODEOWNERS.example
src/main/webui/src/
  api/               client.ts (all HTTP), types.ts (names for generated types)
  lib/               pure logic with *.test.ts next to it; status.ts = how each state looks and what it allows
  hooks/             usePolling, useAction, useWorkspace
  components/        ui.tsx (StatusChip, StateChip, Tag, AppDialog, Section, ActionIcon, ...),
                     LogViewer.tsx (service, build and kitout logs), Toasts.tsx (Snackbar queue)
  views/             one file per page or dialog; RequirementsScreen (blocking screen, notice), TeamSection (Settings),
                     ServiceOptionsDialog (folders, docker options, health check), TeamRepositories (repositories and libraries)
src/main/webui/public/  kitout-icon.svg (logo and favicon), favicon.ico, kitout-icon-192.png; copied into the build as is
docs/logo/           the logo: kitout-icon.svg, kitout-logo.svg (with the word), PNGs
install.sh           build once: openapi.json, npm install, UI build, Quarkus package (JVM mode)
start.sh             start the built app and open the browser (or open the browser on the running one); --stop
uninstall.sh         stop kitout and its services, remove the launchers, settings, secrets, logs, images, caches (--keep-data, --keep-images)
scripts/install-launchers.sh  the kitout command, app menu entry, icon and dock pin (install.sh runs it; --remove)
  theme.ts           MUI theme: colours (light and dark), the frame's surfaces (palette.surface: navy top bar, sidebar),
                     ON_NAVBAR (colours for controls on the top bar), bar height and sidebar widths, type, the "soft" Chip variant
```

## Conventions

**Java**
- Constructor injection, `final` fields, no field injection outside picocli commands.
- Records for values; immutable configuration changed with `with...` methods.
- Errors that the developer can act on: `IllegalArgumentException` (bad input, 400),
  `IllegalStateException` (not possible now, 409), `NoSuchElementException` (unknown, 404).
  The message is the sentence the UI and CLI show: say what happened and what to do.
- External commands go through `CommandRunner` as argument lists. Never build a shell string.
- Anything that may contain a URL or command output goes through `Redactor.redact` before it
  leaves kitout. The CLI's `Console` and the REST error mapper already do this.
- Domain classes stay free of framework imports.

**TypeScript / React**
- All HTTP in `api/client.ts`. Views never call `fetch`.
- Logic that can be pure goes in `lib/` with a test next to it.
- Imports use explicit `.ts`/`.tsx` extensions (Node runs the tests without a bundler).
- Only erasable TypeScript syntax (`erasableSyntaxOnly`): no enums, no parameter properties.
- Copy: sentence case, plain verbs, buttons say what happens ("Start group", not "Submit"), errors
  are the backend's sentence. Colour never carries meaning alone (status chips also show text).
- UI components come from MUI; import each one by path (`import Chip from "@mui/material/Chip"`),
  icons too (`@mui/icons-material/StopRounded`). Style with the `sx` prop and theme tokens
  (`"text.secondary"`, `"error.main"`), never hex colours in views. Colours and defaults change
  in `theme.ts` only.
- Every status is a chip from `components/ui.tsx` (`StateChip`, `HealthChip`, `SeverityChip`,
  `BuildChip`, or `StatusChip` with a `Look`). Which colour a status gets, and which buttons it
  allows (`canStop`, `canStart`, `blocksRemoval`, ...), is decided in `lib/status.ts` and nowhere else.
- A disabled button says why: use `ActionIcon` with `why`, or a `Tooltip` around a `span`.
- MUI v9 removed the old `*Props` props. Use `slotProps` instead: `slotProps={{ htmlInput: ... }}`
  on TextField (not `inputProps`), `slotProps.input` on Checkbox and Switch, `slotProps.primary`
  on ListItemText, `slotProps.paper` on Dialog and Drawer, `slotProps.transition` on Snackbar.

## Recipes

### Add a doctor check

1. Create `application/doctor/checks/MyCheck.java`:

   ```java
   @ApplicationScoped
   public class MyCheck implements DoctorCheck {
       private final CommandRunner runner;
       public MyCheck(CommandRunner runner) { this.runner = runner; }

       @Override public String id() { return "my-check"; }
       @Override public int order() { return 65; }                 // where it appears in the report
       @Override public boolean appliesTo(DoctorContext c) { return !c.members().isEmpty(); }

       @Override
       public List<CheckResult> run(DoctorContext context) {
           Result r = runner.run(Command.of("mytool", "--version"));
           return List.of(r.succeeded()
                   ? CheckResult.ok(id(), "My tool", r.firstLine())
                   : CheckResult.warning(id(), "My tool", "mytool is not installed", "Install it with: sudo apt install mytool"));
       }
   }
   ```

2. That is all: `DoctorService` picks up every `DoctorCheck` bean. The UI and CLI show it.
3. Rules: read-only, fast (the doctor times out a check after 40 s), every non-OK result has a
   fix, results from files are `.asAdvisory()`. Use `ERROR` only for what makes a start fail.
4. Test the decision logic without commands, as `ChecksLogicTest` does.

To offer a one-click fix in the UI, return `.withAction("my-action")` and handle that action id
in `views/DoctorPage.tsx` (see `clean-remotes`).

### Add a machine requirement

1. Add the id to `TeamSetupParser.CHECK_IDS` and to the `enum` in `docs/team/kitout-team.schema.json`
   (copy it to `src/main/resources/team/`).
2. Teach `MachineProbe.read` one fixed, read-only way to read it, and `fingerprint` what the reading
   depends on (a binary via `binary(name)`, a file via `file(path)`; empty means read every time).
3. Give it a name and a default fix in `RequirementsService.defaultName/defaultFix`.
4. Test the reading with a fake `CommandRunner` (see `RequirementsTest`). Never read a command from
   the team file: the team says what is required, kitout decides how to look.

### Add a project type (or a capability of one)

The type of a service is `domain/project/ProjectType`: its label, run modes, whether kitout builds it
and whether natively. A new type touches these places, each a switch or one method:

1. `ProjectType`: the value, its run modes and capabilities.
2. `ProjectReader.read` (and `javaType`/`looksLikeService`/`whyNoService`): how it is recognised;
   a pure reader for its config next to `SpringBootConfig` or `NodePackage`, with a test.
3. `LaunchPlanner`: how it starts (and `profileVariable` if it reads a profile).
4. `BuildCommandFactory.command(type, …)` and `ArtifactLocator.jvmArtifact` if kitout builds it.
5. `ServiceSupervisor.healthDetail` for its hint when it has no readiness check.
6. UI: `TYPE_HINT` in `ServicesPage.tsx`; run modes come from the API.

Spring Boot native, for example, is step 1 (`nativeBuild` true), step 4 (`-Pnative native:compile`)
and `ArtifactLocator.nativeRunner` (the binary in `target/`). Test with `ProjectTypesTest` (real
folders) and `ProjectTypeLaunchTest` (command lines).

### Add a run mode

1. Add the value to `domain/run/RunMode`.
2. Add a factory method to `LaunchPlanner` and a unit test in `MiscDomainTest`.
3. Add the case to `ServiceSupervisor.plan(...)`; the switch does not compile until you do.
4. UI: add it to `RUN_MODES` in `api/types.ts` (the backend enum is a plain string in the API).

### Add an endpoint and use it in the UI

1. Add a record to `api/dto` and a method to a resource in `api` (or a new resource class with
   `@Path("/api")`). Resources stay thin: one application call plus mapping in `ApiMapper`.
2. `./mvnw test`: `OpenApiContractTest` fails and tells you the API changed.
3. `./mvnw test -Dkitout.updateApi=true` updates `src/main/webui/openapi.json`.
4. `cd src/main/webui && npm run generate:api`, export the new type name in `api/types.ts`,
   add the call to `api` in `api/client.ts`, use it in a view.
5. `npm run typecheck`, commit `openapi.json` with the change.

The security filter covers every path under `/api` automatically.

### Add a setting

1. Add the field to the right record in `KitoutConfig` and give it a default in the compact
   constructor, so old `config.json` files without it still load.
2. If it is shown in the UI: add it to `SettingsView`, `ApiMapper.settings` and `ApiMapper.apply`,
   then follow "Add an endpoint" from step 2.
3. Only if existing values must be transformed (a rename, a new meaning): bump
   `KitoutConfig.CURRENT_SCHEMA` and add a step to `JsonConfigRepository.MIGRATIONS`. The old file
   is backed up as `config.json.bak-v<old>` before the first save.

### Add a CLI command

1. Create a class in `cli/commands` with `@CommandLine.Command` implementing `Callable<Integer>`;
   inject services with `@Inject`.
2. Register it in `KitoutCommand.subcommands`.
3. Print through `Console` (redaction, symbols). Return `Console.OK`, `FAILED` or `BLOCKED`.
   Expected exceptions become one clear line automatically (see `KitoutApplication`).

### Teach kitout a native build error

Add one `Rule` to `NativeBuildErrorExplainer.RULES` (pattern, cause, fix) and a test line.
Order matters: specific patterns first.

### Change how a config value is read

`QuarkusConfig` resolves profiles and placeholders; `EndpointExtractor` decides which keys mean
what (port, datasources, Mongo, Kafka, rest clients). Add the key there and a case to
`QuarkusConfigTest`.

## Tests

| Level | Where | Runs | Covers |
|---|---|---|---|
| Domain | `src/test/java/org/kitout/domain` | milliseconds, no framework | parsing, redaction, config resolution, tiers (several BFFs), routing, commands, paths, docker options (`DockerOptionsTest`: splitting, refused options, mounts), where they go on the command line (`LaunchPlannerDockerTest`), kitout's container label (`ContainerLabelTest`) |
| Services with the real OS | `application/git/RemoteCleanupServiceTest`, `application/git/GitSyncServiceTest` (pull: behind, local changes, diverged, no upstream), `application/docker/ComposeServiceTest`, `application/git/GitFileReaderTest` (one file with real git: branch, change, missing file, blob-less), `application/docker/TeamComposeDownloadsTest` (copy from the repository, files next to it, retry, clone wins), `domain/git/GitHostTest` (kinds, registries, the team file's `git` section), `infrastructure/github/HttpGitHubApiTest`, `application/run/ImageSelectionTest`, `application/doctor/checks/ComposePortCheckTest`, `infrastructure/registry/RegistryProtocolTest` (token challenge, paging, Docker auth entries, host spellings, helper noise), `application/env/EnvironmentServiceTest` (shared vs. service, secrets never stored or returned), `domain/run/ImagePathTest`, `application/workspace/ProjectReaderTest`, `application/workspace/WorkspaceAdminTest`, `infrastructure/process/PosixProcessControlTest` | seconds, needs git and Linux | git remotes and rollback, project reading, hidden projects, folder browser limits, process groups and stopping a whole tree |
| Check logic | `application/doctor/checks/ChecksLogicTest` | milliseconds | token scopes and expiry, JDK versions |
| Team file and requirements | `domain/team/TeamFileTest` (the shipped example: refused with placeholders, valid when filled; credentials refused; version rules; Java vendors; starter kit equals docs), `domain/team/TeamLibrariesAndBffsTest` (`bffs` and `bff`, `healthCheck`, `javaLibraries`/`uiLibraries`, one place per repository), `application/run/ServiceSupervisorHealthTest` (incl. the health check switched off), `application/run/ServiceOptionsTest`, build variables in `EnvironmentServiceTest` (run, build, both; never mixed), `application/team/RequirementsTest` (PASS/WARN/FAIL, `enforceFrom`, warn-only, readings with fake commands, Maven wrappers, fingerprints), `application/team/TeamOverlayTest` (team over personal, saving keeps team values out, team file locations by repo and path, presets from the build), `cli/RequirementsGateTest`, `api/security/RequirementsFilterTest`, team parts of `EnvironmentServiceTest` and `ComposeServiceTest` | milliseconds | what blocks, what stays allowed, layering of variables and secrets, `.env` import, team compose environments |
| API | `api/ApiSecurityFilterTest`, `api/OpenApiContractTest` (`@QuarkusTest`) | a few seconds | the three API guards; the API contract file |
| UI logic | `src/main/webui/src/lib/*.test.ts` | `npm test` | SSE parsing, routes, session token, formatting, status rules (which state allows Stop, Remove, ...; container, environment and pull chips), log line levels, published ports, picker breadcrumbs, volume rows (`serviceOptions.test.ts`), GitLab and GitHub links split into team file fields (`teamSource.test.ts`), git host kinds (`gitHost.test.ts`) |

Tests never touch your real configuration: Surefire sets `KITOUT_HOME=target/kitout-test-home`.

What is not covered by automated tests and is worth a manual pass before a release:
starting a real image with Docker, a native build, the GitLab or GitHub API against your instance, the
keyring on your desktop. The release checklist below lists them.

## Upgrades

**Quarkus.** Stay on LTS releases (every six months; see quarkus.io/releases).

```bash
./mvnw quarkus:update -Dstream=<next LTS, e.g. 3.46>   # rewrites the pom and code with OpenRewrite recipes
./mvnw test && ./mvnw package -Dnative
```

Check the Quinoa release notes for a version that supports the new Quarkus (property
`quinoa.version` in the pom).

**UI dependencies.**

```bash
cd src/main/webui
npm outdated
npm install react@latest react-dom@latest @types/react@latest @types/react-dom@latest
npm install @mui/material@latest @mui/icons-material@latest @emotion/react@latest @emotion/styled@latest
npm install -D vite@latest
npm run typecheck && npm test && npm run build
```

**MUI.** kitout uses MUI v9 (`@mui/material` and `@mui/icons-material` on the same major, plus
Emotion 11). Minor releases are drop-in. For a new major, read its migration guide
(mui.com, "Migration") first: majors remove deprecated props, mostly in favour of `slotProps`.
MUI publishes codemods for most of them (`npx @mui/codemod@latest <version>/preset-safe src`).
Afterwards, `npm run typecheck` names every removed prop still in use.

TypeScript stays on 5.9 for now: `openapi-typescript` (which generates the API types) declares
`typescript@^5.x` as a peer dependency, so npm refuses TypeScript 6 with an `ERESOLVE` error.
Before raising TypeScript, check the peer range with `npm view openapi-typescript peerDependencies`
and move only when it includes the new major. Do not work around it with `--legacy-peer-deps`: the
type generation could then break silently.

Fast Refresh (keeping React state while editing) is not set up, to keep the dependency list
short. To add it: `npm install -D @vitejs/plugin-react` (a version that lists your Vite major as
a peer) and add `react()` to `plugins` in `vite.config.ts`.

**Java.** The code targets Java 21 (records, virtual threads, pattern matching). Raise
`maven.compiler.release` when the team moves to the next LTS JDK.

## Releasing

1. `./mvnw clean package -Dnative -Dquarkus.native.container-build=true`
2. Manual pass on a clean Linux user account:
   - `kitout doctor` with Docker stopped, then started (error, then OK)
   - set the git host, clone a group (GitLab group or GitHub organization), scan happens, suggested group appears
   - `kitout up <group>` with one service in IDE mode; check the BFFs start last, together
   - a service with a host folder (read-only) and `--memory 1g`: `docker inspect` shows both
   - a BFF with the health check off whose `/q/health` is blocked: it counts as started
   - stop kitout with Ctrl+C, start it again: running services are still shown as running
   - start a native build twice: the second waits with position 1
   - a repository with `https://user:token@...` origin: doctor warns, cleanup rolls back without a
     stored token, cleans with one
3. Tag the commit, attach `target/kitout-*-runner`, note the Quarkus version in the release notes.

## Troubleshooting

| Symptom | Likely cause | What to do |
|---|---|---|
| UI says "Open kitout with its link" | The tab has no session token (new kitout start, or opened by hand) | Open the link `kitout serve` printed |
| API answers 403 `forbidden-host` | Opened via a hostname other than 127.0.0.1/localhost | Use the printed link |
| Service stays STARTING | The readiness check never answers: slow start, or it is served somewhere kitout does not know | Check its log; hover the Starting chip to see the path and port kitout asks; raise `startTimeoutSeconds` in `config.json` |
| Service shows Running, Unhealthy | It started, but its readiness check now reports DOWN (named in the row) or no longer answers | Fix the dependency (often the database), then Restart if needed. kitout never stops it on its own |
| A Spring Boot service shows "No health check" | It has no actuator, or the readiness probe is off | Add `spring-boot-starter-actuator`; with `management.endpoint.health.probes.enabled=true` kitout asks `/actuator/health/readiness`, else `/actuator/health`. Or switch the health check off under Options |
| "No runnable jar found" for a Spring Boot or Java project | No jar with a `Main-Class` in `target/` (`build/libs/` with Gradle) | Build it; a plain Java project needs a runnable jar (shade or assembly plugin) |
| A Java library shows up as a service | Its build names a main class (e.g. an example main) | Remove it on the Services page (kitout remembers), or list it under `javaLibraries` in the team file |
| A Node frontend is STARTING forever | Its dev server uses another port than the guessed one (shown with `?`) | Options: set the port it really uses |
| A Node service fails right after "Installing the npm packages" | `npm install` failed (registry, Node version) | Its log shows npm's output; fix it, start again |
| "is a Node project: it runs from image, script, ide, not jvm" | A run mode the type does not have | Choose one it offers; for many services at once kitout skips those and says which |
| A BFF stays STARTING although it works | Its health endpoint is blocked (403/404 behind a filter, or not exposed) | **Options** next to it: switch off "Ask the readiness check" (or `kitout options <bff> --health-check off`, or `"healthCheck": false` in the team file). It then counts as started when its port answers |
| A build cannot reach the private Maven repository (401) | Its token is not in the build's environment | Add it as a variable with **Used for** *Build* (secret), shared or for the service; `settings.xml` reads it as `${env.NAME}` |
| Build failed at once: "The secret … is not in the keyring any more" | A secret build variable is missing (new machine, reset) | Enter it again under Environment |
| Start refused: "The folder … mounted into … does not exist" | A host folder in the service's Options is missing on this machine | Create it, or change or remove the folder under **Options** |
| Options not saved: "… cannot be changed here: …" | An extra docker option kitout sets itself (`--name`, `--network`, `-p`, `--rm`, `-d`, `-t`, `-e`, `--env-file`, `--label`) | Leave it out; the sentence says why and where it belongs instead (environment variables: the tune button) |
| Options not saved: "'…' is not one" | A word that is not an option (it would become the image) | Write options only, `--option value` or `--option=value`; quote values with spaces |
| UI build fails after an update: a field such as `volumes` or `bffs` does not exist | `src/main/webui/openapi.json` is from before the API change | `./install.sh` (refreshes it), or `./mvnw test -Dkitout.updateApi=true`; commit the file |
| A library repository shows up as "cannot be loaded" in Setup | It is listed under `services` | Move it to `javaLibraries` or `uiLibraries` in the team file: cloned and pulled, never a service |
| Service shows FAILED right after start | The process exited; often the port was taken or the DB is down | Its log window shows why; run the doctor. **Stop** clears what is left of the failed start |
| Docker page shows no environments | No compose folder set, or the files are named differently | **Pick compose file…** on the Docker page for any file; or Settings, Docker: add the folder, where files must be named `docker-compose-<environment>.yml` and sit directly in it |
| A YAML file is greyed out in the compose file picker | It has no top-level `services:` key (e.g. `application.yaml`), or it is larger than 1 MB | Pick the compose file itself; an extends/include file alone is not an environment |
| Forget is greyed out for a picked file | Its containers still run | Down first, so no containers run on unnoticed; then Forget |
| Docker page: "Docker is not available" | Daemon stopped, or your user may not use it | `systemctl start docker`; add yourself to the `docker` group and log in again |
| Environment shows "Down" although containers run | They were started from another file (copy, other path) | Start them through kitout once, or compare the file path in `docker inspect <container>` (label `com.docker.compose.project.config_files`) |
| Pull shows a service as "In your IDE" | It is in IDE mode: kitout leaves its checkout to the IDE | Pull it in the IDE (or change its run mode) |
| Pull skipped a service | Uncommitted changes, detached HEAD or no upstream branch | Commit or stash, check out the branch, or `git branch -u origin/<branch>`; then pull again |
| Pull failed with "diverged" | Local commits and new commits on origin | Merge or rebase in your IDE; kitout never does that for you |
| Start is greyed out, "Choose an image tag first" | Registry image mode has no default tag | Click **Choose tag** in the service row (or `kitout tag <service>`), pick the tag for your task |
| "Not logged in to the registry ..." | No docker login for the registry, or a different user than the git server's is needed | Settings, Container registry: log in with your registry user (or `kitout registry login -u <user>`) |
| Tag picker: "refused your login" | The registry user may not pull this repository, or the login expired | Log in again with a user that may pull it; check with `docker pull <image>` |
| Tag picker shows no tags / "No image repository" | The image path does not match the registry's layout | Settings, "Image path in the registry": e.g. **Project only** for registries without groups. For a single service: **Change** next to the repository in the tag picker |
| Container registry shows "Logged in" in amber, without a user | Docker has a login, but kitout cannot read it (the detail says which file or helper) | Starts work. For the tag list, check the named credential helper (`docker-credential-<name> list`), or log in again from Settings |
| A service from an image cannot reach its database | The image has no local settings | Add the variables: Settings, "Environment for all services", or the tune button in the service row (JDBC URL, user, password as secret); restart the service |
| "The secret ... is not in the keyring any more" | New machine, or the keyring was reset | Enter the value again where the variable is defined |
| Doctor: "Compose dev and orders" warning | A compose file publishes a service's port, often a copied microservice | Remove it from the compose file (or put it behind a profile) and run it as Registry image |
| A current build shows "outside kitout" | It was built in the IDE or a terminal, or before kitout kept a list | Expected: no log; build it from kitout to get one |
| Build button disabled / "is in IDE mode" | IDE mode means kitout leaves the service and its build to the IDE | Change its run mode, then build |
| Build refused: "Stop … first" | The service runs from its build output (IDE, JVM or native mode), and every build starts with clean | Stop it, build, start it again |
| Build attempts are gone after a restart | `~/.local/state/kitout/builds.json` was deleted or damaged (the kitout log says so) | Attempts from now on are kept; current builds are always read from disk |
| A current build cannot be deleted (checkbox greyed out) | The service runs from it, a build is writing it, or the service is in IDE mode | Stop the service, wait for the build, or change the run mode; the reason shows on the checkbox |
| An attempt cannot be removed | It is queued or running | Cancel it first |
| No version next to a service | The build file has none kitout can read (e.g. a version from a property it cannot resolve) | Set `<version>` in the pom (or `version` in Gradle); `${revision}` works when defined in `<properties>` |
| Builds page skipped a service | It was already queued or building | Wait for it, or cancel it in the list below |
| Native builds of a selection run one by one | By design: one native build at a time (memory) | JVM builds of a selection all run at once |
| Reset kept an image | A container that kitout did not start still uses it | `docker ps -a --filter ancestor=<image>`; remove that container, then reset again |
| Remove is greyed out | kitout still runs the service (also when FAILED) | Stop it, then remove it. Remove all lists the running ones and offers to stop them |
| `kitout` CLI prints nothing for `askpass` | Expected: it answers only git, and only with a stored fallback token | `kitout token` shows where the token comes from |
| `OpenApiContractTest` fails | The REST API changed (also after updating kitout to a version with new endpoints) | Follow "Add an endpoint", step 3 |
| A project keeps disappearing after a scan | It was removed earlier and is hidden | Settings, "Removed projects", **Show again** |
| UI build fails with missing `schema` | `openapi.json` not generated yet | Run `./mvnw test` once |
| UI typecheck fails on a `*Props` prop (e.g. `inputProps`) | MUI v9 removed it | Use the `slotProps` form; see "Conventions" |
| Dev mode: "port 5173 is still not listening after the checkTimeout" | Vite did not start. With `quarkus.quinoa.dev-server.logs=true` its own error is printed just above. Common causes: dependencies changed (delete `node_modules`), or an old Vite still holds the port | Read Vite's output; check the port with `ss -ltnp \| grep 5173` and stop that process |
| `npm error ERESOLVE` during the build | A dependency's peer range excludes a version in `package.json` (e.g. TypeScript above `openapi-typescript`'s range) | Align the versions in `package.json`; see "Upgrades". Do not use `--legacy-peer-deps` |
| Native build of kitout fails on reflection | A new type is persisted as JSON | Add it to `infrastructure/NativeReflection` |
| CLI stops with exit code 4, UI shows "This machine does not meet the team's requirements" | A tool differs from the team file's requirements (often after an upgrade) | Follow the fix on screen or in `kitout check`; then **Check again**. Settings, Doctor and kitout log stay usable |
| Requirement fails although the tool is right | kitout reads another binary than you think (PATH order, nvm not loaded, `JAVA_HOME` pointing elsewhere) | `kitout check` shows where each was found; `which java node mvn`, `echo $JAVA_HOME`. Start kitout from a shell with the right PATH |
| A requirement is wrong for everyone | The team file asks for something nobody has (or kitout misreads it) | Fix the team file (merge request); meanwhile the maintainer can set `"enforcement": "warn"` or an `enforceFrom` day. Developers cannot switch the check off |
| "Maven wrapper of <project>" fails | The project's `./mvnw` downloads an older Maven | In the project: `./mvnw wrapper:wrapper -Dmaven=<version>`, commit `.mvn/wrapper/maven-wrapper.properties` |
| Team setup: "cannot be used" | Placeholders left, a credential in the file, an unknown requirement id, a too old kitout | The list names each problem; fix the team file. kitout keeps using the last good copy meanwhile |
| A service will not start: "The team file asks every developer for MONGO_PASSWORD" | Your value for a team secret is not in the keyring yet (new machine, reset) | Settings, Team setup, or `kitout team secret MONGO_PASSWORD` |
| Docker page: "Not on this machine yet" on a team environment | The repository is not cloned and the file could not be read from the git server (no login, wrong `path`, server unreachable and no copy yet) | The message says which; fix it, or clone the repository: Settings, Team setup, **Clone**, or `kitout team clone` |
| Team environment: "uses files next to it … only works from a clone" | The compose file mounts or reads files beside it (`./init`, `env_file:`, `build:`); a downloaded copy alone would mount empty folders | Clone the repository under **Team repositories**; the clone is used from then on |
| Team environment tagged "from the repository" | Its repository is not cloned; kitout uses a copy read from the git server (`~/.local/state/kitout/team/compose/`) | Nothing to do. Changes arrive within an hour, or at once with Settings, Team setup, **Read again** |
| Old readings after changing a tool by hand | Should not happen (fingerprints); if it does | `kitout check --force`, or delete `~/.local/state/kitout/team/requirements.properties` |
| Build fails: "does not declare a valid bean constructor" | A bean has more than one constructor and none is marked | Put `@Inject` on the constructor CDI should use (the other one is for tests) |
| A project on disk is not loaded ("not in the team file") | With a team file, kitout loads only the services it names, by git origin and host | Add the repository to the team file (merge request), or check its origin with `git remote -v` |
| "Load local projects" / "Clone missing" refused | The machine does not meet the requirements yet | Fix what Setup step 2 shows, then **Check again** |
| Setup opens on every start | Something is not complete: the box at the top says what | Do what it lists; when everything passes, kitout goes straight to Services with a success message |
| "Open kitout with its link" although kitout ran before | New browser, cleared site data, or the key changed (settings reset, `session-token` deleted) | Open the link `kitout serve` prints, once |
| The Docker page does not show a team compose environment just added | kitout still uses its copy of the team file from before (it reads it again every hour) | Setup, **Check again**, or Settings, Team setup, **Read again**, or `kitout team refresh` |
| The team's compose environment does not start / shows a missing file | The repository is not cloned, or `path` in the team file does not match the file in the repository | The Docker page names the file and where it looked; clone it under Team repositories, or fix `path` (relative to the repository root) in the team file |
| A service start waits a while the first time | kitout brings up the team's compose environments first (`autoStart`); the first `up` pulls their images | Watch it on the Docker page; set `"autoStart": false` for environments you start by hand |
| `kitout: command not found` after the install | `~/.local/bin` is not on the PATH yet (it is added at the next login on most systems) | Log in again, or add `export PATH="$HOME/.local/bin:$PATH"` to `~/.bashrc` |
| kitout from the app menu or dock: "kitout could not start", a tool is missing, or an old version is checked (e.g. Node.js after `nvm install`) | The desktop does not read `~/.bashrc`, where nvm or SDKMAN set up the tools. `start.sh` runs itself once through your shell (interactive, so it reads `~/.bashrc` or `~/.zshrc`); the PATH of the terminal `install.sh` ran in (`target/kitout-env.sh`) is only added after it | kitout keeps the tools it saw when it started: right-click kitout in the dock, **Stop kitout**, then start it again. Still wrong: `bash -ic 'node --version'` shows what kitout sees; fix `~/.bashrc` until that is right |
| Clicking kitout in the dock does nothing visible | kitout already runs, but no browser opened (`xdg-open` missing), or it failed before the notification service was up | `./start.sh` in a terminal shows why; `journalctl --user -e` has the menu start's output |
| kitout is not in the dock | Not GNOME/Ubuntu (KDE, Xfce, Cinnamon), or `gsettings` not available | Find kitout in the app menu and pin it (KDE: Pin to Task Manager) |
| Setup still shows a repository in the old place after changing **Clone into** or the workspace folders | (fixed) kitout remembered where it had found each repository; it now looks again when the folder is no longer inside the clone or workspace folders | Open Setup again or **Check again**; a project already loaded keeps its path until you remove it and load it again |
| Setup: "The team file set in kitout's build (kitout.team.*) cannot be used" | A mistake in `application.properties` or the `KITOUT_TEAM_*` variables, often a link in `kitout.team.repo` | `repo` is group/project only (`acme/infrastructure`), `path` the file from the repository root (`kitout/kitout-team.json`); rebuild with `./install.sh` |
| Following a team file: "The repository is group/project only, not a link" | A GitLab or GitHub link was typed into the repository field (the UI splits pasted links, the CLI does not) | Repository `acme/infrastructure`, path `kitout/kitout-team.json`; or paste the whole link into the UI |
| "Build a team setup" is not offered | kitout's build sets `kitout.team.required=true` | Ask for the team's file location |
| "Add to the team" refused: another host / no git origin | Team services are repositories on the team's git host | `git remote add origin <url>` (or fix it); a team without a git server yet: leave the host empty in the setup |
| A teammate's kitout cannot find a compose file of the setup | It was stored as a local `file` (outside any repository) | Put the compose file into a repository, rebuild or edit the setup to name it by `repo` and `path`, export again |
| Setup step 3: "cannot be loaded" | The repository is on disk but holds no Quarkus service (often an infrastructure repository listed under `services`), or its git origin differs from the team file | Follow the note: move the repository to `repositories` in the team file, or fix the origin. It does not hold up the setup |
| The infrastructure repository does not show up as a service | By design: repositories under `repositories` (or with compose files) are never services | Docker page, **Team repositories**: folder, Clone, Pull |
| Old image versions disappear | kitout keeps the newest N versions of each service image (Settings, Docker) | Raise the number (at most 5) |
| Port shown with `?` | The port comes from an environment variable | kitout shows the file default; the service may use another |

Where the logs are:

| Log | Where |
|---|---|
| kitout itself (daemon and CLI) | **kitout log** in the UI (live, filterable), or `~/.local/state/kitout/kitout.log` (rotated at 10 MB, 3 kept), and the terminal it runs in. Settings, "kitout's files" shows the paths |
| Each service | `~/.local/state/kitout/logs/<service>.log`, or **Logs** next to the service, or `kitout logs <service> -f` |
| Each build | `~/.local/state/kitout/builds/<id>.log`, or **Log** on the Builds page |
| Each compose up/down | `~/.local/state/kitout/compose/<job>.log`, or the output panel on the Docker page |

`tail -f ~/.local/state/kitout/kitout.log` follows kitout's own log. For more detail, start kitout with
`QUARKUS_LOG_CATEGORY__ORG_KITOUT__LEVEL=DEBUG kitout serve`.

## Known limitations and next steps

Deliberately not in kitout (for now):
guarded commits and deploy templates, pipeline and Sonar status, git status across repositories,
IDE environment export, AI project creation, merge requests, build history.

Known gaps, in suggested order:

1. **Image staleness check.** The doctor should compare the ports in the registry image
   with the local checkout. It needs reading `application.properties` from
   the image (`docker create` + `docker cp`), which is slow, so it should be an on-demand check.
2. **Multi-module Maven projects.** The scanner treats the first folder whose build file mentions
   `io.quarkus` as the project and does not look inside it. In a multi-module build whose parent
   pom imports the Quarkus BOM, the parent is taken as the project and the application module is
   not found on its own. Supporting this needs a "find the module with the Quarkus plugin" step
   in `ProjectReader` and builds run from the parent with `-pl <module> -am`.
3. **YAML config.** Nested maps and scalars only; lists and multi-line values are ignored. Enough
   for ports and URLs.
4. **Different SSH host.** For SSH clones, the clone URL's host must equal the configured git
   host. Instances with a separate SSH hostname need that rule relaxed in `CloneService`.
5. **Clone progress.** Shown per project (queued, cloning, done), not as a percentage.
6. **Fast Refresh** in UI dev mode (see Upgrades).
7. **Service id collisions.** Two clones of the same repository get the same id; the later scan
   wins. Rare, but a suffix scheme would fix it.

### How this code was verified

The Java domain, services and adapters (211 tests, including the scanner on real Spring Boot, Java and Node folders, including real git repositories, real process
groups and real Jackson for `config.json`) and the UI logic (42 tests) were run. The full backend was
compiled against minimal stand-ins for the Quarkus, JAX-RS and picocli APIs; the two `@QuarkusTest`
classes need Quarkus itself and were not run.

The rename to kitout, host folders and docker options, the health check
switch, several BFFs per group, common libraries, `install.sh` and `start.sh` were added the same way: the UI was
type-checked with `tsc` against API types generated from the Java DTOs and loose React/MUI stand-ins,
the scripts were run with stand-in tools (order of steps, errors, an already running kitout), not
for a real build (no Maven Central here). Before relying on it: `./install.sh --test` once, then try a service with a host folder and
an extra option, a group with two BFFs, and a BFF with the health check off.

The MUI version of the UI could not be installed where it was written (no npm registry access).
It was checked in two ways: compiled with `tsc` against loose stand-in declarations (catches
unknown names, missing imports, unused code), and bundled with a plain-HTML stand-in for the MUI
components, then driven in a browser against a mock API (Stop on a failed service, Remove blocked
while running, remove all blocked with the running services named, the kitout log page streaming
and filtering). Neither checks MUI's own prop types or the look: the first
`npm install && npm run build` does. Expect small fixes there, most likely a prop type.
