#!/usr/bin/env bash
#
# Register the two Git submodules VoidTube needs, pinned to the commits this
# project was built against. Run once from the repo root, before the first push.
#
# Why: a ZIP of the source cannot carry Git submodules, so a freshly unzipped
# checkout has a .gitmodules file but no submodule gitlinks. GitHub Actions then
# fails with: "Could not read script '…/SharedModules/core_settings.gradle'".
#
# Usage:
#   bash tools/add-submodules.sh
#
set -euo pipefail

SHARED_URL="https://github.com/aleixrodriala/SharedModules"
SHARED_PATH="SharedModules"
SHARED_SHA="11b65d83fc527ecef3ecf2ee9e842a760f41c989"

CORE_URL="https://github.com/yashoncode/MediaServiceCore"
CORE_PATH="MediaServiceCore"
CORE_SHA="3ef8fd7426def8a613fb77ba29f4a270523ece9f"

if [ ! -f build.gradle ] || [ ! -d smarttubetv ]; then
  echo "error: run this from the VoidTube repo root." >&2
  exit 1
fi

# Start clean so a leftover .gitmodules (from the ZIP) cannot block `submodule add`.
git rm -r --cached --ignore-unmatch "$SHARED_PATH" "$CORE_PATH" >/dev/null 2>&1 || true
rm -rf "$SHARED_PATH" "$CORE_PATH"
rm -f .gitmodules

echo "Adding $SHARED_PATH …"
git submodule add "$SHARED_URL" "$SHARED_PATH"
echo "Adding $CORE_PATH …"
git submodule add "$CORE_URL" "$CORE_PATH"

# Pin to the exact commits the project expects (they are in the clones above).
echo "Pinning $SHARED_PATH -> $SHARED_SHA"
git -C "$SHARED_PATH" checkout -q "$SHARED_SHA"
echo "Pinning $CORE_PATH -> $CORE_SHA"
git -C "$CORE_PATH" checkout -q "$CORE_SHA"

git add .gitmodules "$SHARED_PATH" "$CORE_PATH"
git commit -m "Add SharedModules + MediaServiceCore submodules (pinned)"

echo
echo "Done. Verify with:  git submodule status"
echo "Then push with:     git push"
