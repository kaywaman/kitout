# Changelog

All notable changes to kitout. The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/);
versions follow [Semantic Versioning](https://semver.org/) (0.x: anything may still change).

## [Unreleased]

## [0.1.0] - first public release

- Runs a team's microservice environment locally: Quarkus, Spring Boot, plain Java and Node projects,
  and services from registry images only; groups with one or more BFFs started last.
- Run modes per service: registry image (with the tag you choose), local JVM or native build, npm script,
  or your IDE.
- A team file (`kitout-team.json`) in any git repository: services, groups, compose environments,
  variables, secrets and machine requirements, laid over each developer's own settings.
- Works with GitHub, GitLab and any other git server; GitLab groups and GitHub organizations can be
  listed and cloned.
- Docker compose environments, also read from a repository that is not cloned.
- Doctor with fixes, builds page, pull with fast-forward only, guided cleanup of tokens in remote URLs.
- Web UI and CLI on the same core; app menu entry and dock icon on Linux desktops.
- `install.sh` builds once (fetching Maven 3.9 and Node.js 22 when needed); `uninstall.sh` removes
  everything kitout put on the machine.
