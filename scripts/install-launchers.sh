#!/usr/bin/env bash
# Makes an installed kitout easy to start; install.sh runs it after a successful build.
#
#   scripts/install-launchers.sh            add the kitout command, the app menu entry and the dock icon
#   scripts/install-launchers.sh --remove   take them away again (kitout itself and its settings stay)
#
# What it writes (for this user only, no sudo):
#   ~/.local/bin/kitout                                  the kitout command (the CLI)
#   ~/.local/share/applications/kitout.desktop           the app menu entry: starts kitout and opens the browser
#   ~/.local/share/icons/hicolor/.../apps/kitout.*       its icon
#   <kitout>/target/kitout-env.sh                        the tool paths of this terminal, for starts from the menu
# and pins kitout to the dock on GNOME (Ubuntu's dock too). Elsewhere it says how to pin it.
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT=$(pwd)
BIN="$HOME/.local/bin"
APPS="${XDG_DATA_HOME:-$HOME/.local/share}/applications"
ICONS="${XDG_DATA_HOME:-$HOME/.local/share}/icons/hicolor"
DESKTOP_FILE="$APPS/kitout.desktop"

say() { printf '%s\n' "$*"; }

# single-quoted for sh, so any folder name is safe in the generated scripts
quote() { printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"; }

dock_supported() {
  command -v gsettings >/dev/null 2>&1 \
    && gsettings get org.gnome.shell favorite-apps >/dev/null 2>&1
}

pin_to_dock() {
  local current
  current=$(gsettings get org.gnome.shell favorite-apps 2>/dev/null) || return 1
  case "$current" in
    *"'kitout.desktop'"*) return 0 ;;
    "@as []"|"[]") gsettings set org.gnome.shell favorite-apps "['kitout.desktop']" ;;
    *) gsettings set org.gnome.shell favorite-apps "${current%]}, 'kitout.desktop']" ;;
  esac
}

unpin_from_dock() {
  local current updated
  current=$(gsettings get org.gnome.shell favorite-apps 2>/dev/null) || return 0
  case "$current" in
    *"'kitout.desktop'"*) ;;
    *) return 0 ;;
  esac
  updated=$(printf '%s' "$current" | sed -e "s/, 'kitout.desktop'//" -e "s/'kitout.desktop', //" -e "s/'kitout.desktop'//")
  gsettings set org.gnome.shell favorite-apps "$updated" || true
}

refresh_caches() {
  command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database -q "$APPS" 2>/dev/null || true
  command -v gtk-update-icon-cache >/dev/null 2>&1 && gtk-update-icon-cache -q -t "$ICONS" 2>/dev/null || true
}

if [ "${1:-}" = "--remove" ]; then
  # only remove a kitout command this script wrote, never someone's own (e.g. a native build)
  if [ -f "$BIN/kitout" ] && grep -q "written by kitout's install.sh" "$BIN/kitout" 2>/dev/null; then
    rm -f "$BIN/kitout"
  fi
  rm -f "$DESKTOP_FILE" "$ICONS/scalable/apps/kitout.svg" "$ICONS/512x512/apps/kitout.png" "$ICONS/192x192/apps/kitout.png"
  dock_supported && unpin_from_dock
  refresh_caches
  say "Removed the kitout command, the app menu entry and the dock icon."
  exit 0
fi

[ -f "$ROOT/target/quarkus-app/quarkus-run.jar" ] || { say "kitout is not built yet: run ./install.sh" >&2; exit 1; }

# --- the tools of this terminal ---------------------------------------------------------------
# The app menu starts kitout without this terminal's setup (nvm, SDKMAN and the like live in
# ~/.bashrc), so Java, Maven and Node.js could be missing there. The PATH and JAVA_HOME of the
# install are added after the menu's own PATH: what is set there still comes first.
{
  echo "# written by kitout's install.sh: the tool paths of the terminal it ran in. Run ./install.sh again after changing tools."
  echo "PATH=\"\$PATH:\"$(quote "$PATH"); export PATH"
  if [ -n "${JAVA_HOME:-}" ]; then
    echo "[ -n \"\${JAVA_HOME:-}\" ] || { JAVA_HOME=$(quote "$JAVA_HOME"); export JAVA_HOME; }"
  fi
} > "$ROOT/target/kitout-env.sh"

# --- the kitout command -------------------------------------------------------------------------
mkdir -p "$BIN"
if [ -f "$BIN/kitout" ] && ! grep -q "written by kitout's install.sh" "$BIN/kitout" 2>/dev/null; then
  say "Left $BIN/kitout alone: it is not one install.sh wrote (a native kitout?). Remove it to get the command from this build."
else
  cat > "$BIN/kitout" <<EOF
#!/bin/sh
# The kitout command, written by kitout's install.sh for the build in $ROOT.
# Moved the folder? Run ./install.sh there again.
ROOT=$(quote "$ROOT")
JAR="\$ROOT/target/quarkus-app/quarkus-run.jar"
[ -r "\$ROOT/target/kitout-env.sh" ] && . "\$ROOT/target/kitout-env.sh"
if [ ! -f "\$JAR" ]; then
  echo "kitout: no build in \$ROOT. Run \$ROOT/install.sh" >&2
  exit 1
fi
exec java -jar "\$JAR" "\$@"
EOF
  chmod +x "$BIN/kitout"
  say "kitout command: $BIN/kitout (try: kitout --help)"
  case ":$PATH:" in
    *":$BIN:"*) ;;
    *) say "  $BIN is not on your PATH yet. Most systems add it at the next login; or add this to ~/.bashrc:"
       say "    export PATH=\"\$HOME/.local/bin:\$PATH\"" ;;
  esac
fi

# --- app menu and dock --------------------------------------------------------------------------
case "$ROOT" in
  *'"'*|*'`'*|*'$'*|*'\'*)
    say "No app menu entry: the folder name $ROOT has a character desktop files cannot hold (\" \` \$ \\). Start kitout with ./start.sh."
    exit 0 ;;
esac

mkdir -p "$APPS" "$ICONS/scalable/apps" "$ICONS/512x512/apps" "$ICONS/192x192/apps"
cp "$ROOT/docs/logo/kitout-icon.svg" "$ICONS/scalable/apps/kitout.svg"
cp "$ROOT/docs/logo/kitout-icon-512.png" "$ICONS/512x512/apps/kitout.png"
cp "$ROOT/src/main/webui/public/kitout-icon-192.png" "$ICONS/192x192/apps/kitout.png"

cat > "$DESKTOP_FILE" <<EOF
[Desktop Entry]
Type=Application
Version=1.5
Name=kitout
GenericName=Dev environment
Comment=Start your team's services, databases and BFFs, and open kitout in the browser
Exec="$ROOT/start.sh"
Icon=kitout
Terminal=false
Categories=Development;
Keywords=quarkus;spring;docker;compose;microservices;dev;
StartupNotify=false
Actions=open;stop;

[Desktop Action open]
Name=Open kitout
Exec="$ROOT/start.sh"

[Desktop Action stop]
Name=Stop kitout (services keep running)
Exec="$ROOT/start.sh" --stop
EOF
chmod +x "$DESKTOP_FILE"
refresh_caches
say "App menu: kitout (under Development)"

if dock_supported; then
  if pin_to_dock; then
    say "Dock: kitout is pinned"
  else
    say "Dock: could not pin kitout; right-click it in the app menu, Add to Favorites"
  fi
else
  say "Dock: find kitout in the app menu and pin it there (KDE: Pin to Task Manager; Xfce, Cinnamon: Add to Panel)"
fi
