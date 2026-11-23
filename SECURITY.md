# Security

kitout runs on the developer's machine with their git and registry logins, so we take reports seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead: the repository's
**Security** tab, **Report a vulnerability**. Describe what an attacker can do, and how to reproduce it.
You get an answer within a week; a fix and a release follow as fast as the problem asks for, and you
are credited in the release notes unless you prefer not to be.

## What kitout protects, and how

- **The local API** listens on `127.0.0.1` only and needs a session token (`X-Kitout-Token`) that only
  the user who started kitout can read (`~/.local/state/kitout/session-token`). Cross-site requests from
  a browser are refused.
- **Tokens and passwords** stay in git's credential helper, the system keyring (`secret-tool`) or Docker's
  credential store. kitout never writes them to its settings, logs, diagnostics or the UI; messages are
  redacted.
- **The team file** cannot point kitout at another server: repositories are named by path and always
  cloned from the configured git host. A team file with anything that looks like a credential is refused.
- **Commands** run with argument lists, never through a shell, so no input can change what runs.

Supported versions: the latest release.
