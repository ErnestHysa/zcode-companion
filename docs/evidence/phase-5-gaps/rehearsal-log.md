# Phase-5 E2E rehearsal — T1 sensors on seeded changes (AAP-2 acceptance)

Date: 2026-10-07. Machine: Ernest's Mac (bundle leg is Mac-in-loop by design,
§14.2; INV-15 respected — no relay traffic anywhere, no credentials, no app run).

Seeds:
- **Fake release**: local git repo at `/tmp/phase5-rehearsal/upstream`, built by
  copying the REAL reference clone's `packages/shared` (@ `29628c9`), committing,
  tagging `v3.14.3`, then committing seeded drift and tagging `v3.14.4`:
  + `ServiceChannels.DriftProbe` channel (additive class),
  + `sessionPhaseSchema` → `sessionPhaseSchemaV2` rename in
    `zcode-protocol-v4/snapshot.ts` (consumed schema+enum rename → BREAKING),
  + `PROTOCOL_V4_LIMITS.maxFrameBytes` `1024*1024` → `2*1024*1024` (consumed
    limit → BREAKING).
- **Seeded bundle change**: copy of the REAL installed `zcode.cjs` (desktop
  3.14.4, `fad4c35c4c36ec21…`) with `maxFragments:64→48` and
  `WorkspaceClosed:4012→4014`; the real 327 MB `app.asar` used unchanged.

## Leg A — open-repo sensor (watch → checkout → drift-check)

```
$ printf '{"lastTag":"v3.14.3"}\n' > /tmp/phase5-rehearsal/state/state.json
$ node tools/sensors/open-repo-watch.mjs --repo /tmp/phase5-rehearsal/upstream \
    --state /tmp/phase5-rehearsal/state/state.json \
    --out /tmp/phase5-rehearsal/state/watch.json --update-state
{
  "kind": "open-repo-watch",
  "repo": "/tmp/phase5-rehearsal/upstream",
  "latestTag": "v3.14.4",
  "lastTag": "v3.14.3",
  "isNew": true,
  "checkedAt": "2026-10-07T17:33:33.520Z"
}
$ node tools/drift/check-drift.mjs \
    --shared-root /tmp/phase5-rehearsal/upstream/packages/shared/src \
    --inventory tools/drift/surface-inventory.json \
    --manifest tools/drift/consumed-surfaces.json --commit v3.14.4 \
    --out /tmp/phase5-rehearsal/state/drift-report.json
verdict=BREAKING added=3 changed=1 removed=2 breaking=3 bodyChanged=0
BREAKING enums/removed sessionPhaseSchema: enum export removed
BREAKING limits/changed PROTOCOL_V4_LIMITS.maxFrameBytes: "1024*1024" -> "2*1024*1024"
BREAKING schemas/removed zcode-protocol-v4/snapshot.ts#sessionPhaseSchema: removed schema/type export
(exit=1 — BREAKING expected)
```

Report items (see `open-repo-drift-report.json`): the rename is caught in BOTH
layers it surfaces in (schemas + enums), the additive channel lands additive,
all three consumed removals/changes classify BREAKING. Watch evidence:
`open-repo-watch.json`.

## Leg B — Mac bundle sensor (hash tripwire → extract → classify → notify)

```
$ cp /Applications/ZCode.app/Contents/Resources/glm/zcode.cjs /tmp/phase5-rehearsal/bundle-state/zcode.cjs
$ sed -i '' 's|maxFragments:64|maxFragments:48|;s|WorkspaceClosed:4012|WorkspaceClosed:4014|' \
    /tmp/phase5-rehearsal/bundle-state/zcode.cjs
$ ZCODE_ASAR=/Applications/ZCode.app/Contents/Resources/app.asar \
  ZCODE_CJS=/tmp/phase5-rehearsal/bundle-state/zcode.cjs \
  DRIFT_STATE_DIR=/tmp/phase5-rehearsal/bundle-state \
  bash tools/sensors/bundle-sensor.sh
relay surface written: /tmp/phase5-rehearsal/bundle-state/relay-current.json (closeCodes=6, adapterLimits=5, envelopeFields present=15/15, errorCodes present=4/4)
verdict=BREAKING added=0 changed=2 removed=0 breaking=2 bodyChanged=0
BREAKING adapterLimits/changed maxFragments: 64 -> 48
BREAKING closeCodes/changed WorkspaceClosed: 4012 -> 4014
notified: sent -> /tmp/phase5-rehearsal/bundle-state/notifications.log
(sensor exit=1 — 1 = BREAKING expected)
```

Notification evidence (`notifications.log`, real macOS OSD attempt — `sent`):

```json
{"at":"2026-10-07T17:33:54.691Z","title":"ZCode bundle drift","message":"ZCode desktop updated — BREAKING relay drift detected. Review /tmp/phase5-rehearsal/bundle-state/drift-report.json before connecting.","osd":"sent"}
```

Tripwire + report evidence (`bundle-sensor.log`):

```
[2026-10-07T18:33:49+0100] tripwire: bundle hash changed (asar 232e913ea13d -> 232e913ea13d, cjs fad4c35c4c36 -> b58f9708ae3e) — extracting relay surface
[2026-10-07T18:33:54+0100] drift checked: verdict=BREAKING exit=1 report=/tmp/phase5-rehearsal/bundle-state/drift-report.json
```

## Quiet paths (heartbeat semantics)

- Re-running the sensor against the same mutant keeps firing BREAKING (exit 1,
  re-notifies) — intended nag-until-actioned behavior: the committed baseline is
  quieted only by the documented by-hand regeneration after review.
- Running against the REAL installed bundle (hashes equal the committed
  baseline): `heartbeat: bundle unchanged (asar=232e913ea13d cjs=fad4c35c4c36)`,
  exit 0 — see the last line of `bundle-sensor.log`.

## Also exercised in this phase (not re-transcribed)

- By-hand AAP-1 no-drift acceptance vs the live reference clone:
  `verdict=ADDITIVE added=0 changed=0 removed=0 breaking=0 bodyChanged=0`, exit 0.
- `node --test tools/drift/surface.test.mjs tools/drift/relay.test.mjs
  tools/sensors/sensors.test.mjs` = 20/20 pass (includes the watch script
  against real seeded local git repos and the notifier log contract).
