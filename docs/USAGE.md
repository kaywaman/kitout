# Using kitout

What kitout recognises, and how to do the everyday things in the UI and on the command line.
`kitout --help` and `kitout <command> --help` describe every option.

| Project type | Recognised by | Port and health | Local run | Built by kitout |
|---|---|---|---|---|
| Quarkus | `io.quarkus` in the build file | `%dev.quarkus.http.port`, `/q/health/ready` (or the management port) | `quarkus-app/quarkus-run.jar` with `-Dquarkus.profile`, or the native runner | JVM and native |
| Spring Boot | `org.springframework.boot` | `server.port` from `application(-<profile>)` files, `/actuator/health/readiness` (or `/health`) | the runnable jar with `-Dspring.profiles.active` | JVM |
| Java app | a main class in the build (shade, assembly, exec, Gradle `application`) | none until you set a port under **Options**; without one, running means its process lives | the runnable jar (`Main-Class` in its manifest) | JVM |
| Node | `package.json` with a `dev`, `start` or `serve` script | guessed from the script or Vite/Angular config (shown with `?`), set it under **Options** | `npm run dev` (after `npm install` if needed), `PORT` set | no |
| Image only | the team file's `images` | the team file's `port` and `healthPath` | its registry image | no |

Libraries (no main class, no start script) are never services: list them under `javaLibraries` or
`uiLibraries` to have them cloned.

| You want to… | Use |
|---|---|
| Work in the browser | `kitout` (opens the UI on http://127.0.0.1:7878). Open the printed link once per browser; afterwards the plain address works. Every start checks the team file, the machine and the projects in the background |
| Set up from your team's file | Start `kitout`: the **Setup** page checks the team file and your machine, then loads or clones the team's projects. In a terminal: `kitout team use --repo acme/infrastructure --path kitout/kitout-team.json` (host from Settings, Git, or `--host`; branch `main` unless `--ref`), then `kitout team clone` / `kitout team load` and `kitout team secret MONGO_PASSWORD` |
| See every requirement in detail | Nothing to do usually: kitout checks the machine on every call. For diagnostics: `kitout check` (`--force` reads every tool again) |
| Start a team from scratch (just an infrastructure compose file, no CI/CD yet) | Setup, **Build a team setup**, or `kitout team create acme-shop --compose ~/infra/docker-compose-dev.yml`. Add services as they come (`kitout team add <folder>`), share it with `kitout team export` |
| Write a team file for your team | `kitout team init ~/work/acme/platform/kitout-team`, replace every `<<REPLACE: …>>`, commit. See [docs/team](docs/team/) |
| Check your machine | `kitout doctor --group acme-shop` |
| Start / stop a group | `kitout up acme-shop` / `kitout down acme-shop` |
| See what runs, its health and start time | `kitout status` |
| Follow a log | `kitout logs acme.shop.orders -f`, or the log button next to the service |
| See what kitout itself did | **kitout log** in the UI, or `~/.local/state/kitout/kitout.log` |
| See and clean up builds | **Builds** page: **Current builds** (what can run now, delete to free disk space) and **Build attempts** (the last 10 per service, with logs). Every build is a clean build |
| Build locally | **Builds** page: choose services and options (JVM builds run at once, native one by one). Or `kitout build acme.shop.orders --native --profile qa --skip-tests` |
| Get the projects | In the UI: **Add projects**, then browse to the folder. Or `kitout scan ~/work`, or `kitout clone acme/shop --all` (a GitLab group, or a GitHub organization or user) |
| Bring checkouts up to date | In the UI: **Pull**. Or `kitout pull --check`, then `kitout pull` (fast-forward only) |
| Get the compose files and other team repositories | Not needed for a compose file that stands alone: kitout reads it from the repository (tag "from the repository"). For one that uses files next to it, and for other team repositories: Docker page, **Team repositories**: Clone, Pull, copy the folder. Or `kitout team clone`, `kitout team pull` |
| Get the common Java and UI libraries | List them in the team file under `javaLibraries` / `uiLibraries`; they are cloned and pulled with the rest (Docker page, **Common Java libraries**, **Common UI libraries**), never started. A team setup built on this machine: **Add library** there, or `kitout team add-library <folder>` (`--ui` for a UI library) |
| Build with another profile | Builds page or a service's build dialog: the team's profiles (dev, qa, prod by default), or **Other…** to type one. Edit the list in Settings, Builds. Or `kitout build <service> --profile staging` |
| Run a service with another profile, port or npm script | **Options** next to it: Profile (Quarkus, Spring Boot), Port, npm script (Node). Or `kitout options <service> --profile qa --port 8085 --script start` |
| Run a service you have no checkout of (another team's, any language) | Team file, `images`: `{ "name": "payments", "image": "other-team/payments", "port": 8090 }`. It starts with its group from the tag you choose |
| Pass environment variables to builds (`MAVEN_OPTS`, a repository token) | Settings, "Environment for all services", or the tune button next to a service: set **Used for** to *Build* or *Run and build*. Or `kitout env set MAVEN_OPTS=-Xmx2g --for build` |
| Mount host folders into a service's container, or pass extra `docker run` options | **Options** (gear button) next to the service. Or `kitout options acme.shop.orders -v ~/certs:/deployments/certs:ro --docker-args "--memory 1g"` |
| Start a BFF whose health endpoint is blocked | **Options** next to it: switch off "Ask the readiness check"; it then counts as started when its port answers. Or `kitout options acme.shop.bff --health-check off`, or `"healthCheck": false` for that service in the team file |
| Groups with more than one BFF | Settings, Groups, **Edit**: click every BFF under "Entry points". They start together once the other services are up. Team file: `"bffs": [...]` |
| Keep fewer or more old image versions | Settings, Docker: 1 to 5 versions per service image (default 3) |
| Start the databases and brokers | In the UI: **Docker**, then **Up** for an environment. Or `kitout compose up dev` (uses `docker-compose-dev.yml`) |
| Choose the image a service runs | In the UI: **Choose tag** next to "Registry image". Or `kitout tag acme.shop.orders` (list), `kitout tag acme.shop.orders mr-123` |
| Log in to the container registry | Settings, **Container registry** (or `kitout registry login -u <user>`); may differ from your git login |
| Images without the group or owner in their path | Settings, "Image path in the registry": **Project only** (`{project}`) |
| Give images local settings (Mongo, ...) | Settings, "Environment for all services", or the tune button next to a service. Or `kitout env set MONGO_HOST=localhost`, `kitout env set --secret MONGO_PASSWORD`, or take over your compose `.env`: `kitout env import .env` |
| Use your own compose file | In the UI: **Docker**, **Pick compose file…**. Or `kitout compose pick ~/my/kafka-debug.yml`, then `kitout compose up kafka-debug` |
| Remove tokens from remote URLs | `kitout clean-remotes` (dry run), then `--apply` |
| Start everything from the IDE, local JVM builds, ... | `kitout mode IDE` (all), `kitout mode JVM --group acme-shop` |
| Drop a library or archived project from kitout | `kitout remove acme.common-lib` (files stay on disk; scans skip it). Stop it first if kitout runs it |
| Forget all services only | `kitout remove --all --yes` (after stopping what kitout runs) |
| Remove kitout from the machine | | `./uninstall.sh` (preview and question), `--yes`, `--keep-data`, `--keep-images` |
| Start over completely | Settings, **Start over**: reset settings, logs, builds and local images (you choose). Or `kitout reset` (preview), then `kitout reset --yes` |

`kitout --help` and `kitout <command> --help` describe every option.

**What kitout does not do:** replace your IDE (the service you change runs there in Quarkus dev
mode), define databases or brokers (it runs your team's compose files and checks they are
reachable), merge or rebase your branches, or trigger releases.

## Git servers

| | GitHub | GitLab | Any other git server (Gitea, Forgejo, Bitbucket, SSH) |
|---|---|---|---|
| Clone, pull, team file and compose files | yes, with your git login | yes | yes |
| Clone a whole group | organization or user | group with subgroups | name the repositories in the team file |
| Token check in the doctor | | scopes and expiry | |
| Default registry | `ghcr.io` | `registry.<host>` | set it in Settings |

kitout uses git itself for everything, with the login git already has: an SSH key, or a credential
helper over HTTPS (GitHub: `gh auth setup-git`; GitLab: the token as password once). The kind of server
is guessed from the host name; set it in Settings, Git (team file: `git.provider`) when the name does
not say it, e.g. a GitLab at `git.example.com`.

## The team file

One `kitout-team.json` in any git repository describes the team's setup: services, groups, compose
environments, variables, secrets and the tools every machine needs. Developers follow it once
(Setup page, or `kitout team use --repo acme/infrastructure --path kitout/kitout-team.json`), and kitout
reads it again every hour. A build can preset it for everyone in `application.properties`
(`kitout.team.host`, `kitout.team.repo`, `kitout.team.path`, `kitout.team.ref`). Starter kit, example and
JSON schema: [docs/team](team/).
