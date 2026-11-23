# Security

kitout runs on the developer's machine with their git and registry logins, so we take reports seriously.

## Reporting a vulnerability

Please **do not open a public issue**. Use GitHub's private reporting instead: the repository's
**Security** tab, **Report a vulnerability**. Describe what an attacker can do, and how to reproduce it.

kitout is maintained in spare time, without guaranteed response or fix times. Reports are read, and handled on a best-effort basis. If a problem cannot be fixed soon, it is published as a security advisory, so users can decide for themselves. Fixes from the community are very welcome. Reporters are credited unless they prefer not to be.

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

Supported versions: Only the latest release; there are no backports.
