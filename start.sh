#!/usr/bin/env bash
# Starts kitout and opens it in the browser. No build: run ./install.sh once before (and again after
# the code changed).
#
#   ./start.sh              start kitout and open the browser
#   ./start.sh --no-open    start it and only print the link
#   ./start.sh --stop       stop a kitout started from the app menu or another terminal
#
# Already running? Then start.sh only opens the browser on it (that is what the app menu and the
# dock icon run). Stop kitout with Ctrl+C, or --stop. Services it started keep running; stop them in
# the UI or with `kitout down <group>` if you want everything gone.
set -euo pipefail

cd "$(dirname "$0")"
JAR="$(pwd)/target/quarkus-app/quarkus-run.jar"
PORT=7878   # quarkus.http.port of the packaged app
URL="http://127.0.0.1:$PORT"

# started from the app menu: add the tool paths of the terminal install.sh ran in (nvm, SDKMAN, ...)
[ -r target/kitout-env.sh ] && . target/kitout-env.sh

# no terminal (app menu, dock): problems go to a desktop notification, or nobody would see them
INTERACTIVE=true
[ -t 1 ] || [ -t 2 ] || INTERACTIVE=false

OPEN=true
STOP=false
for arg in "$@"; do
  case "$arg" in
    --no-open) OPEN=false ;;
    --stop) STOP=true ;;
    -h|--help) sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see ./start.sh --help)" >&2; exit 2 ;;
  esac
done

fail() {
  printf '\033[31mstart.sh: %s\033[0m\n' "$*" >&2
  if ! $INTERACTIVE && command -v notify-send >/dev/null; then
    notify-send --app-name=kitout --icon=kitout "kitout could not start" "$*" || true
  fi
  exit 1
}

# --- stop ---------------------------------------------------------------------------------------
if $STOP; then
  PIDS=$(pgrep -u "$(id -u)" -f -- "$JAR serve" || true)
  if [ -z "$PIDS" ]; then
    echo "kitout is not running."
    exit 0
  fi
  # SIGTERM: kitout shuts down cleanly; the services it started keep running and are found again next time
  kill $PIDS
  for _ in $(seq 1 50); do
    pgrep -u "$(id -u)" -f -- "$JAR serve" >/dev/null || { echo "kitout stopped (the services it started keep running)."; exit 0; }
    sleep 0.2
  done
  fail "kitout did not stop within 10 seconds (pid $PIDS)."
fi

open_browser() {
  if $OPEN && command -v xdg-open >/dev/null; then
    xdg-open "$1" >/dev/null 2>&1 &
  else
    echo "Open: $1"
  fi
}

# Where kitout keeps the key browsers need (the same rules as KitoutPaths).
session_token_file() {
  if [ -n "${KITOUT_HOME:-}" ]; then
    echo "$KITOUT_HOME/state/session-token"
  else
    echo "${XDG_STATE_HOME:-$HOME/.local/state}/kitout/session-token"
  fi
}

# --- already running: just open it ---------------------------------------------------------------
if (exec 3<>"/dev/tcp/127.0.0.1/$PORT") 2>/dev/null; then
  TOKEN_FILE=$(session_token_file)
  if [ -r "$TOKEN_FILE" ]; then
    echo "kitout is already running on $URL; opening it."
    open_browser "$URL/#token=$(tr -d '[:space:]' < "$TOKEN_FILE")"
    exit 0
  fi
  fail "Port $PORT is in use, but not by a kitout this user started (no $TOKEN_FILE). Stop that program, then run ./start.sh again."
fi

# --- start -----------------------------------------------------------------------------------------
command -v java >/dev/null || fail "Java is not on the PATH. Install Java 21 (Temurin or OpenJDK) and open a new terminal."
JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | cut -d. -f1)
[ "${JAVA_MAJOR:-0}" -ge 21 ] || fail "Java ${JAVA_MAJOR:-?} found; kitout needs Java 21 or newer (check JAVA_HOME too)."

[ -f "$JAR" ] || fail "kitout is not built yet. Run ./install.sh once, then ./start.sh."

if [ -n "$(find src pom.xml -type f -newer "$JAR" -not -path '*/node_modules/*' -not -path '*/dist/*' \
      -not -name 'openapi.json' -not -name 'schema.d.ts' 2>/dev/null | head -n 1)" ]; then
  echo "Note: the code changed since the last ./install.sh; starting the previous build. Run ./install.sh to use the changes."
fi

echo "Starting kitout on $URL (Ctrl+C stops it; the services it started keep running)"
if $OPEN; then
  command -v xdg-open >/dev/null || echo "xdg-open not found; open the link below yourself." >&2
  exec java -jar "$JAR" serve --open
else
  exec java -jar "$JAR" serve
fi
