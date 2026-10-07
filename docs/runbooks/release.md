# Release & Auto-Ship Runbook — auto-adaptation Stage 2 (AAP-3/4/5)

Implements the report's §14.2 T2–T4 tiers on top of the Stage-1 drift tooling
(`docs/runbooks/drift.md`). Phase 6 of the android-gaps roadmap.

**INV-15 (normative):** nothing in CI ever touches the production relay. The
only live-relay leg is the Mac canary — an operator-machine ritual, run from
this repo by hand. The rehearsal tooling (MockRelay, gh-recorder) exists so the
whole chain is exercisable with ZERO production side effects.

## Layout

| Path | Role |
|---|---|
| `tools/ci/verify-protocol.sh` | **T2 verify leg**: drift-check → regenerate golden fixtures from the NEW open-repo schemas → full unit suite in a scratch clone. Exit 0 ADDITIVE+green / 1 BREAKING / 2 error. |
| `.github/workflows/protocol-verify.yml` | T2 in GitHub Actions (INV-15 audit note in header). `workflow_dispatch` with `upstream_ref`. |
| `tools/release/decision.mjs` | **T3 decision matrix** (`decideRelease`): ADDITIVE+green+green → auto-ship; BREAKING → agent-PR (ABSOLUTE: never auto-ship breaking); else halt. |
| `tools/release/decision.test.mjs` | Matrix tests incl. the exhaustive never-auto-ship-breaking pin. |
| `tools/release/publish.mjs` | **T3 auto-ship executor**: version bumps → `MAX_TESTED_DESKTOP` bump → signed `assembleRelease` → sha256 → drift-generated notes with the T4 meta block → `gh release create --draft`. Refuses any non-auto-ship decision. |
| `tools/release/draft-break-pr.mjs` | **T3 BREAKING route**: branch + report + PATCH-BRIEF → `gh pr create --draft` → STOP (human approval gates everything after). |
| `tools/release/pipeline.mjs` | The chain orchestrator (T2 → canary → T3 → publish/PR) with a unified run log. |
| `tools/release/rehearsal/gh-recorder.mjs` | Rehearsal `gh` shim: validates + records release/PR calls as JSONL, touches nothing remote. |
| `tools/canary/live-canary.sh` | **Mac live canary** (prerequisite): pair → discover (vc34 guards) → bridge → stream → recover → diagnostics export, with redaction + fixture validation. |
| `spike/.../CanaryStage.kt` | The canary drill as one spike stage (`--stage canary`). |
| `app/.../update/` | **T4 client**: foreground GitHub-Releases update check, gated banner, download + SHA-256 verify, consented install intent. |

## Release signing (AAP-5 prerequisite — Phase-12.1 minimum)

The stable release keystore lives **off-repo** at
`~/.zcode-android-release-keys/` (`zcode-release.keystore` +
`keystore.properties`, both mode 0600, never committed, never in CI). Created
2026-10-07 (Phase 6); cert `CN=ZCode Companion, OU=ErnestHysa`, SHA-256
`731eec01844cda2847aa597c2759222731952717756b93868c68fe91f544c8d2`, validity
30 years. §37's "single stable key from day one — rotation breaks
Obtainium/assetlinks" is why this key must never change. When the properties
file is absent, `app/build.gradle.kts` falls back to the pre-Phase-6 unsigned
release build.

## The chain (one release, end to end)

```bash
# 1. T1 — a new desktop tag appears (open-repo-drift Action fires, or by hand):
node tools/sensors/open-repo-watch.mjs --repo https://github.com/zai-org/ZCode.git \
  --state tools/drift/.watch-state/state.json --out /tmp/watch.json

# 2. T2 — verify (CI: gh workflow run protocol-verify -f upstream_ref=<tag>; local:)
tools/ci/verify-protocol.sh --shared-root <new-checkout>/packages/shared/src \
  --label <tag> --work-dir /tmp/verify-<tag>

# 3. canary (operator, Mac, desktop up; MockRelay for a zero-side-effect rehearsal):
tools/canary/live-canary.sh --qr "$SPIKE_QR_URL" \
  --workspace-match <synthetic-ws-substring> --task-match <canary-task-substring>

# 4. T3 — decide + execute (gh = the real gh in production; the rehearsal shim
#    in CI-less dry runs):
node tools/release/pipeline.mjs --shared-root <new-checkout>/packages/shared/src \
  --label <tag> --canary-report /tmp/.../canary-verdict.json --gh gh

# 5. Human click: promote the DRAFT release on GitHub (publish is never automatic).
```

## Decision matrix (§14.2 T3 — enforced by tools/release/decision.mjs)

| Drift | T2 suites | Canary | Decision |
|---|---|---|---|
| ADDITIVE | green | green | **auto-ship** → signed DRAFT release |
| BREAKING | any | any | **agent-PR** → draft PR + report; HUMAN APPROVAL REQUIRED |
| anything else | not green | not green | **halt** |

The BREAKING row is the roadmap's absolute Deferred-scope gate
("never auto-ship breaking"); `decision.test.mjs` pins it exhaustively (no
input combination with a BREAKING verdict produces an auto-ship decision), and
`publish.mjs` re-verifies the decision before acting.

## T4 client update check (what the phone does)

Foreground check on connection-center entry, only when the connected desktop is
NEWER than `CompatibilityGate.MAX_TESTED_DESKTOP` (no network otherwise). The
honest compat warning BECOMES the update action when a newer release exists
(release `versionCode` > installed, from the release's machine meta block or
`-vcN` tag). "Update now" downloads the release APK, verifies the published
SHA-256, and hands a FileProvider URI to the system installer — the system
installer UI is the user consent (§37). The release meta block the T3 pipeline
writes is the contract:

```
<!-- zcode-android-release-meta {"versionName":…,"versionCode":…,"maxTestedDesktop":…,"apk":"app-release.apk","sha256":…} -->
```

## Post-release ritual

Also run the Stage-1 ritual (`docs/runbooks/drift.md`): regenerate
`tools/drift/surface-inventory.json` from the new checkout, commit, so the next
drift-check has the new baseline.
