#!/usr/bin/env bash
# clean-git-remotes.sh - find and remove credentials embedded in git remote URLs.
#
# Usage:
#   clean-git-remotes.sh <workspace-root>            # dry run: list affected repos
#   clean-git-remotes.sh <workspace-root> --apply    # rewrite the remotes
#
# Only the user:token@ part of https:// remote URLs is removed. Tokens are never
# printed. Run the dry run first, store the token in a credential helper, then
# run with --apply.. kitout itself offers the same as "Clean remotes" in the doctor.
set -euo pipefail

root=${1:?usage: clean-git-remotes.sh <workspace-root> [--apply]}
apply=${2:-}
found=0

strip_creds() { sed -E 's#^(https?://)[^@/]+@#\1#'; }

while IFS= read -r -d '' gitdir; do
  repo=${gitdir%/.git}
  for remote in $(git -C "$repo" remote); do
    for key in url pushurl; do
      url=$(git -C "$repo" config --get "remote.$remote.$key" || true)
      [ -n "$url" ] || continue
      clean=$(printf '%s' "$url" | strip_creds)
      [ "$url" != "$clean" ] || continue
      found=$((found + 1))
      echo "$repo  remote '$remote' ($key) -> $clean"
      if [ "$apply" = "--apply" ]; then
        if [ "$key" = pushurl ]; then
          git -C "$repo" remote set-url --push "$remote" "$clean"
        else
          git -C "$repo" remote set-url "$remote" "$clean"
        fi
      fi
    done
  done
done < <(find "$root" -type d -name .git -prune -print0)

if [ "$found" -eq 0 ]; then
  echo "No credentials found in remote URLs under $root."
elif [ "$apply" = "--apply" ]; then
  echo "Cleaned $found remote URL(s). Next: run 'git fetch' in one repo to check the credential helper."
else
  echo "$found remote URL(s) contain credentials. Re-run with --apply to clean them."
fi
