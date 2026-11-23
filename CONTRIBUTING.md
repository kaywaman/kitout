# Contributing to kitout

Thanks for helping. kitout is young (0.x): issues with what did not work on your machine, and with
what your team needs, are as welcome as code.

## Reporting a problem

Open an issue with:

- what you did and what happened (the message, and the part of `~/.local/state/kitout/kitout.log` around it);
- **Copy diagnostics** from the Doctor page, which leaves tokens and passwords out;
- your Linux distribution, Java, Docker and git versions, and whether your repositories are on GitLab,
  GitHub or another git server.

Security problems: not in an issue, see [SECURITY.md](SECURITY.md).

## Changing the code

```bash
./install.sh --test --no-launchers   # build everything and run every Java and UI test
./mvnw quarkus:dev                    # or mvn: the daemon on :7879 with live reload, the UI through Vite
cd src/main/webui && npm test         # UI logic tests
```

Before you start on something larger, open an issue so we can agree on the approach.

- **How kitout is built, and why:** [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md): layers, flows,
  state, security, and the decision log.
- **How to change it safely:** [docs/MAINTAINING.md](docs/MAINTAINING.md): recipes for the usual
  changes (a doctor check, a run mode, an endpoint, a setting, a project type), tests, troubleshooting.

The rules that keep it maintainable:

- The domain layer is plain Java with no framework; the application layer talks to the outside world
  through the ports in `application/port`; adapters live in `infrastructure`.
- Every external call is a command line tool (git, docker) or a small HTTP client, run with argument
  lists, never through a shell.
- Logic that can be pure is pure and has a test next to it: `domain/` in Java, `src/main/webui/src/lib/`
  in the UI.
- Messages say what is wrong and what helps, in plain words. Secrets are never logged, shown or stored
  outside the keyring.
- An API change regenerates `src/main/webui/openapi.json` (`./install.sh` does it); the UI's types are
  generated from it.

## Pull requests

- One topic per pull request, with tests for new behaviour.
- The CI must be green: `./install.sh --test --no-launchers` on Ubuntu 24.04.
- Update the docs your change touches (README, `docs/`, the team file schema in `docs/team/` and its copy
  in `src/main/resources/team/`; a test checks that the two are equal).
- Add a line to [CHANGELOG.md](CHANGELOG.md) under "Unreleased".

By contributing, you agree that your contribution is licensed under the Apache License 2.0, like the rest
of kitout.
