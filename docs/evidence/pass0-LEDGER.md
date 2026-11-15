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

> **🛑 SATURATED (2026-11-14, pass 12) — EXTENDED (pass 13) — EXHAUSTED (pass 14) —
> passes 15–17 drove the coverage/mock-debt gated compositions — pass 18 completed the
> G49/G50 matrix and FULLY ADJUDICATED the mock-debt list, ZERO new rows:** the
> delivery menu's two undriven one-shot values are wire-receipted — **"Queue this
> send"** (`requestedDelivery=queue keepQueueAndSend [q-rig-1,q-rig-2]` → the NEW
> debt-#19 server-side HOLD appends q-rig-101 → strip 2→3, desktop-faithful
> `inputAccepted{delivery:"queue"}` semantics) and **"Send now (override queueing)"**
> (`startNow clearQueueAndSend [q-rig-1,q-rig-2,q-rig-101]` — the CAS set carries the
> just-queued id; queue EMPTIED + immediate rows) — joining pass 17's `guide`; the
> run-row Delete's **confirm-face adjudicated NO-DEFECT statically both sides** (the
> desktop confirms only the AUTOMATION-level delete — `automations.delete.title/
> description`; the run-row `automations.runs.delete` has no confirm pair, and the
> phone matches both halves). Mock debts: **#3 CLOSED fixed-by-prior-work** (`2b12fc46`
> — `snapshotPendingInteractions` lists the delivered push; the desktop resolves its
> permission card FROM `snapshot.pendingInteractions`), **#6 CLOSED by-adjudication**
> (subscribe-anchored = the desktop shape; recipe stands), **#7 CLOSED** (driven pass 9),
> **#8 CLOSED by construction** (`6846727d` — MOCK_AUTOMATIONS implies the off-peak
> seed), **#19 OPENED+CLOSED** (the queued-delivery hold + live receipt), **#14
> reclassified STANDING RIG-ENV** — the debt list is fully adjudicated (0 open
> work-debts). Two idle-socket drops recovered honestly (RECOVERING → CONNECTED,
> relay-4/relay-8); **gateway.drop for the whole pass: 0.** Roadmap decision remains
> ERNEST'S.

- Report: `docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md` —
  Parts A–U = passes 0–18. **Latest: pass 18 (Part U, 2026-11-15 ~03:20).**
- Repo HEAD at last pass: `6846727d` (main; the pass-18 `fix(mock)` debts #8+#19;
  docs to follow). APK of record: sha256 `ad449d7d…` / md5 `530ba4ff…`, versionCode
  53 / 1.3.2 (byte-identical across the whole series; the pass-18 build was
  up-to-date — zero app changes).
- Desktop of record: **/Applications/ZCode.app 3.18.1.10376, asar sha256 `ec475e6e…`,
  383,832,025 B** (re-verified at pass-18 STEP 0 — UNCHANGED, no DELTA).
- Open rows: **25 F (23 defects + 2 obs) + 21 G + 3 standing + 13 smalls (+1 watch
  +1 rig)** — pass 18 added NO rows. Stamps: F-P8-D 4 (the always-expanded strip
  visible through both queue-strip legs). First-series WORKING receipts: the
  delivery one-shot `queue` value with the server-side HOLD (U.A1), the delivery
  one-shot `startNow` value + the clear composition (U.A1).
- Backlog: **8/8 DONE**; the pass-17 coverage-honesty driveables are now receipted
  or adjudicated; what remains is standing stops + F-P3-A-gated planes only —
  **the discovery surface stays empty; the mock-debt list is fully adjudicated.**
- Rig left by pass 18: emulator `zcode-a16` UP (PIN-less, wifi ENABLED, screenshot
  protection OFF by persistence), relay gen1 ALIVE (nonce `mock-p18-gen1-c4d5e6`;
  arms SPENT — QUEUE consumed by the A1/A2 sends, INPUT_ROUTING=choice sticky;
  pkill to end), app pid 7843 **CONNECTED on relay-8** (03:12:27) on the synthetic
  conversation (timeline: "p18 startNo" user row + synthetic reply; queue strip
  GONE — the A2 clear), logcat running → /tmp/p18/logcat.log. Launchers
  `/tmp/p18/relay-g1.sh`. New rig learnings in Part U.A0: the IME-open dump-bounds
  artifact cuts BOTH directions (a tap at the dump's composer position hit a queue
  row ~600 px higher once, then the same-geometry menu taps hit true — force-close
  the IME with BACK and verify `mInputShown=false` + bottom-bounds before any
  composer-row tap); dialog-window dump bounds were TRUE with the IME up beneath
  (dialogs render in their own window); `input text` %s still truncates mid-word
  (accept it — wire legs don't care about the draft text); the Edit-sheet Cancel is
  a CLEAN escape (zero wire verbs) when a tap mis-fires into the queue rows.

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
| F-P8-A | 2 | OPEN | cold start w/ DIRECT selected: deferred relay `persistEndpoint` rewrites `_endpoint` after resolve — silent abandonment, recovery dropped by `sourceKind` filter, "First sync failed" vs healthy direct, Retry completes it | guard `persistEndpoint` against overwriting a resolved non-relay selection; retry must reconnect the transport the UI shows; pins: Robolectric race harness + "no `hub.switched` without user tap" | — | Part K.A2 | 1 (+F-P9-A sibling mechanism) |
| F-P9-A | 2 | OPEN | relay killed MID-AUTHENTICATION → transport wedges in AUTHENTICATING permanently: zero sockets, zero ladder, zero logs, 8 min receipted; survives foreground/unlock; NO retry face (cached rows suppress the strip); active-endpoint tap = silent no-op; ONLY escape = cross-endpoint switch (away+back) | AUTHENTICATING watchdog (timeout → RECOVERING) + a stuck-face; pins: Robolectric kill-mid-handshake harness + netstat-zero + "no silent state" assert | — | Part L.A2 | 3 (pass 10: stale-relay variant, M.A3; pass 15: THIRD variant — relay ABSENT at the cold-start connect → 10s TIMEOUT → silent wedge, no ladder/face, force-stop escape; R.A0.3) |
| F-P9-B | 2 | OPEN (NEW pass 9) | typed disconnected-remote face never renders live: hub logs the error (workspace-bridge-error: remote-disconnected, verbatim errorText) but ConnectionCenter shows the generic offline face — the G67 card (`connection_remote_reconnect`, render condition lastError.messageKey==REMOTE_WORKSPACE_DISCONNECTED, ConnectionCenterScreen.kt:296) NEVER renders → the recovery verb workspace-reconnect-request is UI-unreachable; row snapshot stays stale under the arm | trace hub.connectFailed→lastError dropping the messageKey (SESSION-scope bridge error); pin: live-shape harness asserting the card renders with the arm armed | — | Part L.A2 | 1 |
| F-P13-A | 2 | OPEN | the workflowRun delta plane is DEAD on the CLOSE-CYCLE post-recovery path (pass 15 narrowed the row's scope): the detach path drops the local subscription record (relay killed → `recovery.detached (unsubscribed)` — detach() fires ONLY from closeConversation, ConnectionHub.kt:1027-1052 → RecoveryCoordinator.kt:130-149), the recovery re-materializes conversations via `relay.recovered :: subscriptions=N snapshotReplace=true` issuing NO subscribeConversationV4 (relay log empty of subscribes; app sends hello only), so the subscribe-ACK epoch record is never re-stamped → EVERY server-initiated delta frame (workflowRun.updated/removed, kind=ONLINE) arrives at the wire and drops at ConversationGateway.kt:261-274 (`gateway.drop … no subscription record`) for the life of the connection; the declared `workflowRunDeltas:true` (V4Plane.kt:672) is effectively false on that path and the UI stays correct only via the ~8s poll drum (F-P1-D). **STAY-OPEN boundary (pass 15, R.A4): a conversation that stays OPEN through any kill recovers FULLY — the record survives (zero detached lines), the recovery re-subscribes (begin during RECOVERING), the epoch re-stamps, and anchored deltas APPLY (seq=2 +6.07s, seq=3 +22.1s; zero gateway.drop in the whole pass)** | recovery must re-subscribe (or stamp epochs on the snapshotReplace ack) ON THE DETACHED-REOPEN path; pins: relay-log "no subscribe after recovered" + a gateway.drop assert on a post-close-cycle delta; stay-open resilience receipted (pass 15) | — | Part P.A3, R.A4 | 3 (pass 14: the fresh-process happy path RECEIPTED — Part Q.A1; pass 15: the stay-open boundary — deltas live across 2 kills, scope narrowed to the close cycle) |
| F-P0-B | 2 | OPEN | rows reconcile sweep STALEs state-plane-delivered PERMISSION interactions (the two reconcilers fight); card renders terminal-but-actionable | ONE LINE: `known.permission != null` exemption at `InteractionCoordinator.kt:697` + pin answering from BOTH planes | — | Part E.A2.4 (root cause) | 8 stamps (passes 3–9; pass 9's hook-review zero-rows receipt) |
| F-P3-A | 2 | OPEN | pinned pending-stack budget starves EVERY interaction card body to zero height (question body, hook items; LazyColumn unbounded main axis kills `weight(1f,fill=false)`) | card-agnostic: raise/scroll the pinned budget or render bodies outside it; pin hosts cards in a live `LazyColumn{item{}}` harness varying pinned-child count (pass 12: the starvation is composition-dependent — the approval card rendered FULL body on re-entry while question/hook cards vanished) | — | Part D.A2 (first), G.A2.1 (card-agnostic), O.A2 (variance) | 9 (passes 1–9 + 12) |
| F-P11-A | 3 | OPEN (NEW pass 11) | conversation attachment chip has NO error face — a failed read leaves "Loading…" forever (produceState swallows failures; no unavailable state, no retry; recovered here only because the mock self-healed) | error/unavailable state on the chip (F-P0-C family) + Robolectric failing-loader harness; MOCK half FIXED `1e649eab` (eager registration, verified gen3) | — | Part N.A1/N.A2 | 1 |
| F-P11-B | 3 | OPEN (NEW pass 11) | Live-connection window picker's selection never reflects in the row subtitle in-session — Settings prefs projection doesn't re-collect after `preferencesRepository.update` (3 cycles; disk HAS 60 — `preferences.pb` field 8; re-entry renders 60); lesser: force-stop ~90 s after a write lost it (flush killed) | collect prefs via the live dataStore.data flow on the screen (or key the row on the pref) + subtitle-after-update pin | — | Part N.A2 (s36/s37/s40/s41/s44 + the pb pull) | 1 |
| F-P8-C | 2 | RE-VERIFIED-WORKING (pass 10) | conversation-timeline drag-scroll, reported dead at pass 8 HEAD, NO LONGER REPRODUCES at e9a8d206 — drag receipted 3 directions incl. reverse (Part M.A1); mechanism was open, no fix commit identified; the F-P8-E companion is REFUTED (pass 11) | optional drag regression pin for fix rounds | — | Part K.A2 (dead), M.A1 (working) | 1 + pass-10 re-verification |
| F-P8-E | 2 | REFUTED-WORKING (pass 11) | the pass-8 "followBottom cleared only by find-jump" pin was a MIS-READ — the gesture-direction release (scroll to head → release) AND the latched numbered jump FAB (`conversation_jump_latest` + UnreadBadge) both live-receipted at pass 11; remedy commit `4bb678e7` predates pass 8's claim | row stays: keep the release+FAB pins for fix rounds | — | Part N.A1/N.A3 (release, no-re-clamp, badge "4", jump+reset) | 1 |
| F-P10-A | 3 | OPEN (NEW pass 10) | the Commit sheet's Commit action SILENTLY no-ops when hub.activeWorkspaceTarget() resolves null: dialog closes, zero wire verbs (`git.commit` never sent), zero log lines (`gitport.commit` warn never fires), zero user face — the reads in the SAME sheet resolve elsewhere (git.getChanges ok) | resolve the write target the way the reads do (session-scoped) + a typed refusal face on the null-target path (`writeOp` :209 `?: return null` is the silent exit; the onCommit `if (hash != null) … else nothing` at GitScreen.kt:467-478 has no else) | — | Part M.A2 | 1 |
| F-P6-A | 2 | OPEN | permission ghost-split: store keeps DISPLAYED rows the card plane dropped (F-P0-B) AND generation-orphaned ghosts accumulate monotonically (4 ghosts / 26 h; broker posts shade notifications with NO answer surface) | card half = F-P0-B fix; store half = resolve generation-orphaned interactions in a reconcile sweep; ghost fixtures `/tmp/pass8/db/p8-2.db` + `/tmp/p9/db/p9-4.db` (5 unresolved / 83 rows, oldest 2026-11-12 19:59; note: the pass-10 wipe-data cleared the LIVE ghosts — the sweep code is still absent, fresh installs just have nothing to orphan yet) | F-P0-B (card half) | Part I (headline), K.A3, L.A3, O.A2 (census 0 orphans) | 4 (pass 12 census) |
| F-P6-B | 2 | OPEN | with armed stack, timeline renders ZERO rows (pinned budget + expanded queue panel eat the window) | re-balance pinned/timeline budget; queue collapse = F-P8-D | F-P8-D | Part I | 3 (pass 12: again, s05/s09) |
| F-P7-A | 2 | OPEN | Approvals-screen inbox answer strands: `submitFromStore` → RETRYABLE_KEPT (needs a session machine); banner remedy "open the task" unactionable on DIRECT (foreign workspace). Inbox answers FINE over relay (pass 8) — endpoint/machine-context-specific | interaction-scoped submit (shade path proves the shape); validate task row before advising | — | Part J | 2 (7,8) |
| F-P7-B | 2 | OPEN | shade action answered while connection down → interaction FAILED, no retry/queue/no user face; the COMMON path with Live-connection OFF | queue behind reconnect (inbox RETRYABLE_KEPT semantics) or visible "not delivered" re-post | — | Part J | 2 (pass 13: the SAME no-face outcome receipted for the typed server-rejection trigger — stale-generation answer → `status:"rejected"` → store FAILED + outbox FAILED_TERMINAL, card just clears, no notice; Part P.A5) |
| F-P1-B | 2 | OPEN | stale-generation interactions stay answerable 50+ min and ACK against a moved-on desktop; closeStored sweep misses rows (2 of 5) | expire stored interactions on generation/workspace identity mismatch + mock unknown-id error arm | — | Parts D.A2, E.A2.5 | 5 (pass 13: the gen5a card survived the gen5b snapshot swap and stayed answerable — AND the sibling question row got RESOLVED by the same swap, so the sweep is PARTIAL in both directions; Part P.A5) |
| F-P3-B | 2 | OPEN | automation "Go to session" NEVER loads its transcript — 4 skeleton rows forever, zero transcript wire calls | openRun must hydrate via **`readSessionMessages` — ALREADY CONSUMED** (ConversationPort.kt:318): wiring only, plus honest empty state | — | Part F.A2, K.B1 (verb found) | 2 (pass 17: re-manifested on the SEEDED run's unscripted session — no subscribe, no transcript wire, skeleton-forever (T.A2, s15b); **NARROWED**: the MANUAL run's workspace session LOADS its transcript via the subscribe snapshot — the skeleton is the unscripted-session shape only) |
| F-P3-C | 3 | OPEN | inbox ACK's resolution echo DROPPED when conversation unsubscribed (`gateway.drop … no subscription record`) | retain last-N deltas per session / subscribe-on-ACK / optimistic local resolve | — | Part F.A2 | 1 |
| F-P5-A | 3 | OPEN | refused git reads render SILENT-EMPTY sections (headers, zero rows, no error) — pinned refusal faces don't manifest on the live load path | typed-refusal face on section-empty + pin hosting the LIVE shape | — | Part H.A2 | 1 |
| F-P2-B | 3 | OPEN | post-unpair resume card claims "Paired · Connect" from a fakeLink (`PairingViewModel.kt:50-56`) — structurally guaranteed | reduce to a genuine unpaired state (no fakeLink) | — | Part F.A2 (code pin) | 1 |
| F-P0-C | 3 | OPEN | file viewer renders SILENT BLANK panel on refused read (`FilesScreen.kt:225-237`) — no error, no retry | typed error face + WARN-to-UI | — | Parts A, E.A2.6, F.A2.5 | 3 (0,2,3) |
| F-P8-D | 3 | OPEN | queue panel has NO collapse affordance (always-expanded; desktop's has one) + header clipped under the sticky strip | two-way header toggle (desktop parity) + strip/header overlap fix | — | Part K.A2 (supersedes Part I's probe) | 4 (pass 18: always-expanded through both queue-strip legs, s08/s09) |
| F-P8-B | 3 | OPEN | switching to an already-CONNECTED endpoint throws ISE `connect() is the first-connection path` | `connectDirect()` → `recover()`/no-op when state==CONNECTED | — | Part K.A2 | 1 |
| F-P8-F | 3 | OPEN | Take-photo intent failure (API-36 camera denial) surfaces NOTHING in the attachment flow | typed honest face for intent-launch failure (silent-blank family) | — | Part K.A2 | 1 |
| F-P0-E | 3 | OPEN (unresolved) | ANR during rapid rig input into direct-endpoint form; causality unproven (app trace rotated away) | observation; rig recipe recorded (chunk DELs ≤8, pull `/data/anr/trace_*` immediately) | — | Part A.A2 | 2 (pass 10: the wipe-data first-boot ANR storm — app input-dispatch ANR in the same record as GMS/phone startup ANRs + a 15s watchdog block; M.A3) |
| F-P1-D | 3 | OPEN (observation) | open conversation polls workflowRuns+plans every ~8 s even idle/backgrounded | subscribe-on-open + deltas (capability already declared) or ≥30 s cadence | — | Part D.A2, K.A3 | 4 (pass 12: 52 polls in the gen2 relay log) |
| F-P0-D | 3 | RETRACTED | pass-0 terminal-attach silent no-op — unreproduced 5× (passes 2,3,4,5,8); Context-sheet route variant = N-BM-3 | keep typed-face work as hardening only | — | Part E.A2.8 | — |
| F-P2-A | 2 | REFUTED→SUPERSEDED | selection persistence REFUTED as a defect (survives force-stop, app-kill, full emulator reboot — passes 7, 8); the REAL root is F-P8-A's silent in-memory abandonment | closed in favor of F-P8-A | — | Part J, K.A0.7/K.A3 | — |
| F-P1-C | 3 | REFUTED | "push arm delivered nothing" = DB-pull race artifact (pass 2); arm healthy, later rig-fixed | — | — | Part E.A2.3 | — |
| F-P1-A | 2 | ABSORBED | question-card zero-height body → merged into F-P3-A (card-agnostic) | — | — | Part D.A2 | — |
| F-P0-A | 2 | ABSORBED | conversation-card refusal → root-caused pass 2 → merged into the F-P0-B/F-P3-A family | — | — | Parts A, E.A2.4 | — |
| F-P4-A | 2 | FIXED (rig) | mock push patch structurally excluded its own interaction | `pushPendingPermission` builds from unfiltered list + patch-content pin — commit `2b12fc46`, 37 mock tests green | — | Part G.A2.2, H.A1 | — |
| N-BK-1 (rig half) | 2 | FIXED (rig) | git fixtures in wrong field vocabulary (blank paths, +0−0, duplicated tabs, oid-vs-hash) | contract vocabulary rewrite — same commit `2b12fc46` | — | Part G.A2.3, H.A1 | — |

**Open F count: 25** (23 defects + 2 observations; pass 13 added F-P13-A —
the delta-plane post-recovery drop) · FIXED: 2 (both rig) ·
RETRACTED/REFUTED/ABSORBED: 7.

## Current fix-order (consolidates K.D + the carried rows; supersedes every report fix-order)

1. **F-P8-A** → 2. **F-P0-B** (unblocks F-P6-A's card half) → 3. **F-P9-A** (pass 9:
   the AUTHENTICATING wedge strands every multi-endpoint reconnect; unblocks the
   endpoint-switch legs) → 4. **F-P13-A** (pass 13: the delta plane drops on every
   post-recovery connection; recovery must re-subscribe — small, self-contained,
   transport+gateway) → 5. **F-P8-C's drag pin** (pass 11: F-P8-E is REFUTED — the
   follow-release + jump FAB work; only a drag regression pin remains optional) →
   6. **F-P9-B** (pass 9: the un-rendering typed face; unblocks the remote-down
   recovery leg) → 7. **F-P6-A store half** (ghost sweep; fixtures
   `/tmp/pass8/db/p8-2.db` + `/tmp/p9/db/p9-4.db`) → 8. **F-P8-D** (queue collapse;
   re-test F-P6-B after) → 9. **F-P3-A** (card-agnostic pinned budget) → 10. **F-P10-A**
   (pass 10: the silent commit no-op; small, self-contained) → 11. **F-P11-B** (pass
   11: the window picker's stale projection; small, prefs-flow re-collect + pin) →
   12. **F-P11-A** (the attachment chip's error face) → 13. **F-P3-B**
   (wiring-only hydrate) → 14. **F-P3-C** → 15. **F-P7-A / F-P7-B** → 16. **F-P5-A →
   F-P2-B → F-P0-C → F-P8-B → F-P8-F** → 17. **F-P1-B → F-P1-D** → 18. smalls
   (N-BM-1 → N-BP-1 → N-BM-3 → N-BN-1 → N-BK-1 app half → N-BJ-1 → N-BK-2 → N-BL-1 →
   N-BM-2 → N-BO-1 → N-BP-2 → K-small-1/2) → 19. parity queue per the G table + the opportunistic list.
   (Part P supersedes N; earlier orders superseded.)

## G-rows — desktop-vs-phone gaps (parity queue)

| ID | P | Status | Gap (one line) | Consume spec (exact) | Evidence |
| --- | --- | --- | --- | --- | --- |
| G81 | 3 | OPEN | gen-ui consume plane — desktop renders generated interactive UI (HTML in plugin-sandbox), phone zero files | 4-part spec: declare `genUiContext:true` hello bit; answer `runtimeCapabilities`; accept/forward the genUiContext array (max 100 × {path,state}) on send; render components (expand/collapse/tweak/submit/copyImage/retry + `genUi.desktopOnly` face). Minimum = honest "open on your desktop" face | Part F.B1 (complete) |
| G94 | 3 | OPEN (NEW pass 8) | worktree creation/fork plane (ManagedWorktree, 14 methods) — start-a-task-in-a-worktree has no phone counterpart, not even read-only. Pass 9: the plane is additionally AGENT-gated (worktreeCwd runtimeCapability → ManagedWorktreeService.supports, localhost only) | minimum face: `listCreations` + `onDidChangeCreations`/`onDidSettleCreation` | Part K.B1, L.B1 |
| G91 | 3 | OPEN | Plugins decode-only — `listPlugins` STRICT schema decoded, NO production screen consumes it (18 desktop bundled plugins invisible) | read-only Plugins viewer in the settings-viewer family (Usage/Memory pattern): list + enable-state only | Part I.2.4 |
| G93 | 3 | OPEN | V2 mobile-push plane has NO phone receiver (device registration `{device_mid}→{device_sid}`, Bearer JWT event POST, dispatcher w/ typed skip reasons) — when the relay socket is down, the desktop cannot reach the phone AT ALL | ARCHITECTURAL: push receiver + registration face; needs a cloud-transport decision first. **Enum residual CLOSED pass 9**: kind ∈ {question, approval, plan, task_completed, task_failed}, state ∈ {open, resolved}, producer = host chunk-7SAPNXNZ.js collectMobilePushEvents (NOT zcode.cjs — pass-8 attribution corrected) | Part J.2.1, K.B2, L.B2 |
| G89 | 3 | OPEN | hook review-reason enum (11 typed reasons) undecoded — phone card says only "N pending · M hooks"; saveHooks authoring remote-servable | decode half = safe minimum (reason enum + typed faces); saveHooks = consume/no-action decision | Part H.B3 |
| G82 | 3 | OPEN | memory viewer — desktop Memory UI (`memoryEnabled` toggle + project-memory viewer); phone zero surfaces | read-only mirror = `listProjectMemories` + `readProjectMemoryFile` (of 6 exact methods) | Part G.B3, H.B1 |
| G85 | 3 | OPEN | broadcastService vocabulary complete but consume/no-action UNDECIDED (`mobile-push:run/event` = V2 realtime arm; `state:*` sync family; task-groups claim protocol) | decision row; mobile-push:* feeds the G93 architecture | Part F.B3 |
| G60 | 4 | STANDS | plan-claim/rewards/purchase desktop-only (`codingPlan` 2,463 renderer hits; `claimManualPlan`; phone zero claim surfaces) | unchanged since pass-31; re-measured 3.18.1 | Part B.B2, G.B7 |
| G83 | 4 | OPEN | clientScenes — ONE verb (`getSnapshot`); smallest consume on the board | one-verb read mirror | Part H.B1 |
| G84 | 4 | OPEN | outputStyle mirror — `listStyles` + `getActiveStyle` (of 7) | read-only mirror | Part H.B1 |
| G95 | 4 | OPEN (NEW pass 8) | feedback/support-intake plane (Feedback, 15 methods incl. device-snapshot + log-archive attach) — phone diagnostics export could feed `attachLogsFromExport` | inventory row → consume decision | Part K.B1 |
| G96 | 4 | OPEN (NEW pass 9) | conversation selection-comment/annotate plane (批注) — desktop gates the selection→comment entry on the conversationQuoteComment agent capability; phone conversations have no annotation entry | inventory row (renderer feature, self-hiding); consume = the phone quote plane has no comment concept | Part L.B1 |
| G98 | 3 | OPEN (NEW pass 10) | the desktop's SOURCE-SERVER/compute planes — it connects DIRECTLY to SSH servers (180 i18n keys: full host/port/auth password·privateKey/asset-install-mode form), WSL (45), Docker (35), sourceServer (60) as workspace hosts; the phone's "remote" is only the desktop's relay — zero counterpart, not even read-only | read-only mirror of source servers (the phone already decodes sourceServerId in task rows — pass-8's bridge resolution); authoring stays desktop-only | Part M.B3 |
| G99 | 4 | OPEN (NEW pass 10) | the desktop's WHITEBOARD plane (65 keys: named persisted boards, pen/eraser/color/strokeWidth/undo/redo/addToChat, typed missing-board face) vs the phone's send-only Sketch canvas | inventory row; consume minimum = honest "open on desktop" face for whiteboard artifacts if they surface in conversations | Part M.B3 |
| G100 | 4 | OPEN (NEW pass 10) | the desktop's model-TRAJECTORY viewer (165 keys, 模型调用轨迹: per-model-call usage input/output) — the phone's Usage screens are aggregate | P4-smallest: decode the trajectory payload for the Usage viewer if the channel serves it; else inventory-only | Part M.B3 |
| G101 | 4 | OPEN (NEW pass 10) | the CUA permission-status plane: `CuaPermissionService` getStatus (typed status incl. grantOwner/accessibilityProbeOk/screenRecording/screenCaptureProbeOk/idle + typed reasons) + restartHelper (macOS-only), 25-key desktop panel — the phone decodes PUSHED observations only and can never show the desktop's CUA health proactively | read-only status mirror minimum (one card fed by getStatus); restartHelper = consume/no-action decision | Part M.B1, M.B3 |
| G97 | 3 | OPEN (NEW pass 9) | the desktop's web-remote-control companion plane (90 i18n keys: QR→browser connectUrl, session lifecycle, 12-way typed failure taxonomy, workspace/task switching, bot channels feishu/lark/weixin/telegram, singlePageNote one-page guard, pullMobileLogs, settings.mobilePush desktop toggle) — the phone is BLIND to it: no face for a browser session controlling its workspace, no kicked/conflict notice, bot-channel access undocumented phone-side | honest surfaces minimum (a companion-state mirror + a kicked/conflict face); the plane itself stays desktop-served. **Pass-10 census additions:** the companion's client mode (`web-remote-replayable`) is restricted to a 49-channel allowedChannels Set (exact list in M.B2) with McpSync scoped to 4 verbs; the desktop's `remote.*` namespace (460 keys) is the desktop-side device-away faces | Part L.B3, M.B2 |
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
- **turnHeader row-state plane (pass 13)** — the phone decodes the turnHeader row kind
  but consumes only its boundary (the "── Turn" separator, V4Conversation.kt:288); the
  row's STATE vocabulary ("running" etc.) is decode-only — the task machine keys on the
  `control` block instead (SessionState.kt:199-222). Receipted live on MOCK_TURN_RUNNING:
  no running face, no Stop-from-header — correct at HEAD (control is the state channel),
  recorded so nobody re-hunts it as a defect.
- **gitCheckpoint createCheckpoint** — unconsumed verb behind the "create one before
  risky changes" empty state (K.A2 small: wire a button or reword).
- **NO-ACTION forever list** (never "port" these): sync ×5, credential/oauth/onboarding/
  fileWatcher/userConfig, AppControl (CUA driver), CuaPipSession lifecycle, Bots
  authoring depth (read-only by ADR), git WRITE half (stage/commit/push — read-only
  posture), pluginManagement 17 methods, voice-input service.

**Open G count: 21 + 3 standing** (G60, WATCH-4, settings depth; G93's enum residual closed).

## N-rows — smalls

| ID | P | Status | One line | Evidence |
| --- | --- | --- | --- | --- |
| N-BM-1 | 3 | OPEN | load-earlier strip total is a stale subscribe-time snapshot ("420 of 339") | Part J, K.A3 |
| N-BM-3 | 3 | OPEN | Context-sheet → Terminal route: Attach is a silent no-op (main terminal screen attach works — sheet variant dead) | Part J |
| N-BK-1 (app half) | 2 | OPEN | History silent-empty needs a "No commits yet" empty state (rig half FIXED `2b12fc46`) | Part G.A2.3 |
| N-BJ-1 | 4 | OPEN | attachment image previews have NO size/aspect cap (1×1 seed renders ~300×220dp) | Part F.A2, K.A3 (+ pass-11 stamp: s04 unchanged) |
| N-BK-2 | 4 | OPEN | automation Edit sheet schedule chips clip at phone width | Part G.A2 (+ pass-16 stamp 2: "Weekda"/"Cr" clipped live under the arms, S.A3) |
| N-BL-1 | 4 | OPEN | workflow-run Run/Manage icon buttons subtree-unlabeled (TalkBack silence) — ONLY unlabeled clickables found | Part H.A2 |
| N-BM-2 | 4 | OPEN | child-session view: generic title, skeleton-forever on unscripted children | Part J (+ pass-17 stamp: the automation run's Go-to-session on an unscripted session renders the generic "Conversation" title over the skeletons, T.A2) |
| K-small-1 | 4 | OPEN | checkpoint empty state says "create one" with NO create affordance (verb unconsumed) | Part K.A2 |
| K-small-2 | 4 | OPEN | "First sync failed" renders alongside CACHED rows (wording/state nit) — pass 9: the strip can be entirely ABSENT when the F-P9-A wedge suppresses the refresh | Part K.A2, L.A3 |
| N-BN-1 | 4 | OPEN (NEW pass 10) | Plugins screen: the per-plugin enable switches are UNLABELED (plugin_switch_plugin-prisma / -review carry no content-desc, no text children — TalkBack silence on the row's only interactive element) | Part M.A1, M.A2 |
| N-BO-1 | 4 | OPEN (NEW pass 11) | the changes viewer's error face interpolates the RAW fault code ("Could not load changes: fault.fileChanges.none" — `changes_error` %s, Phase9Components.kt:379) — the G66 fault→human mapping (2af339b0) doesn't cover this Phase9 surface | Part N.A2 (+ pass-13 stamp: SECOND surface receipted live — the quota reset failure face renders "Reset failed · fault.mock.unscripted:useCodingPlanReset", `quota_reset_error + " · " + err` at CodingPlanQuotaSection.kt:456; Part P.A1) |
| N-BP-1 | 3 | OPEN (NEW pass 13) | the Usage screen DOUBLE-applies the Scaffold `inner` padding (PullToRefreshBox line 141 + the inner Column line 146 — the only viewer with two `padding(inner)` applications; all 11 sibling viewers have exactly one) → a ~400px dead band under the top bar that is ALSO A DEAD TOUCH ZONE: scroll and pull-to-refresh gestures starting inside it are swallowed (8 receipted attempts, zero re-queries); content scrolls only from drags begun below the band | drop the second `.padding(inner)`; pin: a compose test dragging from the band region asserts the list scrolls | Part P.A1 |
| N-BP-2 | 4 | OPEN (NEW pass 13) | Daily-trend x-labels render "2026-" for every row — `day.date.take(5)` (UsageScreen.kt:613) slices the ISO date the decoder feeds; the slice assumes an MM-DD shape | slice after the year (or format the full date); pin on the label text | Part P.A1 |
| K-small-3 | 4 | WATCH | one-off terminal route pop (unreproduced — watch, don't chase) | Part K.A2 |
| N-BJ-2 | 4 | REFUTED→FOLDED | unpair IS reachable (Settings bottom, pass 4); fakeLink half folded into F-P2-B | Part G.A2.4 |
| N-BM-4 | — | RIG | Dictate leg kills the emulator (cause unproven); leg undriven | Part J |

**Open smalls: 13 (+1 watch +1 rig)** — pass 13 added N-BP-1 (P3) and N-BP-2 (P4).

## Mock-fidelity debts (rig, not app)

1. Accepts ANY interactionId — add the unknown-id typed error arm (unblocks honest-failure faces).
2. ~~`fault.file.notFound` on seeded `readTextFile` (listing-only seeds)~~ **CLOSED pass 16** (`9b6e2292`): seedWorkspaceFiles seeds default content for EVERY path (explicit fileText keeps precedence); the main.rs open receipted live (S.A1).
3. ~~Snapshot doesn't list the once-per-connection PUSHED interaction → rows sweep legitimately STALEs it.~~ **CLOSED pass 18 fixed-by-prior-work** (`2b12fc46`, pass 5): `snapshotPendingInteractions` serves the FULL pending list once the fresh-push delivered (`interactionPushDelivered`) — the only exclusion window is BEFORE its own patch lands (the RIG-P1 anti-race, by design). Desktop evidence: the host pane resolves the active permission card FROM `snapshot.pendingInteractions` and the notification producer filters that snapshot list — a delivered interaction IS snapshot-listed on the desktop, so the mock's post-delivery inclusion is the faithful shape. The F-P0-B stale-sweep receipts stand on their pinned row-plane mechanism; this confound is gone.
4. ~~Static session-config echo re-asserts the effort chip~~ **effort half CLOSED pass 16 (S.A2 — does not reproduce at HEAD; the G3 merges + thoughtLevelConfig mutation fixed it in earlier mock work; chip "max"→"high" stable 8s+)** ~~the ROUTING half stands: the composer delivery picker (Guide tap) fires no wire verb~~ **ROUTING half CLOSED pass 17 (T.A1 — BY-DESIGN): the config-chip delivery picker is the G49 ONE-SHOT `deliveryOverride`; no pick-time verb is the contract, the icon TINT is the armed face, and the wire carries `requestedDelivery` on the next send — receipted live on the full trio** (`MOCK: sendText trio heldQueueDisposition=keepQueueAndSend requestedDelivery=guide expectedHeldQueueItemIds=[q-rig-1, q-rig-2]`, `sendConversationCommandV4 ok in 1805ms`).
5. ~~Raw optionId labels (`allowOnce`) instead of human labels (desktop sends `label: c.name`)~~ **CLOSED pass 16** (`9b6e2292`): options now carry `label: "Allow once"/"Allow always"/"Deny"` (unknown ids verbatim), self-tested; the on-device RENDER is F-P3-A-gated (both compositions starved — S.A2).
6. ~~Push arm is CONVERSATION-SUBSCRIBE-anchored (recipe: reconnect with conversation open, lock inside the arming window).~~ **CLOSED pass 18 by-adjudication**: the anchoring IS the desktop shape (the interaction plane rides the conversation subscription — pass-6/8 receipts); `155a3c02` armed it that way on purpose so the FRESH notification path is genuinely exercisable. The recipe stands as the drive procedure, not as an open debt.
7. ~~`MOCK_REMOTE_DOWN_MS` / `MOCK_MD_FILE` arms exist but were never driven (gen2 append-after-exec bug).~~ **CLOSED pass 18** — the parenthetical was already the closure: both arms driven pass 9 (Part L), healthy since.
8. ~~**The automations plane needs `MOCK_OFFPEAK=1` ALONGSIDE `MOCK_AUTOMATIONS=1`** — without it the app's `off-peak-task.list` faults (`fault.mock.unscripted:offPeakTaskList`) and home renders the "This feature isn't available" face (pass-10 receipt; the launcher /tmp/p10/relay-g1.sh carries the fix).~~ **CLOSED pass 18 by construction** (`6846727d`): `seedOffPeakRigList` extracted and served by MOCK_AUTOMATIONS=1 when MOCK_OFFPEAK is unset (the desktop's automations ARE scheduled/off-peak tasks — a session serving listAutomations always serves the list); MOCK_OFFPEAK=0 opts out. Self-tested (the 5/5 pass-18 file); the plane itself stays receipted by passes 10/16/17 (code path unchanged — no live re-drive needed).
9. ~~The scripted attachment's registration raced the app's cached-row read~~ **FIXED pass 11** (`1e649eab`: eager registration in setScriptedAttachment; cold-start read ok 306ms, zero unknownRef on gen3). Related note: `MOCK_MD_FILE` takes a FILE PATH, not a flag — `MOCK_MD_FILE=1` crashes the relay at boot (FileNotFoundException: 1).
10. ~~The scripted reset~~ **CLOSED pass 14** (`8361b769`): `MOCK_RESET_SUCCESS=1` scripts `{used:true}` + stamps the use; every later status read serves the CONFIRMED progression (consumed opportunity + usedAt + unread cursor) — the success face receipted live (Q.A2). **The resetStatusQueue VARIANT is also closed (pass 15, `66844fbe`): queue entries serve EXPLICITLY (only the unread fold applies — the pass-14 fold no longer confirms them in place) and `MOCK_RESET_QUEUE_PRE=1` arms the ladder leg** — the reconciliation retry ladder receipted live (R.A3).
11. ~~The off-peak relay-start anchor~~ **CLOSED pass 14** (`8361b769`): `MOCK_OFFPEAK_COMPLETE_ANCHOR=first-list` arms the flip at the notifier's baseline seed; live-verified (the flip fired mid-life after the baseline in gen3/gen3b, Q.A3).
12. ~~The frame-class filter~~ **CLOSED pass 14** (`8361b769`): `MOCK_DISCONNECT_AFTER_DATA_FRAMES=<n>` counts DATA pushes only; live-verified at =2 (survived handshake + 70s of polls, died at the 2nd data push, Q.A4).
13. ~~The deltas relay-start anchor~~ **CLOSED pass 14** (`8361b769`): `MOCK_RUN_DELTA_ANCHOR=subscribe` arms the schedule at the first conversation subscribe; live-verified (the deltas landed at subscribe+6.1s/+22.1s on a relay 5 min old, Q.A1).
14. **(pass 13, RIG)** the API-36 emulator NAT: after a server-side abrupt socket close (the disconnect arm), the emulator's IPv4-mapped-IPv6 path blackholes NEW app flows (SYN-SENT hangs) while pure-IPv4 device flows (`nc -z 10.0.2.2`) still succeed — recovery = full emulator restart. Not an app defect (the same stack connected 10+ times before). **Pass-14 addendum: it is PROBABILISTIC — pass 14's pass-start pkill armed it once (one dead AUTHENTICATING, Q.A0.1) and six later abrupt-close swaps recovered normally.** **Pass 18: reclassified STANDING RIG-ENV knowledge** — not an open work item (unfixable in mock code; the recovery is the recipe). Did not manifest this pass: two idle-socket drops recovered honestly WITHOUT a restart (Part U).
15. ~~**(pass 14)** `markCodingPlanResetHistoryRead` acks but never clears `hasUnreadHistory`~~ **CLOSED pass 15** (`66844fbe`): the mark now CLEARS the unread cursor on every later status read (the desktop's server behavior); the pass-14-declared arrival counter went live. Live-verified (relay "reset history read" line, R.A2).
16. ~~**(pass 14)** the scripted reset doesn't mutate the entitlement meters~~ **CLOSED pass 15** (`66844fbe`): a stamped use mutates the used window's limit entry (delta 10, clamped; remaining/percentage recomputed) on later getEntitlementSnapshot reads. Live-verified: Weekly 49%→59% beside the completion note (R.A2, s05).
17. ~~**(pass 16)** the mock's resync SNAPSHOT omits delta-pushed effect rows — `pushRowDelta` rows live only in the resync opLog, never in the snapshot builder's row source — so after a force-resync (the watchdog's escalate) pushed rows VANISH from the timeline~~ **CLOSED pass 17** (`05ba5c18`: the stall arm's live frames + silent backlog rows join the authoritative `appendedRows` tail, so `snapshotRows(...) + appendedRows.values.toList()` carries them; self-tested 2 ways) — **live receipt T.A3**: under the exact pass-16 force shape (`attempt=2 force=true` → `snapshotReconcile rows=166`) the timeline RETAINS the stall rows (frames 147-150 render; pass 16 saw them vanish).
18. ~~**(pass 17)** the mock never served `inputRouting.mode "choice"` (only startNow/enqueue) — the phone's held-queue CHOICE disposition dialog (G50) was structurally undrivable~~ **CLOSED in the same pass** (`05ba5c18`: the `MOCK_INPUT_ROUTING=<mode>` arm pins the desktop's 5-mode enum `startNow|enqueue|guide|reject|choice` with a STICKY flag that suppresses the send/stop flips — fail-fast on a bad value per H.A0.2; + the sendText trio log line + the clearQueueAndSend server-side honoring). Live receipt: the dialog rendered first-ever and the full trio rode one sendText (T.A1).
19. ~~**(pass 18)** the mock ignored `requestedDelivery:"queue"` on sendText — the desktop answers `inputAccepted{delivery:"queue"}` and HOLDS the input as a queue item (the bot path marks the task waiting/Queued), but the mock appended user/assistant rows immediately, un-queuing the send and hiding the G49 one-shot "Queue this send" override's whole point~~ **CLOSED in the same pass** (`6846727d`: the sendText arm enqueues `q-rig-N` + `pushQueuePatch` + the routing echo and appends NO rows; composes with the clear disposition — empty→exactly the new item — and keep — grows; self-tests 5/5, suite 172/0/0). **Live receipt U.A1**: trio `keepQueueAndSend + queue + [q-rig-1,q-rig-2]` → q-rig-101 appended → strip 2→3, then `startNow + clearQueueAndSend + [q-rig-1,q-rig-2,q-rig-101]` → strip GONE + immediate rows.

## DISCOVERY BACKLOG (tick when the findings are appended as rows)

- [x] **1. Desktop Mobile-Remote UI/UX audit** — DONE pass 9 (Part L.B3): the plane is
  webRemoteControl.* (90 keys) — QR→browser connectUrl companion, session lifecycle,
  12-way typed failure taxonomy, workspace/task switching, bot channels, singlePageNote,
  pullMobileLogs, settings.mobilePush desktop toggle → **G97 (P3)** phone-side blindness.
- [x] **2. Channel method tables — DONE pass 10 (Part M.B1):** the last 8 tables
  extracted exact (ProviderSettings 15, ModelSelection 3, SourceServerWorkspace 6,
  ZCodeTask controller family = Xz's 7 routed + 4 bot passthroughs + the WindowController
  channel's 14 members, ConversationShare 13 + typed stubs, CuaPermission 2, MediaPreview
  1 ⚠ CLOSED, WindowProjectChanges 2 ⚠ CLOSED — both ⚠ attributions resolved as
  host-served). ~41 tables now exact across Parts G–K+M + the 49-channel exposure policy
  (M.B2). No ⚠ remain.
- [x] **3. Renderer route/screen inventory — CLOSED as a dead angle** (no URL-route
  vocabulary exists: 2,862 lazy chunks, scene/state-driven nav — Part K.B3; do not
  re-hunt). **The i18n namespace diff DONE pass 10 (Part M.B3):** 84 desktop namespaces /
  6,515 keys adjudicated → G98/G99/G100/G101 + the settings-domain census stands from
  pass 7 (51 domains). The namespace diff is EXHAUSTED — every no-counterpart namespace
  is now either rowed, folded into a row, or classified desktop-legit.
- [x] **4. The unassessed clientHello/runtimeCapabilities bits** — DONE pass 9 (Part L.B1):
  all 10 traced + the full 13-name schema; NEW rows G96 (selection-comment 批注) and
  G94/G91 adjacency notes; phone answers NOTHING (zero runtimeCapabilities handling).
  sessionLifecycleWorkspace + extensionInputSource are desktop-reserved (zero consumers).
- [x] **5. Named residuals — DONE (G93 enum half pass 9, ⚠ attributions pass 10)** (Part
  L.B2, M.B1): kind ∈ {question, approval, plan, task_completed, task_failed}, state ∈
  {open, resolved}; producer = host chunk-7SAPNXNZ.js collectMobilePushEvents. **The
  MediaPreview/WindowProjectChanges ⚠ attributions CLOSED pass 10** — both are
  host/index.js-served (createMediaPreviewService's prepare; createWindowProjectChangesService's
  getCapabilities+onDidChange).
- [x] **6. Coverage leftovers (rolling)** — pass 9 closed: the lockscreen leg CORE
  (push-while-locked over LIVE receipted 2×; the shade-ACTION tap = STANDING RIG STOP),
  MOCK_REMOTE_DOWN_MS ✓, MOCK_MD_FILE ✓, mention-panel tabs ✓. **Pass 10 closed:** the
  diagnostics plane, the 17-screen a11y audit (N-BN-1), the automations plane's
  unavailable-face, the font-scale matrix (1.15× max), the zai-dark theme matrix, the
  fresh-install pairing chain, F-P8-C's drag re-verification. **Pass 11 closed:** the
  user-row long-press ACTIONS plane (all four actions), F-P8-E's follow-release leg
  (REFUTED — the release + numbered jump FAB work), the MD_FILE render half (light
  rig), the changes viewer (typed error face + the MOCK_FILECHANGES happy path), the
  Licenses share, the What's-new empty state, the Live-connection window picker
  vocabulary. **Pass 12 closed the LAST driveable piece:** the Notification detail
  LEVEL toggle (driven WORKING both directions, disk-arbitered via the STRING values
  in preferences.pb; the pass-11 "Minimal/Standard dialog" naming corrected — it is a
  direct two-value toggle row, no dialog). REMAINING (all non-driveable): question
  card options post-F-P3-A-fix (F-P3-A open), Take-photo happy path (impossible —
  stop), widget add-to-home (standing stop), re-drive FIXED rows once fix rounds
  start, the shade-ACTION approve (standing stop), TalkBack runtime (real device).
  **ITEM CLOSED — backlog 8/8; campaign SATURATED at pass 12 (Part O).**
  **Pass 15 (Part R) drove the Part-Q coverage-honesty DRIVEABLE leftovers:**
  the Usage range chips (CLOSED no-defect — the chips drive, the wire carries
  the `"all"|"7d"|"30d"` enum per ViewerTools.kt:31-37, the rig fixture is
  range-invariant), the reset meter/unread residuals (#15/#16 CLOSED live), the
  reconciliation retry ladder (first receipt — 3 reads, +250 ms from the
  previous read's completion), the disconnect filter at limit=1 (one frame
  earlier than =2; honest recovery), and the F-P13-A stay-open boundary. What
  remains on that list is standing stops + F-P3-A-gated planes only.
- [x] **7. Phone self-audit expansion — DONE pass 10 (Part M.A1/M.A2/M.D):** the a11y
  sweep now covers 17 screens beyond the pass-3/5 conversation+settings+home set (one
  new small: N-BN-1); the diagnostics-export content receipted (1,174 lines,
  redaction-verified); settings-search edges N/A (this build's Settings has no search
  field); font matrix driven at the phone's real max (1.15×) + reverted; zai-dark
  driven + reverted. REMAINING within this item: TalkBack runtime behavior (the census
  is static AX; no TalkBack session was driven — a real-device leg if ever wanted) and
  the locale matrix (zh round-trip was receipted pass 5).
- [x] **8. Shipped-content catalogs** — plugin inventory 18 (matches pass-35), slash-20
  parity EXACT (pass 6), settings-domain census done (51 domains, pass 7). DONE unless a
  desktop delta changes the catalogs.

## Prompt templates (paste-ready; the ledger + report tail make them self-adjusting)

**Discovery pass (the default — canonical, audit-verified 2026-11-14):**
```
Continue the pass0 verification campaign: live-verify the ZCode Companion Android app
against its macOS desktop, find everything missing or broken, and record it.

READ FIRST (in this order):
1. docs/evidence/pass0-LEDGER.md — the whole file (rows, fix-order, DELTA procedure,
   discovery backlog, campaign state incl. the audited rig note).
2. The LAST "# Part" section of
   docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md (latest pass
   record + its fix-order + coverage-honesty).
3. The rig recipes: report Part C + sections D.A0, E.A0, F.A0, G.A0, H.A0, K.A0 + the
   Part I/J concurrency forensics. Non-negotiables they encode: `pkill -f
   MockRelayMainKt` NEVER by port (qemu NAT); taps at SCREENSHOT-derived bounds for
   dialogs (dump bounds lie ~200 px); restart `adb logcat -T 1` after EVERY emulator
   restart; absent log lines are inconclusive — the Room DB pull is the arbiter.

STEP 0 — DESKTOP OF RECORD (your first tool action):
  defaults read /Applications/ZCode.app/Contents/Info.plist CFBundleShortVersionString
  shasum -a 256 /Applications/ZCode.app/Contents/Resources/app.asar
Compare against the ledger. UNCHANGED → proceed. CHANGED → run the ledger's DELTA
procedure BEFORE any app leg (re-extract, re-census every standing grep, diff all
numbers, open new G-rows), and stamp the new digest into the ledger.

STEP RIG — LIVE EMULATION (mandatory for every live leg; REA/docs-only work never
satisfies one):
- Reuse or boot: `adb devices`; kill zombie relays with `pkill -f MockRelayMainKt`;
  cold-boot = AVD zcode-a16, NO `-no-window`, flags `-no-snapshot-load -gpu
  swiftshader_indirect -no-audio -no-boot-anim`, then `svc wifi disable`.
- FIRST in-app action after boot: Settings → Privacy → "Screenshot protection" OFF
  (FLAG_SECURE blacks every capture otherwise); verify a ~1.4 MB-class PNG.
- Build + install at HEAD: `./gradlew :app:assembleDebug :tools:mockzcode:jar` then
  `adb install -r app/build/outputs/apk/debug/app-debug.apk`; restart
  `adb logcat -T 1`; receipts go in a fresh /tmp/p<N>/ per pass.
- Relay: per generation set a FRESH `MOCK_GEN_NONCE` (format mock-p<N>-gen<M>-<id>;
  the var is MOCK_GEN_NONCE — report Part C's "MOCK_GEN_ID" is a wrong name, do not
  copy it), launch via the plain `java -cp` recipe (/tmp/p0/relay.sh pattern) with
  ONLY the arms the backlog item names — env-inherited, set BEFORE the exec line
  (appending after it arms nothing); MOCK_PORT_OVERRIDE/MOCK_PUSH_MS_OVERRIDE honored.
  Stored endpoints survive relay restarts (constant mock passHash) — no re-pairing.
- Drive ON-DEVICE: uiautomator dumps (count nodes with `grep -o '<node' | wc -l` — the
  XML is one line), screenshots, logcat, and DB pulls
  (`run-as com.zcode.android.app cat databases/zcode.db{,-wal}`) as the arbiter.

STEP 1 — DISCOVERY BACKLOG: work the ledger's next unchecked item(s) with the method
named there. An item is done when its findings are appended as new F/G/N rows
(mechanism + code pin + receipt) and the checkbox is ticked.

STEP 2 — COVERAGE LEFTOVERS: drive the latest pass's "Coverage honesty" list (latest
first). Skip anything already receipted unless its code changed.

RULES:
- Never re-narrate an OPEN row — ONE line + a Stamps bump in the ledger. Never
  re-receipt FIXED/REFUTED/ABSORBED/STANDS surfaces unless their code changed.
- App code: RECORD, don't fix. Mock/rig fixes ARE allowed (they unblock arms) —
  commit them as `fix(mock): <ID> …`.
- If another session may share the rig, claim generations in /tmp/pass-coord-*.md and
  nonce-anchor every receipt.
- Append the new pass part to the report (append-only; earlier parts untouched),
  ending with the verification-stamp block: time, generations, artifact paths, APK +
  desktop digests, and the what-was-NOT-driven list. Update the ledger (statuses,
  stamps, backlog, campaign state incl. the rig-left note).
- Commit everything locally as ErnestHysa <takis312@hotmail.com> (`docs(pass0):
  pass-<N> record …` for docs, `fix(mock): …` for rig fixes). NEVER push.
```

**Fix round (after roadmap phases exist):**
```
Read docs/evidence/pass0-LEDGER.md, then open
docs/evidence/pass0-full-verification-and-desktop-gap-2026-11-12.md at the roadmap,
and implement PHASE <N> ONLY — nothing else. Each row: re-read the finding + its
ledger entry, TEST-FIRST (failing pin per fix, live-shape harnesses where the report
names them), fix, run the module suites (testDebugUnitTest + pure-JVM `test` tasks),
rebuild the debug APK, commit `fix(<ID>): …` per finding, tick the row FIXED in the
ledger with the commit sha. Follow the deliverable-completion rule (regression pins,
module suites — `testDebugUnitTest` for Android modules + `test` for the pure-JVM
modules, APK rebuilt so a fresh artifact exists, commit, state the artifact path +
version marker). Re-verify each FIXED row live on the rig under the discovery
template's STEP RIG rules. Commit the report/ledger update locally too
(`docs(pass0): fix-round <N> record …`); nothing pushed.
```

**Desktop delta pass (run whenever the desktop updates):**
```
/Applications/ZCode.app was updated. Re-extract the asar and run the DELTA procedure
in docs/evidence/pass0-LEDGER.md: re-run every standing census grep, diff each number
against the ledger, open NEW G-rows (especially mobile-remote/UI/UX planes), re-check
the backlog items the delta invalidates, then diff the phone at HEAD against each new
row. Stamp the new digest into the ledger's desktop-of-record, append a DELTA part to
the report, update the ledger, and commit everything locally (`docs(pass0): desktop
delta …`). Nothing pushed.
```

## History

- 2026-11-15 (pass 18) — Part U executed on the STEP-2 coverage-honesty axis
  (pass-17 leftovers) + the mock-debt axis: ZERO new rows; the G49/G50 one-shot
  matrix COMPLETED (queue + startNow values wire-receipted live; the queue value
  riding the debt-#19 server-side HOLD — strip 2→3 then the clear composition);
  the run-row Delete confirm-face adjudicated NO-DEFECT statically both sides;
  mock debts #3 (fixed-by-prior-work `2b12fc46`), #6 (by-adjudication), #7
  (documentation), #8 (by construction `6846727d`) CLOSED, #19 opened+closed in
  the same commit (self-tests 5/5, suite 172/0/0), #14 reclassified STANDING
  RIG-ENV — the debt list is fully adjudicated (0 open work-debts). F-P8-D
  stamps 4. Two idle-socket drops recovered honestly (relay-4/relay-8); zero
  gateway.drop. Rig left UP (gen1 spent arms, app CONNECTED on relay-8).
- 2026-11-15 (pass 17) — Part T executed on the LAST two gated compositions
  (MOCK_QUEUE + MOCK_AUTOMATION_RUNS): ZERO new rows; the complete G49/G50 send
  trio receipted on ONE wire call under the new MOCK_INPUT_ROUTING=choice arm;
  all five queue verbs wire-true (edit 223ms / reorder 551ms / send-now 499ms /
  delete 285ms — auto-drain was pass 12's); the automations-runs plane receipted
  end-to-end (History sheet + all four filter chips + runAutomationNow 677ms +
  the typed duplicate snackbar + deleteAutomationRun) and Go-to-session's
  working half (the workspace-session run loads via the subscribe snapshot);
  F-P3-B stamps 2 (narrowed to the unscripted-session shape) + N-BM-2 stamps 2.
  Mock debts: #4 CLOSED by-adjudication (the delivery picker is the G49 one-shot
  — no pick-time verb BY DESIGN), #17 CLOSED (fix `05ba5c18` + the live force-
  resync receipt T.A3: `attempt=2 force=true` → `snapshotReconcile rows=166`,
  the stall rows retained), #18 opened+closed in the same commit (the
  MOCK_INPUT_ROUTING arm + the sendText trio log + clearQueueAndSend honoring;
  self-tests 5/5, suite 167/0/0) — 5 OPEN (#3, #6, #7, #8, #14). Zero
  gateway.drop. Rig left UP (gen3 TURN_STALL journaling, app on synth-task-1).
- 2026-11-15 (pass 16) — Part S executed on the open MOCK-DEBT axis + the P.A2
  watch-note: ZERO new rows; mock debts #2/#5 CLOSED (`9b6e2292`, self-tests 7/7
  + suite 162/0) with the Files content read's FIRST working receipt (main.rs,
  14 seeded lines) and the labels' wire shape pinned (UI render F-P3-A-gated);
  debt #4's effort half CLOSED-by-prior-work (chip "max"→"high" stable; the
  routing half stays MOCK_QUEUE-gated); debt #17 opened (the resync snapshot
  omits pushed effect rows). The automations editor receipted WORKING end-to-end
  (updateAutomation 02:00→03:30 + createAutomation "RigProbe", first in-series);
  N-BK-2 stamps 2. The REAL frames>0 stall driven (MOCK_TURN_STALL arm): the
  attach-recovery replayed seq 3..6, the watchdog re-kicked at 90.1s, the heal
  SILENT in both windows — P.A2's watch-note CLOSED (no surface fires when data
  is fully delivered). Zero gateway.drop for the pass. Rig left UP (gen3
  TURN_STALL looping, app on the conversation, logcat → /tmp/p16/logcat.log).
- 2026-11-15 (pass 15) — Part R executed on the STEP-2 coverage-honesty axis
  (both named axes saturated): ZERO new rows; FOUR first-series WORKING receipts
  (the Usage range chips' first drive — no defect, the range enum is
  wire-carried; the reset re-drive under the FIXED mock — Weekly 49%→59% meter +
  the unread clear, debts #15/#16 CLOSED live; the reconciliation RETRY LADDER —
  3 reads, the delay measured from the previous read's completion; the
  STAY-OPEN recovery resilience — deltas fully live across two kills, zero
  gateway.drop in the whole pass). **F-P13-A NARROWED** (stamps 3): the
  delta-plane drop is close/detach-CYCLE-bound (detach() fires only from
  closeConversation — code-pinned), not universal; its fix shape stands for that
  path. F-P9-A stamps 3 (third trigger variant: relay absent at cold-start
  connect → 10s TIMEOUT → silent wedge). Mock debts #15/#16 CLOSED + the
  reset-statusQueue variant armed and driven (`66844fbe`, self-tests 9/9 +
  155/0). The hook-card plane adjudicated F-P3-A-gated (standing). Rig left UP
  (gen4 spent-delta arms, app on the conversation, logcat → /tmp/p15/logcat.log).
- 2026-11-14 (pass 14) — Part Q executed on the arm-matrix axis's last legs: ZERO
  new rows; FOUR first-series WORKING receipts (the delta happy path on a fresh
  process — subscribe-anchored, 2 applies/0 drops; the G90 reset SUCCESS chain —
  typed confirm → {used:true} → first-poll COMPLETED → markHistoryRead → the
  "Quota reset" note; the background off-peak notification — FGS Live-connection
  relay-swap → 1.5s background reconnect → notify.show → "Task completed" in the
  shade; the mid-life disconnect — the data-frame filter died exactly at the 2nd
  data push, honest ~1.2s recovery + typed send-refusal face). The foreground-
  consumed off-peak completion was ADJUDICATED as §9.9 by-design (the home card is
  the in-app surface; both halves receipted live; Part Q.A3 — do not re-hunt).
  Mock debts #10–#13 CLOSED (`8361b769`, self-tests 11/11 + 146/0); #15/#16
  opened; #14 amended (the blackhole is probabilistic). F-P13-A stamps 2. The
  arm matrix is EXHAUSTED — the campaign is saturated on both axes; roadmap
  awaits Ernest. Rig left UP (gen4 spent-arm, app on the conversation, Live
  connection ON, logcat → /tmp/p14/logcat.log).
- 2026-11-14 (pass-13 supersession note) — the `c2bd7bcb` rig-left correction (a
  concurrent session documenting the post-pass-12 soft-reboot and "repair") was
  written into the campaign-state block that pass 13's record replaced; the same
  reboot event is pass 13's P.A0.4/P.A4 lifecycle (that session's "app relaunched
  (pid 2616), reconnected to gen2" was actually pass 13's post-cold-boot gen5a
  launch — the nonce-bearing relays tell them apart). The correction's facts live
  on here; nothing else from it was lost.
- 2026-11-14 (pass 13) — Part P executed on the never-driven-arm matrix axis:
  NEW F-P13-A (P2, the delta plane drops on every post-recovery connection —
  wire-arrival + gateway.drop receipted 2×, mechanism pinned to the recovery's
  missing re-subscribe), NEW N-BP-1 (P3, the Usage screen's double `padding(inner)`
  dead band is ALSO a dead touch zone), NEW N-BP-2 (P4, "2026-" trend labels);
  stamps N-BO-1 2 (the quota-reset raw-fault surface), F-P7-B 2 (the typed
  rejection trigger), F-P1-B 5 (partial sweep both directions). WORKING receipts:
  the Usage plane end-to-end (first data-bearing — quota meters, range chips,
  cards, heatmap, trend, pie, G20 plan charts, reset status), the G90 reset verb
  chain (typed-confirm dialog → useCodingPlanReset → typed failure face),
  MOCK_TOOLS tool cards + markdown strikethrough, the stream-watchdog ladder
  (90s → re-kick → escalate → resync → replay; silent self-heal watch-note), the
  answer-with-error chain (typed rejection → FAILED + FAILED_TERMINAL/rejected +
  card cleared). Mock debt #1 FIXED `91ec7ade` (6/6 self-tests); debts #10–#14
  opened. The off-peak in-life notification leg undriven (NAT flake + arm
  semantics; recipe recorded). Rig left UP (gen5b, logcat2).
- 2026-11-14 (pass 12) — Part O executed: ZERO new rows; the backlog's last driveable
  piece (notification LEVEL toggle) driven WORKING both directions + disk-arbitered;
  FIVE first-series WORKING receipts (the interaction-behavior chain Settings→chips→
  wire, the Auto-send/auto-drain toggle, the card-plane approval ANSWER — pass 0's
  silent no-ops are gone at HEAD, store lifecycle DISPLAYED→RESOLVED receipted — and
  the background-work plane strip→sheet→backgroundBashOutputV4 output panel); stamps
  F-P3-A 9 (shape-narrowing variance receipt: starvation is composition-dependent),
  F-P8-D 3, F-P6-B 3, F-P6-A census 4 (0 orphans at HEAD), F-P1-D 4. Rig: the
  canonical `svc wifi disable` step BREAKS the NAT on the API 36 AVD (O.A0.1 — do
  not run it); prefs.pb stores the level + interactionBehavior as protobuf STRINGS.
  **Campaign SATURATED — backlog 8/8, zero-new-rows pass; roadmap build awaits
  Ernest.** Rig left UP (gen2 armed, app connected).
- 2026-11-14 (pass 11) — Part N executed: the user-row ACTION plane driven end-to-end
  (Copy/side-session/Share/Edit — the side session receipts the full wire+navigate
  chain to sess-side-1), F-P8-E REFUTED-WORKING (the pass-8 pin was a mis-read; the
  gesture release + numbered jump FAB work), the changes viewer (typed error face +
  MOCK_FILECHANGES happy path), the Live-connection window picker driven (3 cycles →
  F-P11-B's stale projection, disk-verified via preferences.pb field 8), the
  attachment chip's no-error face (F-P11-A) + the mock race fixed `1e649eab`
  (verified gen3), the endpoint row pinned inert-by-design, Licenses share + What's
  new + the notification-detail copy receipted, N-BO-1 opened. Rig left UP + gen3
  alive (light rig + MD_FILE + FILECHANGES, Live ON 60-min window).
- 2026-11-14 (pass 10) — Part M executed: backlog items 2/3/7 DONE (the last 8 channel
  tables exact + the ⚠ attributions closed + the 49-channel exposure policy; the i18n
  namespace diff exhausted → G98/G99/G100/G101; the a11y audit of 17 never-swept screens
  → N-BN-1; the diagnostics plane receipted). NEW rows: F-P10-A (P3, the silent commit
  no-op), N-BN-1. F-P9-A 2nd stamp, F-P0-E 2nd stamp. F-P8-C re-verified WORKING (3
  directions). Rig: the PM wedge (launch -92 with a healthy resolver table → wipe-data
  recovery), the fresh-install pairing chain receipted end-to-end, mock debt #8
  (automations needs MOCK_OFFPEAK=1). Rig left UP + connected (gen1d).
- 2026-11-14 (pass 9) — Part L executed: backlog items 1/4/5 done (webRemoteControl
  plane → G97; runtimeCapabilities 13-name schema → G96 + G93 enum residual CLOSED;
  zcode.cjs attribution corrected), 2 new P2 defects F-P9-A/F-P9-B, the 3-pass-owed
  lockscreen leg CORE receipted (shade-tap = standing rig stop), MOCK_REMOTE_DOWN_MS +
  MOCK_MD_FILE first-driven, mention-panel tabs closed, ghost census 5/83 (2+ days).
  Rig left: emulator UP, PIN 1234, Live connection ON (one-tap undo), ZERO relays.
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

- 2026-11-14 (pass 12 correction) — the pass-12 rig-left note was amended: ~10 min
  after the pass closed, the emulator launcher-wrapper exit coincided with a device
  SOFT-REBOOT (verified uptime 0 min, no app FATAL). Rig repaired in-session:
  logcat restarted, app relaunched (pid 2616) + reconnected to gen2 (drum active);
  all settings persisted. The report's Part O stamp block predates the reboot — this
  ledger line is the correction (the report is append-only).
