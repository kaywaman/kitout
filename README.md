<p align="center"><img src="docs/logo/kitout-logo.svg" alt="kitout" width="360"></p>

<p align="center"><b>Your team's microservice environment on your laptop, in minutes.</b><br>
Run the one or two services you change from your checkout or IDE, everything else from registry images,
and the databases from the team's compose files.</p>

---

Working on one microservice usually means starting ten: the right versions of the others, the
databases and brokers they expect, the BFF last, with the right ports, profiles and secrets. Each
developer rebuilds that knowledge by hand, and every newcomer loses a week to it.

kitout does it for you, on Linux, for Java teams:

- **Mixes local and images, per service.** Each service runs from a registry image with the tag you
  choose (`1.4.2`, `mr-123`), a local JVM or native build, an npm script, or your IDE. Switch with one
  click; kitout starts the group in the right order and the BFFs last, once the others are healthy.
- **Knows your projects.** Quarkus, Spring Boot, plain Java apps and Node frontends are recognised from
  their build files: ports, profiles, health endpoints. Services you have no checkout of run from their
  image.
- **One team file instead of a wiki page.** `kitout-team.json` in any git repository names the
  services, groups, compose environments, variables, secrets and the tools every machine needs.
  Reviewed like code; kitout follows it and sets each machine up from it.
- **Says what is wrong, and what helps.** The doctor checks Docker, the registry login, Java, git
  access, ports and tokens in remote URLs, and names the fix.
- **Any git server.** GitHub, GitLab, or any other: kitout uses git with your existing login.
  On GitHub and GitLab it also lists an organization's or group's repositories to clone.
- **Web UI and CLI** on the same core: everything in the browser, or `kitout up shop` in a terminal.

**How it compares.** `docker compose` alone runs everything as containers and leaves the order, the
health checks and switching a service to your IDE to you. Tilt, Skaffold, Garden and DevSpace aim at
Kubernetes. Quarkus Dev Services and Spring Boot's compose support start the databases of *one*
service. kitout sits in between: the whole landscape of a team, with the services you work on running
natively.

**Status:** 0.x, early. Linux only (developed on Ubuntu 24.04); macOS is on the wish list. Feedback
and issues are very welcome.

## Quick start on Ubuntu 24.04

```bash
# Java 21, git, curl and Docker (with compose) from Ubuntu
sudo apt install -y openjdk-21-jdk git curl xz-utils docker.io docker-compose-v2 libsecret-tools
sudo usermod -aG docker "$USER"      # then log out and in once, so Docker works without sudo

# kitout
git clone https://github.com/kaywaman/kitout.git ~/kitout
cd ~/kitout
./install.sh                         # builds once; fetches Maven 3.9 and Node.js 22 if Ubuntu's are too old
```

`./install.sh` puts **kitout** in the app menu and the dock and adds the `kitout` command. Click it,
and the **Setup** page opens in your browser: follow your team's file there, or build a team setup
from scratch, or just add your project folders. Run `./install.sh` again after pulling a new version.

Other distributions work the same way with Java 21+, git, curl and Docker installed.

**Removing kitout:** `./uninstall.sh` shows what goes and asks first. It stops kitout and the services
it started, and removes the command, the app menu entry, kitout's settings, logs and keyring secrets,
the images it pulled and the Maven and Node.js copies `install.sh` fetched. Your repositories, the
containers from the team's compose files (e.g. MongoDB) and their Docker volumes, and your git and
Docker logins stay. Then delete the kitout folder.
`--keep-data` keeps settings and secrets, for installing again later.

## Using it

| You want to… | In the UI | In a terminal |
|---|---|---|
| Follow the team's setup | Setup page | `kitout team use --repo acme/infrastructure --path kitout/kitout-team.json` |
| Start and stop a group | Group selector and **Start group** in the top bar | `kitout up shop`, `kitout down shop` |
| Run one service from your IDE, the rest from images | Services page, the service's run mode | `kitout mode IDE acme.shop.orders` |
| Pick the image tag a service runs | **Choose tag** next to the service | `kitout tag acme.shop.orders mr-123` |
| Start the databases and brokers | Docker page, **Up** | `kitout compose up dev` |
| Check your machine | Doctor page | `kitout doctor` |
| Follow a log | Log button next to the service | `kitout logs acme.shop.orders -f` |

Everything else, from builds and profiles to host volumes, multiple BFFs, common libraries and starting
over, is in **[docs/USAGE.md](docs/USAGE.md)**. `kitout --help` lists every command.

**What kitout does not do:** replace your IDE (the service you change runs there, in Quarkus or Spring
dev mode), define your databases (it runs your team's compose files), merge or rebase branches, or
deploy anything.

## Documentation

- [docs/USAGE.md](docs/USAGE.md): what kitout recognises, and how to do each thing.
- [docs/team](docs/team/): the team file: example, JSON schema, starter kit (`kitout team init`).
- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): how kitout is built, and why.
- [docs/MAINTAINING.md](docs/MAINTAINING.md): changing it safely, tests, upgrades, troubleshooting.

## Developing

```bash
./install.sh --test --no-launchers   # build and run every Java and UI test (what the CI runs)
./mvnw quarkus:dev                   # or mvn: daemon on :7879 with live reload; Quinoa runs Vite for the UI
cd src/main/webui && npm test        # UI logic tests
```

Built with Quarkus 3.40 LTS (Java 21), React 19 and MUI 9. Contributions are welcome: see
[CONTRIBUTING.md](CONTRIBUTING.md). Security problems: [SECURITY.md](SECURITY.md).

## License

[Apache License 2.0](LICENSE).
