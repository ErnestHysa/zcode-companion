#!/usr/bin/env bash
# Deploy the bundle-sensor runtime OUTSIDE the TCC-protected Desktop tree.
#
# Launchd-spawned processes have no Desktop/Documents access (TCC), so an agent
# whose script/baseline live under ~/Desktop fails with "Operation not
# permitted" the moment macOS enforces folder protection (live finding
# 2026-10-09: launchctl exit 126, stderr "Operation not permitted" on open).
# The deploy mirrors the needed runtime files into
# ~/Library/Application Support/zcode-drift/ (never TCC-gated) and installs a
# plist pointing there. Re-run after changing any sensor source — the deploy
# COPIES, it never links, so the repo keeps its committed state.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
DEPLOY="$HOME/Library/Application Support/zcode-drift"
PLIST_SRC="$SCRIPT_DIR/com.zcode.android.drift-bundle-sensor.plist"
PLIST_DST="$HOME/Library/LaunchAgents/com.zcode.android.drift-bundle-sensor.plist"

mkdir -p "$DEPLOY/tools/drift" "$DEPLOY/tools/sensors"
for f in bundle-sensor.sh notify.mjs; do
  cp "$SCRIPT_DIR/$f" "$DEPLOY/tools/sensors/$f"
done
for f in extract-relay-surface.mjs check-drift.mjs drift-lib.mjs relay-lib.mjs relay-baseline.json; do
  cp "$REPO_ROOT/tools/drift/$f" "$DEPLOY/tools/drift/$f"
done
chmod +x "$DEPLOY/tools/sensors/bundle-sensor.sh"

sed -e "s|@REPO_ROOT@|$DEPLOY|g" -e "s|@LOGS@|$HOME/Library/Logs/zcode-android-drift|g" \
    "$PLIST_SRC" > "$PLIST_DST"

# launchd jobs get a minimal PATH — the sensor shells out to `node` (baseline
# read, report parse) and launchd cannot see ~/.hermes/node/bin etc. Inject the
# interactive PATH into the job (live finding 2026-10-09: launchctl exit 127,
# node not found in the launchd environment).
NODE_DIR="$(dirname "$(command -v node)")"
plutil -replace EnvironmentVariables -xml \
  "<dict><key>PATH</key><string>$NODE_DIR:/usr/local/bin:/opt/homebrew/bin:/usr/bin:/bin:/usr/sbin:/sbin</string><key>HOME</key><string>$HOME</string></dict>" \
  "$PLIST_DST"
launchctl unload "$PLIST_DST" 2>/dev/null || true
launchctl load "$PLIST_DST"

echo "deployed: $DEPLOY"
echo "plist:    $PLIST_DST"
echo "verify:   launchctl kickstart -k gui/\$(id -u)/com.zcode.android.drift-bundle-sensor"
