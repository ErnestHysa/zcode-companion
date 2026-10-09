# Drift Detection Runbook

The drift-check + Stage-1 detect tier of the auto-adaptation pipeline
(`tools/drift/` + `tools/sensors/`). Scope: DETECT — the verify /
build+publish / client-update tiers live in `tools/ci/`, `tools/release/`,
`tools/canary/` and `docs/runbooks/release.md`.

**INV-15 (normative):** nothing in this pipeline ever connects to the
production relay. The GitHub Action does git-metadata + source-text + JSON
diff only; the Mac sensor hashes and text-scans local bundle files only.
The live relay canary is a separate, Mac-only ritual (§14.2 T2, not built
in this phase).

## Layout

| Path | Role |
|---|---|
| `tools/drift/drift-lib.mjs` | open-repo surface extraction + diff/classification library |
| `tools/drift/extract-surface.mjs` | CLI: generate `surface-inventory.json` from an open-repo checkout |
| `tools/drift/check-drift.mjs` | CLI: diff a fresh extraction vs a baseline → `drift-report.json` (`--kind open-repo\|relay`) |
| `tools/drift/surface-inventory.json` | **GENERATED golden inventory** — reference clone @ `29628c9acdb8` (v3.14.3), 2026-10-07 |
| `tools/drift/consumed-surfaces.json` | hand-curated consumption manifest (config, not a golden artifact) |
| `tools/drift/relay-lib.mjs` | bundle relay-constant extraction + diff (close codes, QI limits, envelope fields, error codes) |
| `tools/drift/extract-relay-surface.mjs` | CLI: extract the relay surface from `app.asar` + `zcode.cjs` |
| `tools/drift/relay-baseline.json` | **GENERATED baseline** — installed desktop 3.14.4 (cjs `fad4c35c4c36ec21…`, asar `232e913ea13d60bd…`) |
| `tools/drift/*.test.mjs` | golden tests (`node --test`) |
| `tools/sensors/open-repo-watch.mjs` | upstream release-tag watch (git ls-remote metadata only) |
| `tools/sensors/notify.mjs` | notification log + best-effort macOS OSD |
| `tools/sensors/bundle-sensor.sh` | Mac bundle sensor (hash tripwire → extract → classify → notify) |
| `tools/sensors/com.zcode.android.drift-bundle-sensor.plist` | launchd template (install command below) |
| `.github/workflows/open-repo-drift.yml` | CI open-repo sensor, cron `*/10` |

## Quickstart (standalone, by hand)

```bash
# no-drift check against the reference clone (acceptance: verdict=ADDITIVE, all zeros)
node tools/drift/check-drift.mjs \
  --shared-root ~/Desktop/DEVPROJECTS/zcode/repo/packages/shared/src

# bundle check against the installed desktop
node tools/drift/extract-relay-surface.mjs \
  --asar /Applications/ZCode.app/Contents/Resources/app.asar \
  --cjs  /Applications/ZCode.app/Contents/Resources/glm/zcode.cjs \
  --desktop-version "$(defaults read /Applications/ZCode.app/Contents/Info.plist CFBundleShortVersionString)" \
  --out /tmp/relay-current.json
node tools/drift/check-drift.mjs --kind relay --current /tmp/relay-current.json
```

Exit codes: `0` ADDITIVE/no drift · `1` BREAKING · `2` usage/IO error.

## `drift-report.json` schema

Both legs emit the same envelope (v1):

```json
{
  "schemaVersion": 1,
  "kind": "open-repo-drift-report | relay-drift-report",
  "generatedAt": "ISO-8601",
  "verdict": "ADDITIVE | BREAKING",
  "reviewRecommended": false,
  "summary": { "added": 0, "changed": 0, "removed": 0, "breaking": 0, "bodyChanged": 0 },
  "layers": { "<layer>": { "added": [], "changed": [], "removed": [] } },
  "items": [
    { "layer": "…", "kind": "added|changed|removed|body-changed", "name": "…",
      "detail": "…", "consumed": true, "classification": "additive|breaking" }
  ]
}
```

- `verdict` is binary (report §14.2): `BREAKING` iff any item classifies
  breaking; a report with zero items is `ADDITIVE` (no drift).
- `consumed` = the Android app strictly decodes this surface
  (`consumed-surfaces.json` manifest + lib defaults). Removals/changes of
  consumed surfaces are `breaking`; additions are always `additive`
  (INV-8 unknown-tolerance); unconsumed removals/changes are `additive`
  (inventory-only — they cannot break the app, but the inventory should be
  regenerated at the next ritual).
- `body-changed` items (open-repo leg only): a scanned file's digest changed
  with ZERO surface-name drift — invisible to the name surface, so it is
  advisory (`classification: additive`, `reviewRecommended: true`). Suppressed
  for files that already have entry-drift items.
- Relay layers (`closeCodes`, `adapterLimits`, `envelopeFields`, `errorCodes`)
  are consumed by default: a changed/absent constant is `breaking` (wire
  geometry, §33 mapping); a new constant is `additive`.
- Relay reports additionally carry `check.baselineSource` /
  `check.currentSource` (bundle paths + streaming sha256 hashes + sizes +
  desktop version) — the hash tripwire baseline.

### What each open-repo layer fingerprints

Extraction is a deterministic text fingerprint of the structural markers the
upstream sources actually use — not a TS parse:

| Layer | Marker | Entry key → value |
|---|---|---|
| `channels` | `export const X = { … } as const` string maps in `channels.ts` | `X.Key` → string |
| `enums` | `export const N = z.enum([...])` anywhere in the scan set | `N` → member array |
| `slash` | arrays named `*SLASH*|*COMMAND*|*ENTRIES*` of `{name, usage}` objects | command name → usage grammar (string content, or normalized expression text) |
| `schemas` | `export type|interface T`, `export const *Schema` / any `z.`-rhs const | per-file sorted name list |
| `limits` | exported consts named `*VERSION|*LIMIT*|MAX*|*TTL*|*TIMEOUT*|*BUDGET*` (scalar or object) | name → normalized rhs text (`30_000`→`30000`, `1024 * 1024`→`1024*1024`) |

Scan set: all `*.ts` under `packages/shared/src/zcode-protocol-v4/` plus the
curated roots `channels.ts`, `zcode-task-mode-schema.ts`,
`zcode-slash-command-help.ts`, `usage-quota.ts`, `usage-stats.ts`,
`automation-types.ts`. Missing curated files contribute nothing and their
absence surfaces as consumed removals. Body digests (sha256 of
comment-stripped text) + per-entry file provenance are recorded in
`inventory.meta` for the `body-changed` advisory and drift suppression.

Relay extraction families (from `docs/protocol/relay-control-plane.md`,
`[VERIFIED]` v3.14.3 corpus): the six close codes
(`SessionNotFound:4004 … InvalidMobileConnection:4013`), the adapter-limits
object found by parse-ranked key co-location (the real bundle shape is
`QI={maxPhysicalFrameBytes:fc.maxFrameBytes,maxMessageBytes:16*1024*1024,
maxFragments:64,assemblyTimeoutMs:3e4,transportIdMaxChars:
fc.transportEnvelopeIdMaxChars}` — values fingerprint as normalized
expressions, so alias rebindings and size changes both flag),
15 envelope/rpc-frame field literals, and the four relay error codes
(`KICKED`, `AUTH_FAILED`, `INTERNAL`, `WRONG_PARAM`).

## Sensors

### Open-repo leg (GitHub Action, cron `*/10`)

`.github/workflows/open-repo-drift.yml`: watch the upstream release tag
(`git ls-remote` — metadata only) → `actions/checkout` `zai-org/ZCode` at that
tag → `check-drift.mjs` vs the committed inventory → always upload the
drift-report artifact → BREAKING fails the job with a remediation `::error`.
Last-seen-tag state rides `actions/cache`; eviction is safe (first-run
semantics re-baseline silently and the drift-check runs EVERY run regardless
of tag changes). The workflow header is the INV-15 audit note. The action
takes effect when this repo is pushed with Actions enabled.

### Bundle leg (Mac, launchd + WatchPaths)

```bash
sed "s|@REPO_ROOT@|$HOME/Desktop/DEVPROJECTS/zcode/android-native|g" \
  tools/sensors/com.zcode.android.drift-bundle-sensor.plist \
  > ~/Library/LaunchAgents/com.zcode.android.drift-bundle-sensor.plist
launchctl load ~/Library/LaunchAgents/com.zcode.android.drift-bundle-sensor.plist
```

Fires daily at 09:00 and on any change under `/Applications/ZCode.app`
(WatchPaths = the Sparkle-update hook). Env overrides: `ZCODE_ASAR`,
`ZCODE_CJS`, `DRIFT_STATE_DIR` (default
`~/Library/Logs/zcode-android-drift`), `DRIFT_RELAY_BASELINE`,
`DRIFT_NO_OSD=1` (log-only). Unchanged-vs-baseline runs log a heartbeat and
exit 0; a hash change re-extracts, classifies, writes
`drift-report.json` + `notifications.log` in the state dir, and posts a macOS
notification. Exit: 0 additive/heartbeat · 1 BREAKING · 2 extraction failure.

**The committed baseline is never auto-overwritten.** After a desktop update
that reviews clean, regenerate it BY HAND in a reviewed commit:

```bash
node tools/drift/extract-relay-surface.mjs --asar "$ASAR" --cjs "$CJS" \
  --desktop-version <v> --out tools/drift/relay-baseline.json
```

Until then the sensor re-notifies on every fire — that is the intended
nag-until-actioned tripwire behavior.

### Notification log contract

One JSON line per notification:
`{"at":"…","title":"…","message":"…","osd":"sent|skipped|failed: …"}`.
The `osd` field records the real macOS attempt; the log line is the durable
evidence.

## Post-release ritual (until Phase 6 automates it)

1. Sensor fires → read the report.
2. BREAKING: patch affected decoders/fixtures first (rule-5 discipline),
   then regenerate `surface-inventory.json` (+ `relay-baseline.json` if the
   bundle changed) in reviewed commits.
3. ADDITIVE + reviewRecommended: regenerate the inventory in a reviewed
   commit so the next check is quiet.
4. Update `CompatibilityGate.MAX_TESTED_DESKTOP` only after the fixture
   suite is green against the new surface (Phase 6 automates this matrix bump).

## E2E rehearsal

Seeded fake release (local git repo built from the real reference sources with
a +channel / consumed-schema-rename / consumed-limit-bump commit tagged
`v3.14.4`) and a seeded bundle change (mutated copy of the real `zcode.cjs`:
`maxFragments:64→48`, `WorkspaceClosed:4012→4014`) both produce classified
BREAKING reports + `osd:"sent"` notifications; unchanged-bundle runs
heartbeat exit 0. Transcript + reports:
The rehearsal cuts a synthetic upstream checkout from the real reference
sources, seeds a fake release and a mutated bundle, and expects classified
BREAKING reports + notifications (see the workflow and sensor scripts).
