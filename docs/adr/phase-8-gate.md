# Phase 8 Gate — Notifications & Background

| | |
|---|---|
| **Phase** | Phase 8 — Notifications & Background, §43.2 |
| **Status** | **PASS WITH KNOWN LIMITATIONS** |
| **Date / time** | 2026-10-02 18-20-00 BST (final verification run) |
| **Verifier** | `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); toolchain: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, Compose BOM 2026.09.00, WorkManager 2.10.0, Robolectric 4.17 (`@Config(sdk=[30])`); device leg: ATD emulator API 30 (arm64, `zcode-atd`) |
| **Preconditions** | Phase 7 PASS (2026-10-01 01-40-00 BST); §24 broker architecture; §25 background strategy; Phase 5 reconciliation machinery (`NotificationReconciler`, RecoveryCoordinator `NotificationRecordPort`) |

---

## Scope delivered (subphases 8.1–8.5)

| Subphase | Delivered |
|---|---|
| **8.1 NotificationBroker + channels** | NEW MODULE `core:notifications` (§10.3/§40.1): §24.2 channels created at first run (permissions HIGH / task-status DEFAULT / progress SILENT reserved / live-connection LOW); **NotificationPlan** (pure §24.1 event→channel/privacy mapping, §24.3 dedupe keys, §9.9 foreground suppression); **NotificationBroker** (the SOLE decision point: records with §16 7-day retention + INV-5 bound, foreground/background transitions with the §15-S13 background re-post of still-open attention items, S12/S13 reconciliation through the domain `NotificationReconciler`, record persistence via a DataStore-proto port implemented in `data` — the §9.2 REPO→NOTIFY edge, additive `notification_records.proto`); **AndroidNotificationSink** (tray delivery, MINIMAL/CONTENT privacy levels, per-endpoint group summaries at ≥2, §24.7 deep-link tap PendingIntents); **NotificationWiring** (app adapter: interaction store → APPROVAL/USER-INPUT events, task projection → terminal transitions with first-emission seeding, phase → CONNECTION_LOST while tasks run + clear on restore, lifecycle → foreground/background). UI never touches notification APIs (§24.1 acceptance). |
| **8.2 Deep links** | Manifest `zcode://task/{taskId}` VIEW filter (browsable) + `singleTask` (taps re-enter the live instance); `DeepLinks.intake` (pure parser; malformed never offers); nav-graph resolution through the domain `DeepLinkRoute` matrix against the SAME projection snapshots the task list renders (no second query path); UNPAIRED holds and re-validates after pairing (§24.7); TASK_GONE/WRONG_ENDPOINT land on the list with explicit copy (S14/S17); **consume-before-resolve** hardening (an effect restart can never double-navigate — found by the instrumented fleet, regression-hardened). |
| **8.3 FGS validation spike** | `LiveConnectionService` (§24.5): `specialUse` on API 34+ (manifest type + subtype property), typeless pre-34, opt-in only, started from the foreground only, low-priority indicator ("Connected to \<desktop\> — tap to disconnect"), **30-min auto-timeout**, non-sticky; Settings toggle with POST_NOTIFICATIONS request (33+). §25.2 chain executed as far as the environment allows; **`remoteMessaging` evaluated: NOT APPLICABLE** (reserved for system-default messaging handlers). Decision recorded in **ADR-017**. Evidence: `docs/evidence/phase-8/` (service record, `fg F/S/FGS` priority hold, forced deep-Doze survival). |
| **8.4 WorkManager auto-reconnect** | `ReconnectProbeWorker` (§25.3/§15-S5): unique periodic work, **≥15 min + NETWORK_CONNECTED** (quota-compliant by construction, asserted), opt-in via the additive `AppPreferences.autoReconnectProbe` (proto field 5, §32.3), dead-process no-op, process-bridge probe hook wired to `hub.networkAvailable()`. The app's ONLY periodic work (§29). Settings switch + §24.4 honest copy. |
| **8.5 Battery report** | `docs/battery-phase8.md`: §29 method restated; emulator-class mechanism evidence (FGS priority hold through forced deep Doze, service record, bounded 30-min exposure); **per-UID battery deltas NOT MEASURABLE on the ATD** (`Total cpu time reads: 0`) and the §28.1 physical-device targets **NOT EXECUTED** (no physical device attached) — documented deviation per the phase's own acceptance wording ("within §28.1 targets **or documented deviation**"). |

## Acceptance criteria (§43.2 Phase-Level) — evidence

| Criterion | Evidence | Result |
|---|---|---|
| §48 U20 — notifications | Broker unit suite (33 tests: §24.1 mapping, §24.3 dedupe/collapse, suppression, S12 reconciliation, retention, bound); Robolectric sink suite (channels idempotent, stable ids, group summaries, MINIMAL content-empty); wiring events over the REAL stores; device: notification posted to the REAL tray with the exact §24.1 category-only text | PASS |
| §48 U21 — tap lands on the correct task/interaction; stale-safe | Domain matrix (`DeepLinkRouteTest`, pre-existing) + app intake suite (manifest filter match, parse matrix, pending-link lifecycle, 8 tests) + device legs: valid link → conversation of THE seeded task (composer + title asserted, back returns to list); stale/missing link → graceful list + explicit notice, activity survives; malformed never crashes | PASS |
| FGS decision recorded | **ADR-017** (`specialUse` adopted behind opt-in with conservative defaults; `remoteMessaging` not applicable; Play-policy leg deferred with the Play track; OEM leg NOT EXECUTED — Q12 open) | PASS |
| Battery evidence published | `docs/battery-phase8.md` + `docs/evidence/phase-8/*`; numeric targets honestly NOT EXECUTED (environmental limitation) | PASS (documented deviation) |
| §26.6 security gate (Phase 8 = notification surfaces) | Re-walk below — no new HIGH | PASS |

## Exit-gate checks (§43.2 per-subphase)

- 8.1 broker suite green — 33 `core:notifications` tests + tray-level Robolectric suite ✓
- 8.2 deep-link matrix green — domain + intake + device stale/valid legs ✓
- 8.3 go/no-go evidence + ADR — spike artifacts + ADR-017 ✓ (OEM/battery legs: not executed, documented)
- 8.4 suite green — quota-compliance assertions + enqueue/cancel on device ✓
- 8.5 report in `docs/` — with measured-what/existed-what split ✓

## Tests executed (final verification runs, 2026-10-02)

- **JVM/Robolectric: 489/489 PASSED** (baseline at phase start: 434; +55). New suites: `core:notifications` 33 (Plan 8, Broker 9, StoreDiffs 7, Sink 6, Worker 3), app `DeepLinkIntakeTest` 8, data +3 (additive pref round-trip, notification-record store round-trip/tolerance).
- **Instrumented (ATD API-30): 9/9 PASSED** — new `Phase8InstrumentedTest` (2: the broker→tray→channel + U21 routing journey; the stale-safe link journey) + the full pre-existing fleet (Pairing E2E ×3 incl. the settings scroll fix, Phase6, Phase7, PersistenceTiming, HistoryPagination).
- **APK artifact**: `app/build/outputs/apk/debug/app-debug.apk` — `versionCode=27, versionName=0.8.0-phase8` (rebuilt fresh post-version-bump).

## Defects discovered during implementation — fixed with regression coverage

1. **Wiring collectors keyed on the connection PHASE** — a phase change races `persistEndpoint`, so a `flatMapLatest` bound to `endpoint==null` stayed dead for the session (the notifications/`knowsTask` source never received events). **Fix:** collectors key on the hub's ENDPOINT flow — the same lesson `DefaultTasksPort` recorded in Phase 6. (Caught by the instrumented journey.)
2. **Deep-link double-navigate race** — the nav effect re-runs when `paired` moves; a run cancelled mid-resolve left the link pending and a restart pushed a SECOND conversation entry (the DESTROY-from-never-CREATED teardown crash). **Fix:** consume the link BEFORE resolving (restarts can never re-navigate).
3. **Resolver divergence from the visible truth** — the deep-link resolver re-queried tasks through vault+Room and observed an empty projection the task list was not showing (`tasks=0` while the row rendered), resolving a live task as TASK_GONE. **Fix:** resolution reads the SAME projection snapshots the wiring collectors maintain (`knowsTask`/`knowsInteraction`) — §24.7's "authoritative projection" is the one the app actually renders.
4. **FGS tap intent NPE on service-first start** — `Intent(this, LAUNCHER_ACTIVITY)` crashed when no activity class had been registered. **Fix:** null-guarded; the REQUIRED FGS notification posts without the convenience tap target.
5. **Pre-existing pairing E2E broke by growth** — the new Notifications section pushed `settings_unpair` below the fold; the compose test's off-screen `performClick` landed as a BACK press (Settings popped; the confirm dialog never appeared; bisected, base-lined green at HEAD^). **Fix owned by this phase:** the test scrolls the danger row into view first (`performScrollToNode`) — the standard below-the-fold pattern; no assertion weakened. `SettingsScreen`'s scroll container gained `settings_scroll` for exactly this.
6. **`data`'s Robolectric startup inherited WorkManager's initializer** (via the new `data → core:notifications` manifest merge) — the S12 recovery-matrix case tripped its 5 s channel-init wall budget at app startup. **Fix:** test-only manifest removes the `androidx.startup` InitializationProvider for the data module's unit tests (WorkManager is never exercised there). Matrix class then green 3/3 consecutive runs; the one S13 flake observed immediately after the timing shift (expected DISPLAYED, saw REQUESTED) did not reproduce in 3/3 runs and matches the phase-6-documented pre-existing matrix load-sensitivity (name-diffed; bisected stable at HEAD^).

## Changes to prior-phase tests (owned by this phase)

- `PairingE2eInstrumentedTest`: scroll-to-before-click on `settings_unpair` (above); no assertion changed.
- `DataStoreStoresTest`: +2 tests (additive pref field; notification-record store) — additive coverage only.
- `MainActivity.container` widened `private` → `internal` (instrumented wiring assertions; no product surface change).

## Known limitations (accepted)

1. **Physical-device battery numbers do not exist yet.** §28.1's ≤6%/30-min streaming target and the 8 h FGS on/off delta were NOT executed (no physical device in this environment; the ATD provides no per-UID accounting at all — `Total cpu time reads: 0`). Reported as not verified in `docs/battery-phase8.md`; release-adjacent ritual per §36 (never CI, INV-15-adjacent honesty).
2. **The aggressive-OEM-ROM leg of the §25.2 spike did not run** (no OEM hardware) — Q12 stays open. Contained by conservative defaults: opt-in only, foreground-only start, non-sticky, 30-min auto-timeout, reconnect-on-wake primary. Deep-Doze behavior was exercised on the emulator (forced idle; the FGS process survived at foreground priority).
3. **Play-policy validation for `specialUse` is deferred with the Play track** (MVP ships GitHub-Releases/Obtainium per §37, which is not Play-bound) — recorded in ADR-017 with the re-evaluation trigger.
4. **No quick-approve notification action** — §24.2 allows an Approve action only behind an explicit low-risk allow-list + user opt-in (default OFF). The shipped surface is Open-only; the default-off posture is the safety decision, and the action design arrives with the risk-class allow-list (Phase 11 candidate).
5. **Background workflow milestones** post nothing yet (the `progress` channel exists per §24.1; no workflow surface exists until Phase 10+).
6. **Wrong-endpoint disambiguation is single-endpoint honest** — `existsOnOtherEndpoint=false` until Phase 10's registry (§11.6); the WRONG_ENDPOINT path is fully implemented and tested at the domain/parse level.

## Invariant verification (§42 — Phase 8 delta)

| Invariant | Verification |
|---|---|
| INV-1 (never authoritative) | Notifications derive from authoritative stores/events only; S12 dismissal is reconciliation, never mutation |
| INV-2/6 (UI never owns transport; capability-branching) | New module graph: `features` never see `core:notifications`; the broker consumes domain models + store ports; no transport types anywhere new |
| INV-3 (no secrets in logs/notifications) | Tray content is category strings + optional task TITLE (CONTENT level only); redaction suite green; broker logs carry keys/counts only |
| INV-4 (idempotent commands) | Unchanged — notifications never submit anything; no quick-approve action exists |
| INV-5 (bounded) | Broker record set ≤256 (oldest-dropped, tested); probe period ≥15 min (tested); retention 7 d (tested) |
| INV-11 (state screens) | Notification-off state degrades silently at the tray with truthful records; offline approvals remain blocked in-app (§17.6) |
| INV-12/16 (identity/paths) | Deep links are validated opaque ids; routing never trusts paths |
| INV-14 (fail-closed) | Deep links: malformed dropped at intake, unknown interaction → OPEN_TASK_INTERACTION_STALE (never actionable), TASK_GONE never fabricates; notifications never carry approval decisions |

## §26.6 security-gate re-walk (Phase 8 delta: notification surfaces, deep links, FGS)

| Threat | Delta review |
|---|---|
| T2/T4 (credential reading; debug leaks) | No secret ever enters a notification, a record, or a log line (category text + optional user-authored task title only); channels/records in `noBackupFilesDir`; redaction suite green |
| T3 (overlay/screen-scrape of approvals) | Unchanged — notifications only OPEN the app; approvals are answered solely on the fail-closed in-app card (§20.4) |
| T6 (cloud backup) | Notification records live under `noBackupFilesDir` with `allowBackup=false`; extraction rules unchanged |
| T8 (lock-screen content leak) | Strengthened: MINIMAL default posts category-only text with an empty BigText; approvals NEVER carry tool/path/title text at ANY level (§24.1 verbatim); content level is the §27 user control |
| T14 (phone-context approvals) | No auto-approve path exists (§24.2 default-off honored as "no action button"); the card carries the full decision context as before |
| NEW — hostile deep links | `zcode://task/…` is a routing HINT, not an authorization (§24.7): intake validates shape, resolution runs against the paired+authoritative projection, malformed/stale/missing ids land on explicit graceful routes (device-tested); firing the URI without pairing lands on the pairing gate and re-validates after pairing |
| NEW — FGS abuse surface | Service is non-exported, started from the foreground only, posts a contentless indicator, auto-stops at 30 min; declares `specialUse` + subtype justification in the manifest (§25.2/§27 rationale recorded in ADR-017) |

No new HIGH residual; **security gate PASS**.

## ADR / correction references

- **ADR-017** (`docs/adr/adr-017-phase8-background-execution.md`): FGS service-type decision (`specialUse`), `remoteMessaging` N/A evaluation, WorkManager + androidx-core adoption, `core:notifications` module + the `data → core:notifications` / `app → core:notifications` graph edges (both pre-drawn by §9.2/§10.3).
- **`[CORRECTED 2026-10-02]`** (§41.6): §43.2-8.3's exit gate names "ADR-013" — that number was consumed by Phase 3's toolchain ADR before Phase 8 ran; the FGS decision is ADR-017 (next free number). No semantic change.
- architectural.md: Phase 8 completion recorded in §43.2 and the status header (§41.8 authorization: phase-gate bookkeeping only).

---

**Phase Exit Decision: PASS WITH KNOWN LIMITATIONS** — all five subphase exit gates green with executed evidence; limitations 1–6 accepted and documented; §26.6 gate PASS (no open HIGH); no §47 No-Go condition triggered. Phase 9/10 prerequisites are met.
