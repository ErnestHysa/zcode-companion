# pass0 campaign LEDGER — the living campaign index (EDITABLE)

> **What this file is.** The campaign's working state: one row per finding, current
> status, fix shape, evidence pointer. The report
> (`pass0-full-verification-and-desktop-gap-2026-11-12.md`) is APPEND-ONLY and holds the
> receipts; THIS file is the only editable campaign artifact. Every session reads this
> file FIRST. When this file and the report disagree, the LATER report part wins and the
> ledger gets corrected — never the other way round.
>
> **Why it exists.** Ten passes re-narrated the same open defects because state lived
> only in 2,500 append-only lines. The ledger ends that: agents stop re-finding, the
> roadmap is generated mechanically from these rows when Ernest says so, and each future
> fix phase pulls its rows (with evidence anchors) from here — the vc52 campaign's
> 85-row roadmap + 85/85 audit worked exactly this way.

## How every session uses this file (the contract)

1. **Read this file, then the report's LAST part** (latest pass record + its fix-order +
   coverage-honesty sections). Then the rig recipes: report Part C + every pass's
   rig-learnings section (A0/D.A0/E.A0/F.A0/G.A0/H.A0/I/K.A0) — follow them exactly.
2. **STEP 0, always first tool action — desktop digest check** (see Desktop-of-record
   below). Changed → run the DELTA procedure before any app leg.
3. **Discovery pass**: work the DISCOVERY BACKLOG top-down (next unchecked items), then
   drive the latest coverage-honesty leftovers. Record, don't fix app code (mock/rig
   fixes ARE allowed — they unblock arms; commit them with finding IDs). **Live legs
   require the REAL rig** — emulator + MockZCode relay + on-device driving with
   receipts (uiautomator/screenshots/logcat/DB pulls); a docs-only or REA-only pass
   does NOT satisfy a live-leg backlog item.
4. **Fix round** (only when Ernest asks for a roadmap phase): execute the named phase's
   rows test-first, run suites, rebuild the APK, one row ≥ one commit
   `fix(<ID>): …`, tick the row here to FIXED with the commit sha.
5. **Append the pass part to the report; update THIS file** (statuses, backlog ticks,
   campaign-state block). Append-only rules for the report; earlier parts untouched.
6. Re-manifestations of OPEN rows get ONE line in the new part + a stamp bump in the
   row's `Stamps` column here — never a re-narration.

**Stop condition / roadmap trigger:** a full pass that opens ZERO new rows AND a fully
ticked backlog ⇒ tell Ernest the discovery campaign is saturated; he decides when to
build the roadmap (appended to the report bottom, vc52 format: phases by subsystem,
P2-first, exit gates, per-row Accept criteria).

## Campaign state (update every pass)

- Report: `docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md` —
  Parts A–K = passes 0–8. **Latest: pass 8 (Part K, 2026-11-14 ~01:20).**
- Repo HEAD at last pass: `8f7e93c8` (main, clean). APK of record: sha256 `ad449d7d…` /
  md5 `530ba4ff…`, versionCode 53 / 1.3.2 (byte-identical across the whole series;
  re-verified 2026-11-14 at `app/build/outputs/apk/debug/app-debug.apk` — docs-only
  commits since the build).
- Desktop of record: **/Applications/ZCode.app 3.18.1.10376, asar sha256 `ec475e6e…`,
  383,832,025 B** (3.14.3 → 3.18.1 is the audited delta; extraction recipe: re-verify
  digest → `@electron/asar` extract → host index.js sha256 prefix `e31718b8…`).
- Open rows: **35 F/G (20 F + 15 G) + 9 smalls + 3 standing** (counts below per table).
- Backlog: 3 of 8 items touched (1 done, 2 partial — see Backlog).
- Rig left by pass 8: emulator `zcode-a16` + gen6 relay :18100 + DIRECT mock :18200,
  app paired to both, Live connection OFF. **AUDITED 2026-11-14 (later): the emulator
  is DOWN; the two relay java listeners (:18100/:18200) are still alive serving dead
  sessions — `pkill -f MockRelayMainKt` both before the next gen or gen1 hits
  BindException.**

### Desktop-of-record DELTA procedure (run whenever the digest check fails)

Re-extract the new asar → re-run the standing census greps (asar digest, host index.js
sha, service/channel registry, method tables, settings-domain counts, clientHello bits,
V2 device plane markers `/api/v1/remote/device` + `RelayV2IncomingMessage`, gen-ui
markers `genUiContext`/`genUiService`, mobile-push keys, bundled plugin count, theme
count) → diff every number against this ledger → open NEW G-rows for desktop gains →
append a DELTA part to the report. Public releases (zai-org/ZCode) carry no changelog
bodies after 3.14.3 — the census IS the changelog.

---

## F-rows — app defects

| ID | P | Status | Mechanism (one line) | Fix shape | Depends on | Evidence | Stamps |
| --- | --- | --- | --- | --- | --- | --- | --- |
| F-P8-A | 2 | OPEN | cold start w/ DIRECT selected: deferred relay `persistEndpoint` rewrites `_endpoint` after resolve — silent abandonment, recovery dropped by `sourceKind` filter, "First sync failed" vs healthy direct, Retry completes it | guard `persistEndpoint` against overwriting a resolved non-relay selection; retry must reconnect the transport the UI shows; pins: Robolectric race harness + "no `hub.switched` without user tap" | — | Part K.A2 | 1 |
| F-P0-B | 2 | OPEN | rows reconcile sweep STALEs state-plane-delivered PERMISSION interactions (the two reconcilers fight); card renders terminal-but-actionable | ONE LINE: `known.permission != null` exemption at `InteractionCoordinator.kt:697` + pin answering from BOTH planes | — | Part E.A2.4 (root cause) | 6 stamps (passes 3,4,5,6,7,8 — pass 7 was the report's "5th re-manifestation"; pass 2 was the root-cause pin, not a stamp) |
| F-P3-A | 2 | OPEN | pinned pending-stack budget starves EVERY interaction card body to zero height (question body, hook items; LazyColumn unbounded main axis kills `weight(1f,fill=false)`) | card-agnostic: raise/scroll the pinned budget or render bodies outside it; pin hosts cards in a live `LazyColumn{item{}}` harness | — | Part D.A2 (first), G.A2.1 (card-agnostic) | 7 (passes 1,2,3,4,5,6,8 — pass 6 was the report's "5th re-manifestation") |
| F-P8-C | 2 | OPEN | conversation timeline cannot be DRAG-scrolled at HEAD (any conversation; only taps work; AX scrollable=true; strip tap + find-jump still work) — mechanism open (unbounded LazyColumn measurement or gesture claim) | live-shape Compose harness to hunt the mechanism; restore drag-scroll | — | Part K.A2 | 1 |
| F-P8-E | 2 | OPEN | stick-to-bottom follow has NO user-scroll release — every row/streaming change re-clamps to bottom ≤7 s (`followBottom` cleared only by find-jump) | `followBottom=false` on user drag leaving `atBottom` + latched jump-to-latest FAB; fix WITH F-P8-C | F-P8-C | Part K.A2 | 1 |
| F-P6-A | 2 | OPEN | permission ghost-split: store keeps DISPLAYED rows the card plane dropped (F-P0-B) AND generation-orphaned ghosts accumulate monotonically (4 ghosts / 26 h; broker posts shade notifications with NO answer surface) | card half = F-P0-B fix; store half = resolve generation-orphaned interactions in a reconcile sweep; ghost fixtures `/tmp/pass8/db/p8-2.db` | F-P0-B (card half) | Part I (headline), K.A3 | 2 (passes 6,8 — pass 7 never re-stamped it) |
| F-P6-B | 2 | OPEN | with armed stack, timeline renders ZERO rows (pinned budget + expanded queue panel eat the window) | re-balance pinned/timeline budget; queue collapse = F-P8-D | F-P8-D | Part I | 1 (+F-P8-C context) |
| F-P7-A | 2 | OPEN | Approvals-screen inbox answer strands: `submitFromStore` → RETRYABLE_KEPT (needs a session machine); banner remedy "open the task" unactionable on DIRECT (foreign workspace). Inbox answers FINE over relay (pass 8) — endpoint/machine-context-specific | interaction-scoped submit (shade path proves the shape); validate task row before advising | — | Part J | 2 (7,8) |
| F-P7-B | 2 | OPEN | shade action answered while connection down → interaction FAILED, no retry/queue/no user face; the COMMON path with Live-connection OFF | queue behind reconnect (inbox RETRYABLE_KEPT semantics) or visible "not delivered" re-post | — | Part J | 1 |
| F-P1-B | 2 | OPEN | stale-generation interactions stay answerable 50+ min and ACK against a moved-on desktop; closeStored sweep misses rows (2 of 5) | expire stored interactions on generation/workspace identity mismatch + mock unknown-id error arm | — | Parts D.A2, E.A2.5 | 3 (1,2,3) |
| F-P3-B | 2 | OPEN | automation "Go to session" NEVER loads its transcript — 4 skeleton rows forever, zero transcript wire calls | openRun must hydrate via **`readSessionMessages` — ALREADY CONSUMED** (ConversationPort.kt:318): wiring only, plus honest empty state | — | Part F.A2, K.B1 (verb found) | 1 |
| F-P3-C | 3 | OPEN | inbox ACK's resolution echo DROPPED when conversation unsubscribed (`gateway.drop … no subscription record`) | retain last-N deltas per session / subscribe-on-ACK / optimistic local resolve | — | Part F.A2 | 1 |
| F-P5-A | 3 | OPEN | refused git reads render SILENT-EMPTY sections (headers, zero rows, no error) — pinned refusal faces don't manifest on the live load path | typed-refusal face on section-empty + pin hosting the LIVE shape | — | Part H.A2 | 1 |
| F-P2-B | 3 | OPEN | post-unpair resume card claims "Paired · Connect" from a fakeLink (`PairingViewModel.kt:50-56`) — structurally guaranteed | reduce to a genuine unpaired state (no fakeLink) | — | Part F.A2 (code pin) | 1 |
| F-P0-C | 3 | OPEN | file viewer renders SILENT BLANK panel on refused read (`FilesScreen.kt:225-237`) — no error, no retry | typed error face + WARN-to-UI | — | Parts A, E.A2.6, F.A2.5 | 3 (0,2,3) |
| F-P8-D | 3 | OPEN | queue panel has NO collapse affordance (always-expanded; desktop's has one) + header clipped under the sticky strip | two-way header toggle (desktop parity) + strip/header overlap fix | — | Part K.A2 (supersedes Part I's probe) | 1 |
| F-P8-B | 3 | OPEN | switching to an already-CONNECTED endpoint throws ISE `connect() is the first-connection path` | `connectDirect()` → `recover()`/no-op when state==CONNECTED | — | Part K.A2 | 1 |
| F-P8-F | 3 | OPEN | Take-photo intent failure (API-36 camera denial) surfaces NOTHING in the attachment flow | typed honest face for intent-launch failure (silent-blank family) | — | Part K.A2 | 1 |
| F-P0-E | 3 | OPEN (unresolved) | ANR during rapid rig input into direct-endpoint form; causality unproven (app trace rotated away) | observation; rig recipe recorded (chunk DELs ≤8, pull `/data/anr/trace_*` immediately) | — | Part A.A2 | 1 |
| F-P1-D | 3 | OPEN (observation) | open conversation polls workflowRuns+plans every ~8 s even idle/backgrounded | subscribe-on-open + deltas (capability already declared) or ≥30 s cadence | — | Part D.A2, K.A3 | 2 (1,8) |
| F-P0-D | 3 | RETRACTED | pass-0 terminal-attach silent no-op — unreproduced 5× (passes 2,3,4,5,8); Context-sheet route variant = N-BM-3 | keep typed-face work as hardening only | — | Part E.A2.8 | — |
| F-P2-A | 2 | REFUTED→SUPERSEDED | selection persistence REFUTED as a defect (survives force-stop, app-kill, full emulator reboot — passes 7, 8); the REAL root is F-P8-A's silent in-memory abandonment | closed in favor of F-P8-A | — | Part J, K.A0.7/K.A3 | — |
| F-P1-C | 3 | REFUTED | "push arm delivered nothing" = DB-pull race artifact (pass 2); arm healthy, later rig-fixed | — | — | Part E.A2.3 | — |
| F-P1-A | 2 | ABSORBED | question-card zero-height body → merged into F-P3-A (card-agnostic) | — | — | Part D.A2 | — |
| F-P0-A | 2 | ABSORBED | conversation-card refusal → root-caused pass 2 → merged into the F-P0-B/F-P3-A family | — | — | Parts A, E.A2.4 | — |
| F-P4-A | 2 | FIXED (rig) | mock push patch structurally excluded its own interaction | `pushPendingPermission` builds from unfiltered list + patch-content pin — commit `2b12fc46`, 37 mock tests green | — | Part G.A2.2, H.A1 | — |
| N-BK-1 (rig half) | 2 | FIXED (rig) | git fixtures in wrong field vocabulary (blank paths, +0−0, duplicated tabs, oid-vs-hash) | contract vocabulary rewrite — same commit `2b12fc46` | — | Part G.A2.3, H.A1 | — |

**Open F count: 20** (18 defects + 2 observations) · FIXED: 2 (both rig) · RETRACTED/REFUTED/ABSORBED: 6.

## Current fix-order (consolidates K.D + the carried rows; supersedes every report fix-order)

1. **F-P8-A** → 2. **F-P0-B** (unblocks F-P6-A's card half) → 3. **F-P8-C + F-P8-E
   together** (frozen timeline: drag-scroll mechanism hunt first, then the follow
   release; unblocks the user-row actions leg) → 4. **F-P6-A store half** (ghost sweep;
   fixtures `/tmp/pass8/db/p8-2.db`) → 5. **F-P8-D** (queue collapse; re-test F-P6-B
   after) → 6. **F-P3-A** (card-agnostic pinned budget) → 7. **F-P3-B** (wiring-only
   hydrate) → 8. **F-P3-C** → 9. **F-P7-A / F-P7-B** → 10. **F-P5-A → F-P2-B → F-P0-C
   → F-P8-B → F-P8-F** → 11. **F-P1-B → F-P1-D** → 12. smalls (N-BM-1 → N-BM-3 →
   N-BK-1 app half → N-BJ-1 → N-BK-2 → N-BL-1 → N-BM-2 → K-small-1/2) → 13. parity
   queue per the G table + the opportunistic list.

## G-rows — desktop-vs-phone gaps (parity queue)

| ID | P | Status | Gap (one line) | Consume spec (exact) | Evidence |
| --- | --- | --- | --- | --- | --- |
| G81 | 3 | OPEN | gen-ui consume plane — desktop renders generated interactive UI (HTML in plugin-sandbox), phone zero files | 4-part spec: declare `genUiContext:true` hello bit; answer `runtimeCapabilities`; accept/forward the genUiContext array (max 100 × {path,state}) on send; render components (expand/collapse/tweak/submit/copyImage/retry + `genUi.desktopOnly` face). Minimum = honest "open on your desktop" face | Part F.B1 (complete) |
| G94 | 3 | OPEN (NEW pass 8) | worktree creation/fork plane (ManagedWorktree, 14 methods) — start-a-task-in-a-worktree has no phone counterpart, not even read-only | minimum face: `listCreations` + `onDidChangeCreations`/`onDidSettleCreation` | Part K.B1 |
| G91 | 3 | OPEN | Plugins decode-only — `listPlugins` STRICT schema decoded, NO production screen consumes it (18 desktop bundled plugins invisible) | read-only Plugins viewer in the settings-viewer family (Usage/Memory pattern): list + enable-state only | Part I.2.4 |
| G93 | 3 | OPEN | V2 mobile-push plane has NO phone receiver (device registration `{device_mid}→{device_sid}`, Bearer JWT event POST, dispatcher w/ typed skip reasons) — when the relay socket is down, the desktop cannot reach the phone AT ALL | ARCHITECTURAL: push receiver + registration face; needs a cloud-transport decision first. Residual narrowed: kind/state enums live in zcode.cjs (agent process) | Part J.2.1, K.B2 |
| G89 | 3 | OPEN | hook review-reason enum (11 typed reasons) undecoded — phone card says only "N pending · M hooks"; saveHooks authoring remote-servable | decode half = safe minimum (reason enum + typed faces); saveHooks = consume/no-action decision | Part H.B3 |
| G82 | 3 | OPEN | memory viewer — desktop Memory UI (`memoryEnabled` toggle + project-memory viewer); phone zero surfaces | read-only mirror = `listProjectMemories` + `readProjectMemoryFile` (of 6 exact methods) | Part G.B3, H.B1 |
| G85 | 3 | OPEN | broadcastService vocabulary complete but consume/no-action UNDECIDED (`mobile-push:run/event` = V2 realtime arm; `state:*` sync family; task-groups claim protocol) | decision row; mobile-push:* feeds the G93 architecture | Part F.B3 |
| G60 | 4 | STANDS | plan-claim/rewards/purchase desktop-only (`codingPlan` 2,463 renderer hits; `claimManualPlan`; phone zero claim surfaces) | unchanged since pass-31; re-measured 3.18.1 | Part B.B2, G.B7 |
| G83 | 4 | OPEN | clientScenes — ONE verb (`getSnapshot`); smallest consume on the board | one-verb read mirror | Part H.B1 |
| G84 | 4 | OPEN | outputStyle mirror — `listStyles` + `getActiveStyle` (of 7) | read-only mirror | Part H.B1 |
| G95 | 4 | OPEN (NEW pass 8) | feedback/support-intake plane (Feedback, 15 methods incl. device-snapshot + log-archive attach) — phone diagnostics export could feed `attachLogsFromExport` | inventory row → consume decision | Part K.B1 |
| G86 | 4 | OPEN | share-code IMPORT plane (`importShare` + progress phases) — desktop-only | inventory first | Part F.B4 |
| G90 | 4 | OPEN | coding-plan RESET trio (`requestCodingPlanResetOpportunity`, `useCodingPlanReset`, `markCodingPlanResetHistoryRead`) — phone renders quota reset read-only | decode + typed faces | Part H.B1 |
| G87 | 4 | OPEN | real-time voice-input ASR plane (ticketed sessions, chunk upload) — phone relies on OS IME dictation (dead on API 36, F-34 class) | inventory row (build unlikely) | Part G.B4 |
| G88 | 4 | OPEN | 22 desktop themes vs phone's 5 (shipped zai pair = true parity) | cosmetic; inventory only | Part G.B5 |
| G92 | 4 | OPEN | `configOptions` decode-only (select/boolean + current values, one adapter from a phone render) | adapter + render | Part I.2.5 |
| WATCH-4 | — | STANDING | V2 device-registration/pairing plane shipped on desktop; phone pairing stays V1 — flip is BREAKING (draft PR, human promotes) | AAP BREAKING path | Parts B, D.B2, K.B1 |
| Settings depth | — | STANDING | 51 settings domains (modelProvider 3,217 keys …); phone covers the conversation/notification/privacy/appearance slice; `workspaceFileSearch`(90)/`dynamicWorkflow`(45) are decode-vs-UI candidates; shortcuts/browser/migration desktop-legit | G92-class candidates flagged | Part J.2.3, B.B3 |

### Parity/opportunistic queue (not defects; ride alongside consumer work)

- **Terminal streaming wiring** — `terminalListenData/Exit` transport-ready, ZERO feature
  callers (`V4Plane.kt:1699-1709`); one subscription + render in the terminal feature.
- **BashOutput taxonomy line** — one `put("BashOutput", TASK_CONTROL)` in ToolKindTaxonomy.
- **`inputOrigin:"mobile"`** on send — one line, desktop-side attribution.
- **`inputFormat` bits** (supportsImage/Video/Pdf) — decode in modelOptions for the
  "image attachments to the agent" ask.
- **Unread-badge sync** (`setTaskUnread`) + **systemService.info** endpoint-detail face.
- **Archived-task DELETE verbs + controller-V4 signal family** — protocol-exact spec
  behind auto-archive/unread (Part K.B1).
- **Draft-promotion pair** (`promoteDeferredDraftSession`/`closeDeferredDraftSession`) —
  consume/no-action decision (desktop twin of the phone's local draft plane).
- **`DeviceSyncState.getSnapshot`** — the read twin of the G93 plane (getSnapshot is a
  FAMILY: DeviceSyncState/ClientConfig/Skills).
- **gitCheckpoint createCheckpoint** — unconsumed verb behind the "create one before
  risky changes" empty state (K.A2 small: wire a button or reword).
- **NO-ACTION forever list** (never "port" these): sync ×5, credential/oauth/onboarding/
  fileWatcher/userConfig, AppControl (CUA driver), CuaPipSession lifecycle, Bots
  authoring depth (read-only by ADR), git WRITE half (stage/commit/push — read-only
  posture), pluginManagement 17 methods, voice-input service.

**Open G count: 15 + 3 standing** (G60, WATCH-4, settings depth).

## N-rows — smalls

| ID | P | Status | One line | Evidence |
| --- | --- | --- | --- | --- |
| N-BM-1 | 3 | OPEN | load-earlier strip total is a stale subscribe-time snapshot ("420 of 339") | Part J, K.A3 |
| N-BM-3 | 3 | OPEN | Context-sheet → Terminal route: Attach is a silent no-op (main terminal screen attach works — sheet variant dead) | Part J |
| N-BK-1 (app half) | 2 | OPEN | History silent-empty needs a "No commits yet" empty state (rig half FIXED `2b12fc46`) | Part G.A2.3 |
| N-BJ-1 | 4 | OPEN | attachment image previews have NO size/aspect cap (1×1 seed renders ~300×220dp) | Part F.A2, K.A3 |
| N-BK-2 | 4 | OPEN | automation Edit sheet schedule chips clip at phone width | Part G.A2 |
| N-BL-1 | 4 | OPEN | workflow-run Run/Manage icon buttons subtree-unlabeled (TalkBack silence) — ONLY unlabeled clickables found | Part H.A2 |
| N-BM-2 | 4 | OPEN | child-session view: generic title, skeleton-forever on unscripted children | Part J |
| K-small-1 | 4 | OPEN | checkpoint empty state says "create one" with NO create affordance (verb unconsumed) | Part K.A2 |
| K-small-2 | 4 | OPEN | "First sync failed" renders alongside CACHED rows (wording/state nit) | Part K.A2 |
| K-small-3 | 4 | WATCH | one-off terminal route pop (unreproduced — watch, don't chase) | Part K.A2 |
| N-BJ-2 | 4 | REFUTED→FOLDED | unpair IS reachable (Settings bottom, pass 4); fakeLink half folded into F-P2-B | Part G.A2.4 |
| N-BM-4 | — | RIG | Dictate leg kills the emulator (cause unproven); leg undriven | Part J |

**Open smalls: 9 (+1 watch +1 rig).**

## Mock-fidelity debts (rig, not app)

1. Accepts ANY interactionId — add the unknown-id typed error arm (unblocks honest-failure faces).
2. `fault.file.notFound` on seeded `readTextFile` (listing-only seeds).
3. Snapshot doesn't list the once-per-connection PUSHED interaction → rows sweep legitimately STALEs it.
4. Static session-config echo re-asserts the effort chip ("max" after `setThoughtLevel`).
5. Raw optionId labels (`allowOnce`) instead of human labels (desktop sends `label: c.name`).
6. Push arm is CONVERSATION-SUBSCRIBE-anchored (recipe: reconnect with conversation open, lock inside the arming window).
7. `MOCK_REMOTE_DOWN_MS` / `MOCK_MD_FILE` arms exist but were never driven (gen2 append-after-exec bug).

## DISCOVERY BACKLOG (tick when the findings are appended as rows)

- [ ] **1. Desktop Mobile-Remote UI/UX audit** — REA the renderer's remote-control screens
  (device picker, QR pairing, device-sync-state, remote-notification settings, companion
  webview) + their i18n copy; diff each against the phone's pairing/settings/consent UX.
  This is the plane Ernest flagged as "changed quite a lot, UI and UX."
- [~] **2. Channel method tables — REMAINING OPEN:** ModelSelection, ProviderSettings,
  SourceServerWorkspace, ZCodeTask (controller family partially done), ConversationShare,
  CuaPermission, WindowController, MediaPreview (attribution ⚠), WindowProjectChanges (⚠).
  Method: the pass-5/8 walk-back extractor (`return i(…),{…}` before `create*Service`,
  80 KB look-back); ⚠-marked tables need a different anchor. Everything else is DONE
  (~33 tables exact across Parts G–K — 13 from pass 5 + ~20 from pass 8 — plus 2 ⚠ and
  7 no-table services).
- [~] **3. Renderer route/screen inventory — CLOSED as a dead angle** (no URL-route
  vocabulary exists: 2,862 lazy chunks, scene/state-driven nav — Part K.B3; do not
  re-hunt). REMAINING: the **i18n namespace diff** (desktop namespaces vs phone
  strings.xml — the method that found G60/G91/G92) and the **settings-domain diff** for
  the decode-vs-UI candidates.
- [ ] **4. The unassessed clientHello bits** — highspeedCardOwner, sessionLifecycleWorkspace,
  sessionDirectorySource, sessionWorkspaceRouting, independentPlanState, residentRowsRange,
  worktreeCwd, extensionInputSource, conversationQuoteComment, pluginExtensionSummary —
  one grep-trace each: which desktop feature does this gate; consume/no-action row.
- [ ] **5. Named residuals** — G93's kind/state enum values (producer: zcode.cjs agent
  process — one file family); MediaPreview/WindowProjectChanges table attribution (⚠ rows
  above); any "residual (recorded, not chased)" lines in future parts.
- [ ] **6. Coverage leftovers (rolling)** — lockscreen-approve-over-LIVE-connection leg
  (recipe: Live connection ON + conversation open at reconnect + lock inside arming
  window; 3rd pass owing it); user-row long-press ACTIONS (blocked by F-P8-C); question
  card options post-F-P3-A-fix; MOCK_REMOTE_DOWN_MS + MOCK_MD_FILE arms; mention-panel
  tabs Sessions/Skills/Plugins/Agents; Take-photo happy path (impossible on this image —
  stop re-attempting); widget add-to-home (standing stop — `cmd appwidget` has no shell
  path on API 36; RIG-4 binding pin stands); re-drive all FIXED rows against their pins
  once fix rounds start.
- [ ] **7. Phone self-audit expansion** — a11y/TalkBack beyond conversation+settings+home
  (every screen), font 1.3×/density/locale × newer screens matrix, diagnostics-export
  content, settings-search edges, dark/theme × all screens.
- [x] **8. Shipped-content catalogs** — plugin inventory 18 (matches pass-35), slash-20
  parity EXACT (pass 6), settings-domain census done (51 domains, pass 7). DONE unless a
  desktop delta changes the catalogs.

## Prompt templates (paste-ready; the ledger + report tail make them self-adjusting)

**Discovery pass (the default):**
```
Continue the pass0 verification campaign (ZCode Companion Android vs macOS desktop).
Read docs/evidence/pass0-LEDGER.md FIRST, then the LAST part of
docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md, then the rig
recipes (Part C + every pass's rig-learnings). STEP 0: verify /Applications/ZCode.app
version + asar sha256 against the ledger; if changed, run the DELTA procedure first.
STEP RIG (mandatory for every live leg): spin the live emulation — check what the last
pass left running (ledger campaign-state block, `adb devices`, the relay/DIRECT ports),
cold-boot zcode-a16 or reuse what's up, build+install the debug APK at HEAD, launch
MockZCode with the ARMS the backlog item names, pair/connect, and drive the app
ON-DEVICE (uiautomator + screenshots + logcat + DB pulls as arbiter). REA/docs-only
work NEVER satisfies a live leg. STEP 1: work the ledger's DISCOVERY BACKLOG (next
unchecked items; method named there). STEP 2: drive the latest coverage-honesty
leftovers. Never re-narrate OPEN rows (one line + stamp bump); never re-receipt
VERIFIED surfaces unless their code changed. App code: record, don't fix (mock/rig
fixes allowed, committed with finding IDs). Append the new pass part to the report;
update the ledger (statuses, backlog, campaign state). Nonce-anchored generations,
DB pulls as arbiter, nothing pushed.
```

**Fix round (after roadmap phases exist):**
```
Read docs/evidence/pass0-LEDGER.md, then open
docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md at the roadmap,
and implement PHASE <N> ONLY — nothing else. Each row: re-read the finding + its
ledger entry, TEST-FIRST (failing pin per fix, live-shape harnesses where the report
names them), fix, run the module suites (testDebugUnitTest + pure-JVM `test` tasks),
rebuild the debug APK, commit `fix(<ID>): …` per finding, tick the row FIXED in the
ledger with the commit sha. Follow the deliverable-completion rule. Append a short
fix-round record to the report; nothing pushed.
```

**Desktop delta pass (run whenever the desktop updates):**
```
/Applications/ZCode.app was updated. Re-extract the asar and run the DELTA procedure
in docs/evidence/pass0-LEDGER.md: re-run every standing census grep, diff each number
against the ledger, open NEW G-rows (especially mobile-remote/UI/UX planes), re-check
the backlog items the delta invalidates, then diff the phone at HEAD against each new
row. Append a DELTA part to the report + update the ledger. Nothing pushed.
```

## History

- 2026-11-14 — ledger created (from Parts A–K, passes 0–8) by the pass-8 review session,
  then audited against the repo + machine the same day: all spot-checked code pins
  verified TRUE (InteractionCoordinator :694-700, ConversationViewModel :71-74/:1291,
  ZCodeApp :182-196, ConnectionHub :672-685/:1650, MockZCode :1332-1340, PairingViewModel
  :50-56, V4Plane :1451/:1699-1709, ConversationScreen :453/:1043-1072, WriteComponents
  :676, FilesScreen :225-237, ConversationPort :318, APK hash ad449d7d/530ba4ff).
  Corrections this audit: open-F count 19→20 (18+2 obs), open-G standing 2→3 (G60),
  F-P0-B stamps corrected to passes 3-8 (pass 7 restored, pass 2 reclassified as the
  root-cause pin), F-P3-A stamps 6→7 (pass 6 restored), F-P6-A stamps 3→2 (pass 7
  withdrawn), consolidated fix-order section added, emulator-found-down noted in the
  rig line, backlog 2→3 items touched, "39+ tables" → ~33 exact + 2 ⚠ + 7 no-table.
