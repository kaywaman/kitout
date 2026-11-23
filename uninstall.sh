#!/usr/bin/env bash
# Removes kitout from this machine, for this user.
#
#   ./uninstall.sh                 show what goes, ask, then remove it
#   ./uninstall.sh --yes           remove it without asking
#   ./uninstall.sh --keep-data     only stop kitout and remove the command, app menu entry and dock icon;
#                                  settings, secrets and logs stay (for installing kitout again later)
#   ./uninstall.sh --keep-images   leave the service images kitout pulled in Docker
#
# What goes: the services kitout started, kitout itself, the kitout command, the app menu entry and
# dock icon, kitout's settings, run state, logs and builds (~/.config/kitout, ~/.local/state/kitout),
# kitout's secrets in the keyring, the service images it pulled, and the Maven and Node.js copies
# install.sh fetched (~/.cache/kitout).
#
# What stays: this folder (delete it yourself afterwards), your repositories, the containers from the
# team's compose files (e.g. MongoDB) and their Docker volumes, your git and Docker logins.
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(pwd)
JAR="$ROOT/target/quarkus-app/quarkus-run.jar"

YES=false
KEEP_DATA=false
KEEP_IMAGES=false
for arg in "$@"; do
  case "$arg" in
    --yes|-y) YES=true ;;
    --keep-data) KEEP_DATA=true ;;
    --keep-images) KEEP_IMAGES=true ;;
    -h|--help) sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see ./uninstall.sh --help)" >&2; exit 2 ;;
  esac
done

# the same folders as kitout itself (KitoutPaths): KITOUT_HOME, or the XDG folders
if [ -n "${KITOUT_HOME:-}" ]; then
  CONFIG_DIR="$KITOUT_HOME/config"
  STATE_DIR="$KITOUT_HOME/state"
else
  CONFIG_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/kitout"
  STATE_DIR="${XDG_STATE_HOME:-$HOME/.local/state}/kitout"
fi
CACHE_DIR="${XDG_CACHE_HOME:-$HOME/.cache}/kitout"

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
warn() { printf '\033[33m%s\033[0m\n' "$*" >&2; }

# a folder is removed only if it is kitout's: never the home folder or anything above it
safe_rm() {
  local dir="$1"
  [ -e "$dir" ] || return 0
  case "$(cd "$dir" 2>/dev/null && pwd -P)" in
    ""|/|"$HOME"|"$(cd "$HOME" && pwd -P)") warn "Not removing $dir: it is not a kitout folder."; return 0 ;;
  esac
  rm -rf -- "$dir"
  echo "Removed $dir"
}

# --- what will happen ----------------------------------------------------------------------------
echo "This removes kitout for $(id -un):"
echo "  - stops kitout and the services it started"
echo "  - the kitout command, the app menu entry and the dock icon"
if ! $KEEP_DATA; then
  echo "  - settings and run state:      $CONFIG_DIR"
  echo "  - logs, builds, caches:        $STATE_DIR"
  echo "  - kitout's secrets in the keyring (tokens, secret variables)"
  $KEEP_IMAGES || echo "  - the service images kitout pulled"
  echo "  - Maven and Node.js copies:    $CACHE_DIR"
fi
echo "Stays: this folder ($ROOT), your repositories, the containers from the team's compose files (e.g. MongoDB) and their Docker volumes, your git and Docker logins."

if ! $YES; then
  if [ ! -t 0 ]; then
    echo "Not a terminal: run ./uninstall.sh --yes to remove without asking." >&2
    exit 1
  fi
  printf '\nRemove kitout? [y/N] '
  read -r answer
  case "$answer" in
    y|Y|yes|YES) ;;
    *) echo "Nothing changed."; exit 0 ;;
  esac
fi

# --- 1. stop kitout -------------------------------------------------------------------------------
step "Stopping kitout"
"$ROOT/start.sh" --stop || warn "kitout could not be stopped; it may still run until you log out."

# --- 2. services, settings, secrets, logs, builds, images (kitout reset) -------------------------
if ! $KEEP_DATA; then
  step "Stopping the services and removing kitout's data"
  [ -r "$ROOT/target/kitout-env.sh" ] && . "$ROOT/target/kitout-env.sh"
  RESET=(--settings --logs --builds)
  $KEEP_IMAGES || RESET+=(--images)
  if [ -f "$JAR" ] && command -v java >/dev/null; then
    java -jar "$JAR" reset "${RESET[@]}" --yes \
      || warn "kitout reset reported problems (above); the rest of the uninstall goes on."
  else
    warn "No kitout build here, so the services cannot be stopped through kitout."
    if command -v docker >/dev/null; then
      ids=$(docker ps -aq --filter label=org.kitout.service 2>/dev/null || true)
      if [ -n "$ids" ]; then
        # shellcheck disable=SC2086
        docker rm -f $ids >/dev/null && echo "Removed kitout's containers."
      fi
    fi
    warn "Services kitout ran as local processes (JVM, npm) keep running: stop them yourself, or log out."
  fi
fi

# --- 3. command, app menu, dock -------------------------------------------------------------------
step "Removing the kitout command, the app menu entry and the dock icon"
"$ROOT/scripts/install-launchers.sh" --remove || warn "Could not remove them all (see above)."

# --- 4. folders and keyring -----------------------------------------------------------------------
if ! $KEEP_DATA; then
  step "Removing kitout's folders"
  if command -v secret-tool >/dev/null; then
    # anything kitout ever stored in the keyring, also secrets no longer named in the settings
    secret-tool clear service kitout 2>/dev/null || true
  fi
  safe_rm "$CONFIG_DIR"
  safe_rm "$STATE_DIR"
  safe_rm "$CACHE_DIR"
  if [ -n "${KITOUT_HOME:-}" ] && [ -d "$KITOUT_HOME" ]; then
    rmdir "$KITOUT_HOME" 2>/dev/null || true
  fi
fi

step "kitout is removed"
echo "Left as they are:"
echo "  - this folder: delete it with  rm -rf $(printf '%q' "$ROOT")"
echo "  - your repositories"
echo "  - the containers from the team's compose files (e.g. MongoDB) and their Docker volumes"
echo "    (remove them with: docker compose -f <compose file> down; add -v to also delete the volumes)"
echo "  - your git and Docker logins"
$KEEP_DATA && echo "  - kitout's settings and secrets (--keep-data): $CONFIG_DIR, $STATE_DIR"
exit 0
