# Phase 11 Gate — Polish (perf, a11y, i18n, widget, voice experiment)

| | |
|---|---|
| **Phase** | Phase 11 — Polish (§43.2-11) |
| **Status** | ✅ COMPLETE — PASS WITH KNOWN LIMITATIONS |
| **Date/time** | 2026-10-03 23-49-25 IST |
| **Verifier** | model `account:zai-individual-coding-plan/GLM-5.3-Flash`; harness ZCode (session tooling); tests executed by Gradle 9.7.1 + JUnit4/Robolectric/Macrobenchmark 1.5.0 on this machine; macrobenchmarks + instrumented suite on the local ATD API-30 emulator |
| **Preconditions** | Phases 6–10 features complete ✅ (gates `phase-6…10-gate.md`) |

## 1. What was built (exact scope)

### 11.1 Performance hardening (§28.2; "Macrobenchmark gates; budgets or tickets")
- **New `:baselineprofile` module** (`com.android.test`, self-instrumenting,
  ADR-018): `StartupBenchmark` (cold start → task list, StartupTimingMetric,
  5 iterations) and `ConversationScrollBenchmark` (FrameTimingMetric over a
  400-row conversation, continuous fling, 3 iterations) — the two §28.2-named
  gates. MockZCode runs in the BENCHMARK process on loopback (INV-15); the app
  is seeded once through a benchmark-only, `BuildConfig.BENCHMARK_SEEDING`-
  guarded intent (identical §12.4-shaped storage writes: Tink-sealed token +
  registry row + persisted selection; inert constant-false in every other
  build type, including release).
- **App `benchmark` build type** (non-debuggable, debug-signed) + benchmark
  source set (loopback cleartext only).
- **CI**: `.github/workflows/macrobenchmark.yml` — scheduled nightly + manual
  dispatch on an API-30 AOSP-ATD emulator (§36 "self-hosted runner
  (scheduled/nightly)" cadence).
- **Budgets vs measured** (`docs/perf-phase11.md`, evidence
  `docs/evidence/phase-11/`): cold start → task list median **1,070.7 ms** /
  max-of-5 1,694.6 ms (targets ≤ 1.2 s P50 / ≤ 2.0 s P95); conversation scroll
  frameDurationCpuMs **P50 2.9 / P95 4.1 / P99 5.3 ms** (≤ 16.7 ms budget).
  ALL numbers EMULATOR-CLASS (no physical reference device here — the library
  EMULATOR error is suppressed with that exact caveat); reference-device legs
  are tracked tickets T-P11-1…6 with acceptance criteria. 5k-line diff budget
  stays PASS (Phase 9's 27 ms regression test re-run green in the full suite).

### 11.2 Accessibility (§30 six acceptance items)
1. **Semantics labels** — `AccessibilityAuditTest` (features:tasks, 5 tests) +
   `ApprovalsA11yTest` (features:conversation, 3 tests): every clickable node
   in the task list and approvals inbox must carry text or a
   contentDescription (deep semantics-tree walk, zero unlabelled tolerated).
2. **Touch targets ≥ 48 dp (56 dp approval actions)** — scripted size audits
   on both surfaces; FOUND + FIXED real violations: header IconButtons were
   40 dp (→ 48 dp), the clickable ConnectionStatusLine was 35 dp (→ 48 dp min
   height), approval inbox Allow-once/Deny were default 40 dp (→ 56 dp min
   height, both the card and the inbox), composer action IconButtons (attach /
   attach-doc / voice / stop / send) 40 dp (→ 48 dp).
3. **200% font scale** — `rendersOperable_at200PercentFontScale`: rows render
   at `fontScale = 2f` and taps still land (asserted through a real click).
4. **Traversal order** — scripted leg: clickable-node order in the semantics
   tree == visual top→bottom order (both surfaces). The per-release manual
   TalkBack audit remains a release ritual (no TalkBack on ATD) — documented.
5. **Reduced motion** — new `rememberReducedMotion()`/`isReducedMotion()`
   (core:ui) reading the system animator-duration-scale; snap-scroll replaces
   `animateScrollToItem` in conversation + terminal under reduced motion;
   behavior tests prove the flag reflects the OS setting.
6. **Status never color-only** — status glyphs added (`taskStatusGlyph`,
   icon+label+rail), structural test asserts the textual label is present
   beside every colored rail; connection line carries its text label.

### 11.3 Internationalization (§31)
- **All rendered UI strings externalized** across app, core:ui,
  core:notifications, features:{tasks, conversation, terminal, files, git,
  pairing} (pairing was already externalized in Phase 3). Deliberately NOT
  externalized: the diagnostics REPORT body and licenses component list
  (developer-facing exported artifacts — noted in the resource files).
- **zh-CN** (`values-zh-rCN`) for every string table incl. notification copy
  and widget plurals; ICU plurals for relative times + widget counts.
- **Notification copy** refactored spec-first-lite: `NotificationPlan` takes a
  pure `NotificationCopy` (default = the English §24.1-table copy; all prior
  pure tests unmodified); `ResourceNotificationCopy` bound in the wiring.
- **Relative time localized** at the UI boundary (`relativeTimeLabel`,
  core:ui) replacing the hardcoded `RelativeTime`/`ageOf` English labels.
- **Tests**: `RelativeTimeZhCnTest` + `ApprovalsZhCnTest` (real zh-rCN
  resource resolution), `RtlPseudolocaleSnapshotTest` (ar-rXB locale + forced
  RTL-direction rendering + operability).

### 11.4 Glance widget (§43.2-11.4)
- `PendingApprovalsWidget` + receiver + provider XML in `:app` (ADR-018):
  **reads the §24.1 broker's record flow ONLY** — the broker gained an
  observable `recordsFlow` mirror of its record set; the widget's updater
  pushes on change (event-driven, §29 no polling). No transport, no
  repositories, no projection access from the widget (INV-2/6).
- Count = unseen APPROVAL/USER-INPUT records (§24.3 open-interaction set);
  honest empty copy; whole-widget tap → the app; localized + pluralized.
- Tests: pure derivation (seen/informational/connection records excluded),
  localized headline + plural, broker flow emission on event/dismiss (4/4).

### 11.5 Voice input experiment (§43.2-11.5; ADR-019)
- Platform `SpeechRecognizer` behind `VoiceInputPort` (NoVoiceInput /
  PlatformVoiceInput); availability-gated mic affordance in the composer;
  final-result dictation appends into the §17.4 draft path; honest error copy.
- Feasibility measured: zero dependency cost; on the ATD image the
  recognition service is absent → affordance hidden (gating verified);
  composer behavior proven by 4/4 Robolectric tests with a fake port.
  **Live recognition E2E NOT EXECUTED** (no GMS/recognition-capable device in
  this environment) — recorded in ADR-019 with the Phase-12 device leg.

## 2. Acceptance criteria (§43.2-11) — evidence

| Criterion | Evidence | Result |
|---|---|---|
| 11.1 exit: Macrobenchmark gates; budgets or tickets | benchmarks RUN GREEN (`:baselineprofile:connectedBenchmarkAndroidTest` BUILD SUCCESSFUL, 2/2 tests; evidence `docs/evidence/phase-11/benchmark-*`); budgets measured emulator-class, reference-device cells → tickets T-P11-1…6 with acceptance criteria (`docs/perf-phase11.md`); nightly CI workflow committed | PASS |
| 11.2 exit: §30 six acceptance items green | 9 new a11y tests green (5+3+1 reduced-motion set); real 40/35 dp violations found and FIXED to 48/56 dp; glyphs + reduced-motion infrastructure landed; TalkBack manual audit documented as release ritual | PASS (with the documented manual-audit ritual) |
| 11.3 exit: zh-CN + pseudolocales; RTL snapshots | zh-rCN resource-resolution tests green; ar-rXB RTL rendering + operability green; all rendered strings externalized (en/zh); AGP-9 pseudolocale DSL removal documented `[CORRECTED 2026-10-03]` in §31 with the Robolectric CI leg as the substitute | PASS |
| 11.4 exit: widget reads broker state only; suite green | `WidgetStateTest` 4/4; structural reading path = `NotificationBroker.recordsFlow` only; INV-2/6 respected (no transport anywhere in the widget path) | PASS |
| 11.5 exit: feasibility measured; ADR if adopted | ADR-019 ACCEPTED (experimental, availability-gated, zero-dep); feasibility table measured; 4/4 composer voice tests; live-recognition device leg documented as not executed | PASS |

**Phase exit criterion ("all §28/§30/§31 items green")**: §28 — the two
macrobenchmark gates run and are under target emulator-class; every
non-executable device cell is an explicit ticket, not a silent pass. §30 —
all six items scripted/implemented (manual TalkBack audit = release ritual).
§31 — externalization + zh-CN + plural/relative-time rules + RTL leg green.
Verdict: PASS WITH KNOWN LIMITATIONS (below).

## 3. Full-suite evidence

- **JVM (all modules, forced re-run)**: `gradle cleanTest test --continue` —
  **BUILD SUCCESSFUL; 611 tests, 0 failures, 0 errors**
  (baseline 596 at Phase-10 exit → +26 new phase tests across 7 suites, minus
  randomized-count variance in existing property suites). Evidence:
  `docs/evidence/phase-11/phase11-jvm-full-suite.log`.
- **Instrumented (ATD API-30)**: `gradle :app:connectedDebugAndroidTest` —
  **11/11 green** (the full pre-existing on-device journey suite; no
  regressions from the i18n/a11y/widget/voice changes). Evidence:
  `docs/evidence/phase-11/phase11-instrumented.log`.
- **Macrobenchmarks**: 2/2 green on the emulator (17m40s run incl. APK
  install; startup 5 iterations, scroll 3). Evidence files above.
- **APK artifact**: `app/build/outputs/apk/debug/app-debug.apk` —
  `versionName=0.11.0-phase11 (versionCode 32)`, freshly assembled
  (aapt-verified).

## 4. Field-caught defects found & FIXED during the phase (each with a regression test)

1. **Voice affordance ignored availability for injected ports** — the override
   path skipped the availability gate (`VoiceInputTest.voiceButton_
   hiddenWhenUnavailable` failed against the first implementation); fixed by
   gating BOTH paths; test now green.
2. **§30.2 real violations**: 40 dp header/composer IconButtons, 35 dp
   clickable connection line, 40 dp approval actions — found by the new audit
   tests, fixed to 48/56 dp, tests green.
3. **TaskListScreen LazyColumn key collision residue** — none this phase
   (pre-existing field fix verified by the suite).

## 5. Known limitations (accepted, documented)

1. **Reference-device performance legs not executed** — no physical §35.6
   device in this environment; all §28.1 numbers are EMULATOR-CLASS; tickets
   T-P11-1…6 carry the acceptance criteria (`docs/perf-phase11.md`).
2. **TalkBack manual audit** — scripted traversal-order checks green; the
   per-release manual TalkBack walkthrough remains a release ritual (no
   TalkBack on ATD images).
3. **RTL leg is composition-level** — Robolectric resolves the ar-rXB locale
   but does not plumb pseudo-bidi into Compose's layout direction; the
   mirrored-layout + operability assertions run with the direction provided
   explicitly; platform pseudo-bidi plumbing is an on-device leg (Phase-12
   canary).
4. **Live voice recognition not executed** — no recognition-capable device
   here (ADR-019); the affordance's absence-path is what this environment
   verifies.
5. **Widget updates require a live process** — after process death the widget
   re-syncs on the next broker mutation; no push/FGS-coupled refresh exists by
   design (§24/§25 honesty rules).
6. **§31 "pseudolocale test build in CI"** — AGP 9 removed
   `pseudoLocalesEnabled`; `[CORRECTED 2026-10-03]` note added to §31; the CI
   leg is the qualifier-pinned Robolectric suite; on-device pseudolocale
   selection rides the Phase-12 canary.
7. **Diagnostics report body + licenses component list stay English** —
   developer-facing exported artifacts, deliberately out of the UI-string
   externalization (noted in the resource files).

## 6. Invariant verification (§42 walk — deltas only; all 16 hold)

- INV-2/6: the widget path touches only `NotificationBroker.recordsFlow`; no
  transport types above core:transport (module graph unchanged except the new
  test-only `:baselineprofile`); features grew zero transport deps.
- INV-3: no new log/backup surface; benchmark seeding logs the URL HOST-FREE
  shape (`url.take(40)` of a loopback mock URL, fixture token only); no
  secrets introduced.
- INV-5: `recordsFlow` mirrors the existing bounded record set (≤ 256).
- INV-8/14: no protocol-surface changes at all this phase (Phase 11
  non-scope held — zero new protocol code).
- INV-9/13/15/16: unchanged; benchmarks use the loopback mock only.
- INV-10/11/12: untouched paths; the new screens (widget) carry localized
  labels; identity rules untouched.

## 7. ADR / correction references

- ADR-018 — Glance + androidx.benchmark dependency additions (§8.2 mandate).
- ADR-019 — voice-input experiment adoption (§43.2-11.5 mandate).
- §31 `[CORRECTED 2026-10-03]` — string home (core:ui, pure-JVM core:common)
  + AGP-9 pseudolocale DSL removal (§41.6 process).

## 8. Field fix round (2026-10-04, vc33) — device-caught defects

Ernest's Samsung S24 (SM-S918B, API 36) field session against desktop 3.14.4
caught the following; each fixed with a regression test (red-green verified) in
this round:

1. **Conversation layout collapse ("an overlay blocks the chat")** —
   `SessionModeChips` chained `.fillMaxWidth()` UNDER the caller's
   `weight(1f, fill = false)`: when the session-config echo arrived (1–2 s after
   open — the first render was fine), the block was capped at the weighted
   share; the chips wrapped one character per column to hundreds of dp tall, the
   header row swallowed the screen, the CenterVertically-aligned Files/Git/
   Terminal chips landed mid-screen and the weight(1f) timeline collapsed to
   zero. Invisible to every suite because all of them passed `config = null`.
   FIX: the chips row respects the incoming constraint; the header uses TWO
   compact rows (echo chips full-width; Files/Git/Terminal right-aligned below);
   every chip label is single-line ellipsized; entries externalized
   (`files_entry`/`git_entry`/`terminal_entry`).
   REGRESSION: `SessionChipsLayoutTest` (geometry: single-line heights, header
   stack ≤ 72 dp, model chip ≥ 40 dp, timeline ≥ 120 dp) — RED against the
   original structure (zero-width model chip, Δ=109.5 dp), GREEN with the fix.
2. **Watchdog escalated with a stale base → fault.subscription.notOwned** —
   the relay paused a fresh subscription's snapshot delivery (the vc24 class);
   the watchdog fired correctly (84 s stall) but `escalate()` re-used the
   reducer's HYDRATED JOURNAL WATERMARK from the previous connection as the
   resync base; the server rejected it and the single re-kick chance was wasted
   (device log: `recovery.escalate … base=mur1xz62…/1255` → `notOwned` →
   `escalateFailed`). Also: the watchdog's frame stamp was GLOBAL (one streaming
   session blinded it to all others).
   FIX: per-session frame stamps (armed at attach — a stalled FIRST snapshot is
   now covered), per-session resync rate-limit stamps, and any escalation whose
   base is not the CURRENT connection's ack-stamped epoch FORCES a snapshot (the
   only base the server honors; a forced snapshot also re-kicks the paused
   delivery). `ChannelV4Plane.resyncSession` now records the returned
   subscription (subscribe/resync symmetry — post-resync frame routing ran
   against a retired generation). `gateway.drop` observability for silently
   unmatched topic frames (§34).
   REGRESSION: `StreamWatchdogStaleBaseTest` ×4 (stale-base forces snapshot;
   current base keeps resume; completed tasks never escalated; per-session
   stamps) — RED on the stale-base cell without the guard, GREEN with it.
3. **Not a defect (verified from the same log):** the Sep-30 LazyColumn
   duplicate-key crash in the retained "last crash" section predates the vc13
   fix (workspace-prefixed keys) — stale evidence, already fixed; the three
   10 s reconnect timeouts while backgrounded are Samsung powerSave network
   suspension (§15-S3 reconnect-on-wake recovered cleanly on foreground).

Suite evidence: JVM full matrix 616 tests with 1–2 MOVING contention flakes in
`ConnectionHub*Test` (same class, different test per run; 23/23 green ×2 in
isolation — the documented Phase 6/9/10 load class), instrumented 11/11 on the
ATD API-30 emulator. Artifact: `app-debug.apk` **0.11.1-phase11 (versionCode 33)**.

## 9. Field fix round 2 (2026-10-04, vc34) — canary session defects

Ernest's second S24 field session (vc33, desktop 3.14.4, production relay) — this
IS the §43-12-style compatibility canary in early form. Four defects fixed:

1. **Task duplication into another workspace (the big one).** The desktop's
   bootstrap `initialViewState.activeTaskId` and `activeWorkspaceKey` are
   INDEPENDENT fields: the active task can live in a different workspace than
   the active workspace key (field log: `bridge.open ws=…/android-native
   task=sess_53e16b04` where the task lived in …/pragma). `workspace-bridge-open`
   with that mismatched pair triggers the desktop's resume semantics = the task
   FORKS into the wrong workspace (idle duplicate; original keeps running).
   FIX (`RelayTransport.openBridgeInternal`): the bridged workspace is resolved
   FROM the bridged task via the bootstrap task list (§7.11); when the named
   task is NOT in the list its workspace is unverifiable and the desktop-named
   pair is bridged as-is (round-5 verified contract — asserted by
   `RelayTransportFreshDiscoveryTest`, which caught my first overreach).
   REGRESSION: `RelayTransportBridgePairConsistencyTest` ×2 (mismatched pair →
   the TASK's workspace; unlisted task → named pair preserved). Mock extended:
   the strict bridge validator accepts pairs consistent with the bootstrap list.
2. **Model/mode switch "doesn't change anything".** Three switch commands were
   ACCEPTED by the desktop (`port.switchModel ×3`) yet the picker state never
   moved: the outcome was fire-and-forgotten and there was no echo overlay.
   FIX: ack status logged verbatim (`outcome=/ackStatus=`); an ACCEPTED switch
   applies the §14.2 command-issuer overlay (`manager.applyLocalConfigOverlay`
   — reflected immediately, replaced wholesale by the runtime echo, INV-1);
   non-acceptance (blocked/stale/refused) surfaces a §33 composer notice
   (ViewModel no longer drops outcomes).
3. **Attachment upload "doesn't go through at all".** The upload started inside
   a reconnect window and hung on a dead socket (`attachmentBeginV4 failed:
   channel closed 1006` at 10:21:13); the failure never reached the user (the
   ViewModel discarded the UploadResult). FIX: §17.6-parity offline gate BEFORE
   the transaction (instant honest chip), upload/switch outcomes surfaced via
   composer notices, and failed chips carry a one-tap RETRY (payload retained
   on the failed chip only — INV-5-bounded; nulled on commit/remove).
4. **resync notOwned loop** (the vc32 fix's own long tail): with
   `baseIsCurrent=true` the desktop STILL refused resyncs (`notOwned` ×5, once
   per watchdog window, forever — the same workspace mismatch as (1) poisoning
   server-side subscription resolution). FIX: when a resync is refused by the
   server (serverSpoke fault), the escalation falls back to a FRESH SUBSCRIBE
   (§15-S10's "resync failed → full snapshot", the subscribe being the only
   server-honorable re-anchor); verified RED without the guard.
   Also: the hub's stale `lastError` (bridge-degraded) is now cleared on every
   CONNECTED (it kept rendering "Sync problem" over a healthy connection).

Suite evidence: JVM 620 tests — the 2 failures in the full-matrix run were (a)
the known `ConnectionHubWritePathTest` moving contention flake (9/9 green in
isolation) and (b) `RelayTransportFreshDiscoveryTest` — a REAL regression my
first fallback introduced (breaking the round-5 named-pair contract), caught by
the suite and fixed in-round; both bridge classes 4/4 green after. Instrumented
11/11 (ATD API-30). Watchdog/escalation suite now 6/6 (fallback + overlay cells
RED-verified). Artifact: `app-debug.apk` **0.11.2-phase11 (versionCode 34)**.
