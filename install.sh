#!/usr/bin/env bash
# Builds kitout once, ready to start with ./start.sh:
#   1. checks the tools: Java 21+ and git must be installed; Maven 3.9+ and Node.js 22.12+ are fetched
#      once into ~/.cache/kitout/ when missing or too old
#   2. writes the API description the UI is typed against (src/main/webui/openapi.json)
#   3. installs the UI's npm packages and builds the UI (type check included), with readable errors
#   4. packages kitout (Quarkus, JVM mode) with the UI inside: target/quarkus-app/
#   5. adds the kitout command (~/.local/bin/kitout), an app menu entry and a dock icon
#
#   ./install.sh                     all of the above
#   ./install.sh --test              also run every Java and UI test
#   ./install.sh --no-launchers      skip step 5 (e.g. on a server)
#   ./install.sh --remove-launchers  only take away what step 5 added, then stop
#
# Run it again after pulling or changing the code. Starting needs no build: ./start.sh, or kitout
# in the app menu.
set -euo pipefail

cd "$(dirname "$0")"
ROOT=$(pwd)
WEBUI="$ROOT/src/main/webui"
JAR="$ROOT/target/quarkus-app/quarkus-run.jar"

TESTS=false
LAUNCHERS=true
for arg in "$@"; do
  case "$arg" in
    --test) TESTS=true ;;
    --no-launchers) LAUNCHERS=false ;;
    --remove-launchers) exec "$(dirname "$0")/scripts/install-launchers.sh" --remove ;;
    -h|--help) sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $arg (see ./install.sh --help)" >&2; exit 2 ;;
  esac
done

step() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
fail() { printf '\n\033[31minstall.sh: %s\033[0m\n' "$*" >&2; exit 1; }

# --- 1. tools ----------------------------------------------------------------------------------
step "Checking the tools"
command -v java >/dev/null || fail "Java is not on the PATH. Install Java 21 (Temurin or OpenJDK) and open a new terminal."
JAVA_MAJOR=$(java -XshowSettings:properties -version 2>&1 | sed -n 's/^ *java.specification.version = //p' | cut -d. -f1)
[ "${JAVA_MAJOR:-0}" -ge 21 ] || fail "Java ${JAVA_MAJOR:-?} found; kitout needs Java 21 or newer (check JAVA_HOME too)."
echo "Java $JAVA_MAJOR"
command -v git >/dev/null || fail "git is not installed (sudo apt install git)."

# Maven 3.9 or newer. Ubuntu 24.04's apt package is 3.8, so a missing or older Maven is fetched once from
# Apache (checked against its published SHA-512) into ~/.cache/kitout/, used only by this build.
MAVEN_VERSION=3.9.12
maven_ok() {
  local v
  v=$("$1" --version 2>/dev/null | sed -n 's/^Apache Maven \([0-9][0-9.]*\).*/\1/p' | head -n 1)
  [ -n "$v" ] || return 1
  local major=${v%%.*} rest=${v#*.}
  local minor=${rest%%.*}
  [ "$major" -gt 3 ] || { [ "$major" -eq 3 ] && [ "$minor" -ge 9 ]; }
}
fetch_maven() {
  local dir="${XDG_CACHE_HOME:-$HOME/.cache}/kitout/apache-maven-$MAVEN_VERSION"
  if [ ! -x "$dir/bin/mvn" ]; then
    command -v curl >/dev/null || fail "Maven 3.9+ is needed and curl is missing to fetch it. Install curl (sudo apt install curl) or Maven 3.9 (sdk install maven)."
    local base="https://archive.apache.org/dist/maven/maven-3/$MAVEN_VERSION/binaries/apache-maven-$MAVEN_VERSION-bin.tar.gz"
    local tmp
    tmp=$(mktemp -d)
    echo "Fetching Maven $MAVEN_VERSION from Apache (once)…" >&2
    curl -fsSL "$base" -o "$tmp/maven.tar.gz" && curl -fsSL "$base.sha512" -o "$tmp/maven.sha512" \
      || fail "Could not download Maven $MAVEN_VERSION (network or proxy?). Install Maven 3.9 yourself (sdk install maven) and run ./install.sh again."
    [ "$(sha512sum "$tmp/maven.tar.gz" | cut -d' ' -f1)" = "$(cut -d' ' -f1 < "$tmp/maven.sha512" | tr -d '[:space:]')" ] \
      || fail "The downloaded Maven does not match its checksum; not used. Try again, or install Maven 3.9 yourself."
    mkdir -p "$(dirname "$dir")"
    tar -xzf "$tmp/maven.tar.gz" -C "$(dirname "$dir")"
    rm -rf "$tmp"
  fi
  echo "$dir/bin/mvn"
}
if [ -x ./mvnw ]; then
  MVN=./mvnw
elif command -v mvn >/dev/null && maven_ok mvn; then
  MVN=mvn
else
  command -v mvn >/dev/null && echo "$(mvn --version 2>/dev/null | head -n 1) is older than 3.9; using Maven $MAVEN_VERSION for this build."
  MVN=$(fetch_maven)
fi
echo "Maven: $("$MVN" --version 2>/dev/null | head -n 1)"

# Node.js 22.12 or newer, for the UI build only. Ubuntu 24.04's apt package is 18, so a missing or older
# Node.js is fetched once from nodejs.org (the newest 22.x, checked against its SHASUMS256.txt) into
# ~/.cache/kitout/ and put first on the PATH of this build.
node_ok() {
  command -v node >/dev/null && command -v npm >/dev/null || return 1
  local v major minor
  v=$(node --version 2>/dev/null | sed 's/^v//')
  major=${v%%.*}
  minor=$(echo "$v" | cut -d. -f2)
  [ "${major:-0}" -gt 22 ] || { [ "${major:-0}" -eq 22 ] && [ "${minor:-0}" -ge 12 ]; }
}
fetch_node() {
  local arch
  case "$(uname -m)" in
    x86_64) arch=x64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) fail "Node.js 22.12+ is needed; there is no ready-made Node.js for $(uname -m). Install it yourself (nvm install 22)." ;;
  esac
  command -v curl >/dev/null || fail "Node.js 22.12+ is needed and curl is missing to fetch it. Install curl (sudo apt install curl) or Node.js (nvm install 22)."
  local base="https://nodejs.org/dist/latest-v22.x" sums name dir tmp
  sums=$(curl -fsSL "$base/SHASUMS256.txt") \
    || fail "Could not reach nodejs.org (network or proxy?). Install Node.js 22 yourself (nvm install 22) and run ./install.sh again."
  name=$(printf '%s\n' "$sums" | awk -v a="linux-$arch.tar.xz" '$2 ~ a"$" {print $2; exit}')
  [ -n "$name" ] || fail "nodejs.org lists no Node.js 22 for linux-$arch."
  dir="${XDG_CACHE_HOME:-$HOME/.cache}/kitout/${name%.tar.xz}"
  if [ ! -x "$dir/bin/node" ]; then
    tmp=$(mktemp -d)
    echo "Fetching ${name%.tar.xz} from nodejs.org (once)…" >&2
    curl -fsSL "$base/$name" -o "$tmp/$name" || fail "Could not download $name."
    [ "$(sha256sum "$tmp/$name" | cut -d' ' -f1)" = "$(printf '%s\n' "$sums" | awk -v n="$name" '$2 == n {print $1}')" ] \
      || fail "The downloaded Node.js does not match its checksum; not used. Try again, or install Node.js 22 yourself."
    mkdir -p "$(dirname "$dir")"
    tar -xJf "$tmp/$name" -C "$(dirname "$dir")" || fail "Could not unpack $name (is xz-utils installed?)."
    rm -rf "$tmp"
  fi
  echo "$dir/bin"
}
if ! node_ok; then
  command -v node >/dev/null && echo "Node.js $(node --version) is older than 22.12; using Node.js 22 for this build."
  NODE_BIN=$(fetch_node)
  export PATH="$NODE_BIN:$PATH"   # also for Quinoa during the Maven build
fi
echo "Node.js $(node --version | sed 's/^v//'), npm $(npm --version)"

# --- 2. API description for the UI ---------------------------------------------------------
# OpenApiContractTest writes src/main/webui/openapi.json from the API the backend really serves;
# the UI's TypeScript types are generated from it, so they always match the Java side.
if $TESTS; then
  step "Compiling kitout and running all Java tests (this also writes openapi.json)"
  "$MVN" -B clean test -Dkitout.updateApi=true \
    || fail "The Java build or a Java test failed: see the output above."
else
  step "Compiling kitout and writing the API description (openapi.json)"
  "$MVN" -B clean test -Dtest=OpenApiContractTest -Dsurefire.failIfNoSpecifiedTests=false -Dkitout.updateApi=true \
    || fail "The Java build failed, or the API description could not be written: see the output above."
fi
[ -f "$WEBUI/openapi.json" ] || fail "$WEBUI/openapi.json was not written. Run: $MVN test -Dtest=OpenApiContractTest -Dkitout.updateApi=true"

# --- 3. UI ---------------------------------------------------------------------------------
step "Installing the UI's npm packages"
(cd "$WEBUI" && npm install --no-audit --no-fund) \
  || fail "npm install failed: see the output above (network, proxy or registry access?)."

if $TESTS; then
  step "Running the UI tests"
  (cd "$WEBUI" && npm test) || fail "A UI test failed: see the output above."
fi

step "Building the UI (API types, type check, bundle)"
(cd "$WEBUI" && npm run build) \
  || fail "The UI build failed: the errors above name the file and line (src/main/webui/...)."

# --- 4. package ----------------------------------------------------------------------------
step "Packaging kitout (JVM mode, UI included)"
"$MVN" -B package -DskipTests \
  || fail "Packaging failed: see the output above."

[ -f "$JAR" ] || fail "$JAR was not written: see the output above."
# --- 5. command, app menu, dock -------------------------------------------------------------
if $LAUNCHERS; then
  step "Adding the kitout command, the app menu entry and the dock icon"
  "$ROOT/scripts/install-launchers.sh" \
    || echo "The kitout command or the app menu entry could not be added (see above); ./start.sh works anyway." >&2
fi

step "Done. Start kitout from the app menu or the dock, or with ./start.sh (no build needed until the code changes)"
