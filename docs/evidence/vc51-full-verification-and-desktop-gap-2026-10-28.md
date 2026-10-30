# ZCode Companion — full emulation verification + macOS-desktop gap audit (2026-10-28)

Scope: user request — "run the app in a live emulation, verify every feature works, then
reverse the actual macOS ZCode app (REA) and document everything missing or weaker vs it,
so the Android app is on par or better."

Tree under audit: `main` @ `fd7046a` (versionCode 51, versionName 1.3.0), debug APK
`988d74f78cfd732e…` (built this session, 30,286,232 bytes). Desktop target:
`/Applications/ZCode.app` (asar sha256 `99e280fb90b626cc…`, ~327 MB, out/main+host+renderer
bundles; desktop source reference clone v3.14.3 at `~/Desktop/DEVPROJECTS/zcode/repo`).

Evidence: 76 screenshots `/tmp/walk/*.png` (emulator session), mock relay wire log
`/tmp/mock-relay.log` (v4call receipts), app logcat (`ZCODE-PAIR` tag), REA analysis
`/tmp/zcode-asar-summary.json` (Evidence `agm_27661707668b570e42e5d2ec94b159caa023379929d90e7cb7b882ad0fc9af2a`).

---

## Part A — live emulation verification (ALL rig flags armed)

Rig: zcode-pixel AVD (1080×2340, cold boot), MockZCode relay on `10.0.2.2:18100` with
`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1 MOCK_WORKFLOWS=1
MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1 MOCK_ATTACHMENT=1`.
App state: upgrade-over-existing-data path (install -r kept the stored pairing).

### Verified working (each observed on device, wire receipt where relevant)

| Surface | Result |
| --- | --- |
| Pairing/connect | Stored credential reconnects; auto-reconnect proven 3× across emulator crashes (mock handshake each time) |
| Tasks list | Bootstrap rows, workspace chips (All/Synthetic One), pinned section, unread dot, Backend group + edit affordance, Fork-of child task, created task renders (F-CREATE-STUCK fix holds) |
| Task verbs | ⋯ menu renders Unpin / Mark unread / Resume / Rename / Archive / Delete; "Marked unread" toast confirmed |
| + New task | Dialog with description + model line; Create → task row appears live (mock command id=178) |
| Workflows hub | Both saved workflows (project/global scopes), invalid-file warning line, last-run states |
| Workflow Run… | Required-arg field + run-in-task picker; Run fired (mock command id=188), "Workflow started" snackbar, navigates into target task |
| Automations | Full CRUD UI; honest capability fault while the mock refuses the channel (mock-side, not app) |
| Conversation session bar | `● Connected · Completed` + config chip two-way toggle expanding to `mode:` / `Effort ·` / provider·model chips (worst-case long provider id bounded with ellipsis — F-CHAT-FOCUS3 holds) |
| Model + effort switch | Picker sheet with provider groups, Default/Preview badges, search; choosing a model opens effort sheet; selection applied (mock command id=165), session bar echoes `yolo · glm-4.6 · high` |
| Timeline | Rich markdown (headings, nested lists, tables, fenced code with highlight + wrap + copy, inline code, links, strikethrough), image attachment render, tool rows with expand (INPUT block), turn headers with duration + file-changes chip + per-turn diff viewer (honest `fault.fileChanges.none` degrade), compaction lifecycle rows (started/completed/failed + Retry) |
| Row actions | Fork → child session created and navigated (`sess-forked-1` snapshot reconciled); Retry → retried reply row (id=105); 👍/👎 feedback (id=38); Undo files → rewind preview sheet → applied, notice strip + affordance removed (id=156) |
| AskUserQuestion | Multi-question card inside the budgeted pinned stack (scrolls internally), single-select radios + multi-select checkboxes + custom answers + "Skip — continue without my answer"; Send answers → resolveInteraction on the wire (id=77), "(answered)" row appended |
| Approvals inbox | Bell badge count, Question + Permission cards, "Allow once"/"Deny", answer rides the wire (`option=allowOnce`, mock id=137) — but see GAP-A2 |
| Queue mode | While the agent ran: composer flips to "Keep typing to queue follow-up changes" + Queued chip + stop button |
| Send/receive | Typed message → send button → user row + mock reply row on screen (mock id=144) |
| Slash menu | `/` surfaces Commands section incl. the custom `/deploy [env]` command with hint |
| Context sheet | One-tap SessionContextSheet: summary capsule (duration·iteration), goal panel with verification timeline (pass/notSatisfied), todos 1/2 with states, workflow runs, plans viewer (expanded, collapsible), Files/Git/Terminal/Model-calls/Share chip rows; drag-down dismisses |
| Files panel | read-only badge, workspace name, honest "workspace files unavailable on this connection" (mock doesn't serve the channel) |
| Git panel | read-only badge, Checkpoints section, honest no-git-channel message + Retry |
| Terminal | Fail-closed consent gate (switch + explainer) → attach → "live — /bin/zsh" with Pause/Search/Copy visible output/Detach |
| Model calls (trajectory) | Sheet with search field + per-call cards (model · duration · finish reason · tool list, error call rendered) |
| Share | System share sheet with the transcript preview + Copy (ACTION_SEND) |
| Jump-to-bottom | FAB with numbered unread badge (B2.9) |
| Settings | Endpoints (endpoint card with version/state + Add direct endpoint), Insights (Usage / Plugins / Skills / MCP servers), Diagnostics (export / copy to clipboard / add a marker), Appearance (Light/Dark/Follow-system, instant apply + system-bars contrast re-scrim), Notifications (detail Minimal + 3 toggles), Live-connection window + auto-reconnect checks, Terminal view-only consent, Privacy (no analytics), About (Licenses + Check for updates → correct "latest build" for vc51 vs published vc49) |
| MCP viewer | Three scripted servers with transport · tool count · status · failure detail |
| Dark theme | Whole app flips; all visited surfaces render correctly in dark |
| Search FAB | Present on Tasks (not deep-walked this round) |
| **Fresh-install pairing** (second rig leg, `pm clear` + full uninstall/reinstall) | Onboarding pager → paste-link dialog; stale-link consent gate fires correctly on a >10-min link (fail-closed E7); fresh link skips it; security gate with the "No screen lock detected" reduced-security consent variant; Keystore seal → relay auth → **Connected** with full bootstrap render. A corrupted link (harness typing artifact, missing `%3D` in the hash) was honestly rejected by the relay and classified `Pairing expired — scan the QR again` with `retryable=false` + re-pair guidance — the failure path verified too |
| Usage screen | Honest capability fault + Retry (mock doesn't script the channel) |
| Plugins viewer | Both scripted plugins with version/source/state toggles + diagnostics warning line |
| Skills viewer | Search field + all three scripted skills with scope badges (workspace/plugin·prisma-toolkit/user) |
| Diagnostics | Copy-to-clipboard fires the report build + clipboard write — **but see GAP-A5** |

### Defects found in the app (this walk)

- **GAP-A1 (feature break, P1): the workspace hook-trust review card does not exist.**
  ADR-024 landed only half: the domain decode (`SessionState.kt:246,344`), the four v4
  commands (`ConversationPort.kt:270-284`) and the VM verbs (`ConversationViewModel.kt:684-708`)
  all exist, but no composable renders `PendingInteraction.Kind.WORKSPACE_HOOK_REVIEW`, and
  `InteractionCoordinator.reconcileFromSessionState` deliberately parks hook reviews in the
  "counted, no card — INV-14" branch (`InteractionCoordinator.kt:730-733`). Live proof: with
  `MOCK_HOOKS=1` scripting a 3-item review (2 pending + 1 trusted), NO card renders anywhere —
  a session blocking on hook trust has no phone remedy, the exact failure ADR-024 set out to
  close. The ADR's own UX contract (per-item opt-in checkboxes default-off, "Trust N selected
  hooks" disabled at N=0, confirm sheet) is nowhere implemented. Zero UI callers exist for
  trustHooks/revokeHooks/toggleHookItem/grantHookTrust.
- **GAP-A2 (defect, P1): the Approvals inbox never clears answered/resolved interactions.**
  Answered the question from the conversation card (wire ok, state patch `pendingInteractions:[]`
  received — the pinned card cleared) and "Allow once" from the inbox (app log
  `approvals.answer :: interaction=mock-approva option=allowOnce`, mock command id=137): both
  inbox entries remained open through screen re-entry minutes later. `observeOpen` filters
  `resolvedAtMs IS NULL` (`Daos.kt:110`) — the coordinator never marks these records resolved
  (no `interaction.*` warnings in logcat; `v4.call ok in 84ms`). Affects both the state-plane
  and the row-anchored permission classes; the inbox count/badge stays wrong too.
- **GAP-A3 (hygiene, P3): conversations are never unsubscribed.** The host serves
  `unsubscribeConversationV4`/`unsubscribeWorkspaceConfigV4` (extracted host surface,
  `/tmp/host-v4-all.txt`); the phone has no call site anywhere (`app/src/main`,
  `data/src/main`, `core/transport/src/main`). Switching tasks leaves old session
  subscriptions armed on the host — wasted frames to the phone and stale-stream risk after
  reconnects.
- **GAP-A4 (UX, P3): the workflow Run… sheet's Run button is covered by the keyboard** while
  the arg field is focused (observed on device; the tap at the button's AX bounds lands on the
  keyboard). Dismissing the keyboard first works. `imePadding()`/pan behavior for that sheet.
- **GAP-A5 (ANR, P2): "Copy diagnostics to clipboard" can ANR the app.** Reproduced live with
  a full system ANR (`Input dispatching timed out … Waited 5006ms`), trace at
  `/tmp/walk/anr-trace.txt`: `SettingsScreenKt$copyDiagnostics$1$1.invokeSuspend(SettingsScreen.kt:518)`
  calls `ClipboardManager.setPrimaryClip` — a **synchronous binder IPC** — resumed on the Main
  dispatcher; the system clipboard service stalled >5 s and input dispatch timed out. The vc18
  fix moved the REPORT build off-main and the vc20 fix bounded the payload, but the clipboard
  call itself still runs on Main. Fix: hop the `setPrimaryClip` (+ toast) to `Dispatchers.IO`/
  `Default` — clipboard writes are legal from any thread.

## Part B — macOS desktop vs Android companion (REA + source cross-check)

Method: REA `analyze_javascript_application` on the shipped asar (record-and-continue for the
post-signing `pty.node` integrity note), then direct enumeration of the extracted
`out/host/*.js` v4 dispatch surface (`/tmp/host-v4-all.txt`, 37 methods), the desktop source
command registry (`packages/shared/src/zcode-protocol-v4/command.ts`, 34 command types), and
the desktop v4 UI file inventory (`packages/ui/src/v4/`, ~160 files). Cross-checked against
the phone's `core/transport/V4Plane.kt` + `data/session/AgentFacadeClient.kt` +
`data/session/CommandCoordinator.kt` + feature modules, and the Part A device walk.

### B1. Protocol plane parity — VERIFIED AT PARITY

The phone's facade covers the complete host conversation-plane: hello/initialize/subscribe/
resync (conversation, workspace-config), rowsRange, file changes, sendConversationCommand,
attachment upload (begin/chunk/commit/abort/read). Command types: the phone sends 28 of the
desktop's 34; the protocol module (`V4Command.kt`) models all 34.

### B2. Feature gaps (desktop has it, the phone doesn't)

Ordered by user impact. "Modeled" = in `V4Command.kt` but never sent by any coordinator/port.

| # | Gap | Desktop evidence | Phone state | Severity |
| --- | --- | --- | --- | --- |
| G1 | **Hook-trust review card** — the desktop shows the blocking review (banner + details + trust flow); the phone renders nothing | `WorkspaceHookPendingBanner.tsx`, `ConversationHookDetailsAction.tsx`, ADR-024 UX contract | GAP-A1 above; wire+VM ready, zero UI | **P1** |
| G2 | **Approvals resolution bookkeeping** — desktop resolves cleanly | interaction auto-resolution family (`useTaskInteractionAutoResolutionSnooze`, `interactionAutoResolutionCommand.ts`) | GAP-A2 above | **P1** |
| G3 | **Workflow run Resume + run-settings amend** — desktop can resume a resumable run and amend run settings | `resumeWorkflowRun`, `amendWorkflowRunSettings` in command.ts; `workflowResumeGate.ts` | Modeled, never sent; run sheet has no Resume/settings actions | P2 |
| G4 | **Queue item delete** — desktop `deleteQueueItem` | command.ts | Modeled, never sent; phone queue has move/edit/send-now/auto-drain/follow-up only | P2 |
| G5 | **Find in conversation** — timeline find with highlight (`conversationFindHighlightDom`, `useConversationTimelineFind`, `conversationFindIndex`) | desktop v4 UI | Absent on phone (trajectory search only covers model calls) | P2 |
| G6 | **Interaction snooze (auto-resolution)** — desktop `snoozeInteractionAutoResolution` + per-task snooze | `useTaskInteractionAutoResolutionSnooze.ts` | Modeled, never sent | P3 |
| G7 | **Selection actions** — text-selection tooltip/menu in the timeline (copy / selection side session / share selection) | `SelectionActionMenu.tsx`, `MarkdownSelectionTooltip.tsx` | Absent | P3 |
| G8 | **Selection side session** — run a selection in a child session | `createSelectionSideSession` in command.ts | Modeled, never sent | P3 |
| G9 | **Shared-context handover** — share/import conversation context refs (`context_refs` on sendText, `discardSharedContext`, `sharedContext*.ts`) | command.ts | Not modeled at all; composer sends no `context_refs` | P3 |
| G10 | **Suggested prompts on the draft screen** — desktop empty-state suggested prompts with rotation + prefill | `featureSuggestedPrompts.ts`, `draftSuggestedPrompt*.ts` | Absent (draft screen is bare) | P3 |
| G11 | **In-conversation quota banner** — desktop warns when the plan bucket runs low mid-session | `ConversationQuotaBanner.tsx`, `sessionQuotaBannerState.ts`, `startPlanQuotaBuckets.ts` | Phone has the Settings quota section (ADR-020) but no live banner | P3 |
| G12 | **Pending-command recovery banner** — desktop surfaces replayable/failed pending commands | `PendingCommandRecoveryBanner.tsx`, `pendingCommandReplay.ts` | Phone has the outbox + journal machinery but no user-facing banner | P3 |
| G13 | **Sessions-index live plane** — desktop subscribes/resyncs the sessions index (cross-session live activity, row activity states) | `subscribeSessionsIndexV4` family, `sessionsIndexStore.ts`, `taskListRowActivity.ts` | Phone polls/refreshes per-screen instead; no live cross-session feed | P3 |
| G14 | **Conversation subscription lifecycle** — desktop unsubscribes conversations/workspace-config on exit | `unsubscribe*V4` in host dispatch | GAP-A3 above | P3 |
| G15 | **Background bash output viewer** — desktop can read background shell output | `backgroundBashOutputV4` | Not consumed by the phone | P3 |
| G16 | **Attachment preview source** — desktop fetches larger/preview attachment renders | `attachmentPreviewSourceV4`, `conversationAttachmentRead/Stat` | Phone uses `attachmentRead` only (smaller previews) | P4 |
| G17 | **Assistant preview cards for rows** — desktop renders artifact preview cards with pptx auto-open | `useAssistantPreviewCardsForRow.ts`, `assistantPreviewPptxAutoOpen.ts` | Phone has `GeneratedArtifactPreview` (narrower: generated-artifact preview only) | P4 |
| G18 | **CUA (computer-use) row groups + permission schema** — desktop groups CUA tool calls, has a dedicated permission plane | `conversationCuaGroups.ts`, `cuaPermission.ts`, zcode-cua package | Phone renders generic tool groups; no CUA-specific grouping/permission copy | P4 |
| G19 | **Conversation share modes** — desktop shares a selection as a link/read-only timeline with preflight; phone shares the transcript text only | `ConversationShare*` family (13 files) | Phone `conv_share` (transcript export) | P4 |

### B3. Deliberate read-only/companion boundaries (NOT gaps — by design, per ADRs/INV)

- Files browser read-only; Git panel bounded to the ADR-021 mutation set; terminal strictly
  view-only (ADR-022); plugins/skills/MCP read-only viewers (INV-9); workflow authoring stays
  desktop-side; hooks configuration desktop-side (the phone only answers trust reviews —
  which is G1); CUA/IDE workbench panes are desktop form-factor. The phone's automations
  surface is FULL parity (create/edit/restart/run-now/delete wired end-to-end; the mock's
  fault message this round was a rig limitation, not an app gate).

### B4. Where the phone is BETTER than the desktop

- Push-style awareness without a push service: approval/task-finished/connection-lost
  notifications + privacy-preserving Minimal detail, live-connection window, auto-reconnect
  checks, Pending-Approvals glance widget, app shortcuts.
- Voice input (ADR-019) in the composer.
- In-app update path with signed release meta (ReleaseParser fails closed) — verified live by
  Ernest in the field (1.2.5→1.2.6).
- Diagnostics export/copy/marker designed for redaction (no conversation content).
- Multi-endpoint (relay QR pairing + direct-server endpoints) with sealed credentials;
  offline queueing with CAS-guarded retries (desktop relies on its local session).
- Dark/light theme override with instant application and system-bar contrast handling.

## Part C — remediation roadmap (recommended order)

1. **Fix round A (P1/P2 defects):**
   - Build the hook-trust review card (ADR-024 contract: per-item opt-in default-off,
     Trust N disabled at 0, confirm sheet, stale_digest state, trust/revoke/toggle wiring
     already in the VM), with a coordinator branch that routes WORKSPACE_HOOK_REVIEW
     interactions into the pinned-card plane; mock rig legs already script the review.
   - Fix approvals resolution: mark repository records resolved on the acknowledge path AND
     on the state-plane disappearance reconciliation for row-anchored machines; add pins for
     inbox-clears-on-answer (state-plane + row-anchored classes).
   - GAP-A5: move the diagnostics clipboard write (and toast) off the Main dispatcher
     (`SettingsScreen.kt:518`) — one-line coroutines fix, ANR eliminated by construction;
     pin with a Robolectric test that the clipboard write happens off-main.
2. **Fix round B (P2):** resumeWorkflowRun + amendWorkflowRunSettings actions on the run
   sheet; deleteQueueItem in the queue strip; find-in-conversation (search bar over rows).
3. **Fix round C (P3):** unsubscribe-on-exit in the conversation teardown; selection
   tooltip/actions + selection side session; suggested prompts on draft; quota banner in the
   conversation (reuse the ADR-020 quota plane); pending-command recovery banner; sessions
   -index subscription for live task activity; interaction snooze.
4. **Fix round D (P4 polish):** attachment preview source; richer assistant artifact
   previews; CUA row grouping; conversation share link modes.

Acceptance for round A: on the rig, `MOCK_HOOKS=1` renders the review card and a
trust_selected round-trip flips item states live (mock handlers exist); answering either
approval class empties the Approvals inbox without leaving the screen.

---

Verification record: emulator+relay rig ran ~1h with 2 crash-reconnect cycles (emulator
memory-pressure deaths, the known 8GB constraint); no app-side crash, ANR, or error-dialog
was observed at any point.

Unit suites (all 17 modules, `testDebugUnitTest` + `test`): **1487 tests, 0 failures,
0 skipped** at `fd7046a` (fresh XML aggregation across every module's build/test-results).
Three `ConnectionHubWritePathTest` sweeps on this machine hit the documented timing-flake
class — a different test failed each sweep (`draft persists…` then `pending approval…`,
both `TimeoutCancellationException` at the class's 20s/15s `withTimeout` waits), and the
full class passed `--rerun-tasks` and in the final all-module pass; exonerated as machine
timing, not an app defect. The final clean all-module run: BUILD SUCCESSFUL, 368 tasks.

---

# Second pass — 2026-10-28 afternoon (APPENDED — pass 2; not part of the original audit)

Scope: re-run of the same mandate ("live emulation + verify everything + REA the macOS app +
document every gap") on the SAME tree. Since the pass-1 code commit `fd7046a` only docs and a
2-line mock debug print landed (`f4b7fb4`), every pass-1 defect and G-gap is expected to still
exist; pass 2 re-confirms them on device and deep-walks what pass 1 left shallow. No code was
changed by this pass — this is a verification + documentation round only.

Tree under audit: `main` @ `f4b7fb4` (app code identical to `fd7046a`; versionCode 51,
versionName 1.3.0 confirmed via `dumpsys package`), debug APK 30,286,232 bytes (same size as
pass 1's build; this pass's measured sha256 `342164798266a24f…` — pass 1's cited `988d74f7…`
was not reproducible with md5/sha256 on the same file; treat the byte size + code tree as the
identity). Desktop target unchanged: `/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…`
(re-measured, identical to pass 1).

Evidence: 62 screenshots `/tmp/walk2/*.png`; mock relay wire log `/tmp/mock-relay-pass2.log`;
app logcat (ZCODE-PAIR tag) throughout; crash stacks in logcat (three FATAL EXCEPTIONs, all
reproduced live); REA analysis re-run this session — Evidence
`ev_973db796ad995e05273a6596bdad49a56d072aadb30678a750a8305efa63bb4e`
(`analyze_javascript_application`, record-and-continue, retention complete-record, same asar
digest); shipped-tree
enumeration from the REA-extracted `/tmp/zcode-app-extracted` (382 MB, out/main+host+renderer);
desktop source reference clone v3.14.3 `29628c9` at `~/Desktop/DEVPROJECTS/zcode/repo`.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` with the same all-flags arming as pass 1
(`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1 MOCK_WORKFLOWS=1 MOCK_TASKMGMT=1
MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1 MOCK_ATTACHMENT=1 MOCK_SCENARIO=HAPPY`). Emulator
alive ~75 min with zero emulator deaths this round. App state: upgrade-over-existing-data
(install -r kept the stored pairing; auto-reconnected to the fresh mock on first launch).

## Part D — pass-2 live emulation

### D1. Newly found defects

- **GAP-A8 (crash, P1): the ENTIRE outbound attachment surface crashes the app — three
  deterministic FATALs, reproduced live on every arm.** Composer `+` → menu offers
  Mention a file / Photo / Attach a file / Take photo. Tapping **Photo**:
  `IllegalArgumentException: Can only use lower 16 bits for requestCode` at
  `androidx.fragment.app.FragmentActivity.checkForValidRequestCode` launched from
  `WriteComponents.kt:832` (`photoPicker.launch(PickVisualMediaRequest)`) — FATAL, process
  dead. Tapping **Attach a file**: identical FATAL (16:14:38, pid 6454) from the same
  registry path (`docPicker.launch`, `WriteComponents.kt:844`). Tapping **Take photo**:
  identical FATAL (16:15:33, pid 6657, `cameraLauncher.launch`, `WriteComponents.kt:854`).
  Only **Mention a file** works (pure text insertion, verified "@" inserted). The three
  launchers are registered at `WriteComponents.kt:657-668` via
  `rememberLauncherForActivityResult`; the request code derived for this registration exceeds
  the 16-bit FragmentActivity limit, so launch() throws before the picker ever opens. Shipped
  in `eb5ae90` (phase-9 attachments) and never exercised on-device (pass 1 verified only the
  inbound attachment render). A primary feature kills the app 3/3 taps — top of the fix order.
- **GAP-A6 (UI break, P2): the task-search overlay draws in the status-bar band and its
  result rows are stacked, overlapped, and dead.** The search field renders at window level
  with zero inset handling — `tasks_search_field` bounds `[44,11][1036,165]`, i.e. under the
  system clock/battery, which render on top of the field. The single result row measured
  `[0,0][1080,117]` (`tasks_search_hit_synth-task-3`, title at `[44,28]`) — behind the clock
  AND under the field. Mechanism: the search field + result rows are emitted as bare siblings
  AFTER the Scaffold at the screen-function top level (`TaskListScreen.kt:390-443`), so every
  sibling lands top-start in the host Box with no `statusBars()` padding. Two taps at the hit
  row produced no navigation and no logs — the only hit is effectively unusable on device.
- **GAP-A7 (contract break, P2): search results do not replace the grouped list.** The code
  comment promises "results REPLACE the grouped list" and `return`s at
  `TaskListScreen.kt:450`, but the Scaffold body (Automations/Workflows/Pinned/Workspace
  sections) is emitted BEFORE that point and always composes; on device the grouped list
  stayed fully visible below the (misplaced) results. The H1.1 contract is broken on-device.
- **WATCH (unconfirmed, needs a dedicated leg): sending slash text while queue mode is armed**
  ("/deploy prod" with the composer in "Keep typing to queue follow-up changes" mode) rode
  the wire (`sendConversationCommandV4` acked ok in 89 ms) but produced no timeline row and
  left the draft in the composer; a second send after toggling queue mode off acked ok
  (660 ms) and cleared the draft. Likely queue-item semantics rather than a defect, but the
  first send's wire-acked/no-row/no-clear combination is worth a scripted rig leg before
  calling it clean.

### D2. Pass-1 defects re-confirmed (all unchanged — expected: app code identical to fd7046a)

- **GAP-A1 (hook-trust card missing, P1) — RE-CONFIRMED LIVE in the exact scripted session.**
  `MOCK_HOOKS=1` scripts the review on `synth-task-1`; the pass-2 walk opened Synthetic
  task's conversation (that session) with the review pending — no card, banner, or
  trust-UI anywhere on the screen (`/tmp/walk2/27/28`).
- **GAP-A2 (approvals inbox never clears, P1) — RE-CONFIRMED FOR BOTH CLASSES.** The
  question was answered from the conversation card (wire: `sendConversationCommandV4` ok,
  mock id=39) and "Allow once" from the inbox (app log
  `approvals.answer :: interaction=mock-approva option=allowOnce`, mock id=144): BOTH inbox
  entries remained open through re-entry minutes later, and the bell badge stayed at 2
  (landscape capture shows the badge). Mechanism note sharpened: the coordinator's
  `submitElicitationAnswers`/`Decline` only call `interactions.markResolved` when the
  permission machine reaches RESOLVED
  (`InteractionCoordinator.kt:456-458`; the `runCatching` at 456 can swallow the transition
  silently) — the observed outcome is the record never resolves.
- **GAP-A3 (no unsubscribe, P3) — re-checked in code: still zero call sites** for
  `unsubscribeConversationV4`/`unsubscribeWorkspaceConfigV4` across app/data/core-transport.
- **GAP-A5 (diagnostics clipboard ANR, P2) — code still unfixed**:
  `SettingsScreen.kt:518` still calls `setPrimaryClip` inside `withContext(Dispatchers.Main)`.
  The pass-2 live copy ran clean (no ANR) because the preconditions differ from pass 1 — the
  system clipboard service was healthy this time (the pass-1 repro stalled it; the pass-2 run
  only logged the EMULATOR's host-clipboard bridge failing with EINVAL after the fact, a rig
  artifact, not the app). The defect stands on the code path.
- **G1–G19 (desktop gap matrix) — unchanged by definition** (no app code changed); pass 2
  adds evidence and four NEW desktop gaps (G20–G23) in Part E below.

### D3. Surfaces verified at pass 2 (new/deep-walked legs; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| Stored-pairing reconnect | Cold-booted emulator + fresh mock process: app reconnected automatically on first launch with full bootstrap render |
| Rename verb | Dialog prefilled → confirm → `zcode-task.renameTask ok`; row title stayed stale until refresh because the mock's served bootstrap view is static (rig fidelity, NOT an app defect — the mock's `directTasks` DID update) |
| Archive verb | Confirm dialog ("Archived tasks can be restored later") → `zcode-task.archiveTask ok`; row persists because the mock acks without mutating (rig fidelity) |
| Task verbs menu | Full set re-verified incl. Pin + "Move to group…" for unpinned rows |
| Send/receive | "ping-rig2" → user row + AGENT "synthetic reply to: ping-rig2" with copy/👍/👎/Fork/Retry rendered live; queue mode flipped in with Queued chip + stop button (mock set control running) |
| Degraded → reconnect | `pkill MockRelayMainKt` → ~40 s to "Reconnecting… · Completed" + "Showing cached projection · recovering"; relay restart → auto-reconnect within seconds, "Connected · Completed" restored |
| Slash menu | `/` surfaces the desktop's builtin catalog (help/login/logout/compact/init/expert/effort/dwf/fork/locale/mcp/plugins/mode/model/new/resume/rewind/skill/goal…); custom commands fetch fires on open (`commands.list ok` + `subagents.list ok` at menu-open, verified on the wire) |
| Custom command | `/deploy [env]` reachable via prefix filter ("/de"), insert works with the [env] hint; sent as raw text (mock-acknowledged) — see the queue-mode WATCH above; NOTE the bare-"/" panel is a NON-SCROLLABLE column and the user section renders below the fold (~25 builtin entries) — a P3 UX note folded into G21's fix |
| Mention a file | Inserts "@" (the only healthy arm of the attach menu) |
| Voice input | Correctly absent on this AOSP emulator image (availability-gated, `PlatformVoiceInput` → `NoVoiceInput`); fail-quiet, not a defect |
| Notifications | LIVE heads-up banner captured ("ZCode has a question for you") while foregrounded at font 1.3 — the question notification fires in-band, not only on background transitions |
| App shortcuts | Published: Approvals + New task (`dumpsys shortcut`) |
| Glance widget | `PendingApprovalsWidgetReceiver` registered as provider [12] (addable; not bound on this rig) |
| Direct endpoint dialog | Full field set (Name/URL/Token/Fallbacks) + "sealed on-device" language; empty URL leaves Add DISABLED — validation via disabled state, clean |
| Settings | Endpoints card (MockBook · Relay · v3.14.3 · Connected), Insights (Usage/Plugins/Skills/MCP), notification toggles, Live-connection window + auto-reconnect checks, Terminal view-only consent, Privacy (no analytics), About ("You are on the latest build" for 1.3.0 vs published 1.2.8 — correct), Licenses, Unpair guidance |
| Diagnostics copy | Live tap: no ANR with a healthy clipboard (see GAP-A5 note; the code path is still Main-dispatcher) |
| Font scale 1.3 | Tasks screen renders correctly at 1.3 (no crush; the ledger's "font-1.3 crush" did not reproduce on Tasks this round) |
| Landscape | Tasks renders and scrolls in landscape (content column centered with wide side margins — ledger-small class; improved vs the recorded "no-scroll" note) |
| Timeline extras re-verified en route | file-changes list (Parser.kt rewritten / ParserTest.kt +12 −3), kotlin code block with wrap/copy, strikethrough, compaction lifecycle rows (started/completed/failed + Retry), jump-to-bottom FAB, image attachment render |

### D4. Rig learnings for the next pass

1. **The mock's command effects are wired to ONE hardcoded session** (`MockWorkspace.sessionId
   = "synth-task-1"`, `MockZCode.kt:473`): every `applyCommandEffect` patch/row (resolve
   Interaction's `pendingInteractions:[]`, "(answered)" rows, sendText echo) is pushed to
   synth-task-1's stream regardless of which conversation the command came from. Live proof:
   the pass-2 answer was sent from Synthetic task 2's card, and the "(answered)" row appeared
   in SYNTHETIC task 1's timeline (screenshot 27) while task 2's card never cleared. This is a
   RIG routing artifact, not an app defect — and it re-frames pass 1's GAP-A2 evidence: the
   "pinned card cleared via state patch" observation happened because pass 1 answered inside
   the default session. The inbox-never-clears claim is INDEPENDENT of this (re-confirmed this
   pass on both classes) but future rig legs must run interaction flows in synth-task-1.
2. `adb shell input text` needs `%s` for spaces; batched `input keyevent 67…` deletions are
   unreliable from the host — prefer `%s`-composed full strings.
3. Tapping a notification shade / keyboard-dismiss changes the composer's rest position —
   re-dump bounds after every chrome change (three tap misses this round came from stale
   coordinates).
4. The relay must be killed with `pkill -f MockRelayMainKt` (re-confirmed) and restarted
   detached (`nohup … &`); the app reconnects within seconds of the socket returning.

## Part E — macOS desktop pass 2 (REA anchor + shipped-tree enumeration)

REA re-ran the full shipped-asar analysis this session (Evidence
`ev_973db796ad995e05273a6596bdad49a56d072aadb30678a750a8305efa63bb4e`, asar sha
`99e280fb90b626cc…` — byte-identical to pass 1, so the pass-1 B-section conclusions all
stand). Pass 2 enumerated surfaces pass 1 did not, from the REA-extracted tree
(`/tmp/zcode-app-extracted`) plus the v3.14.3 source reference, with shipped-string spot
checks in the renderer bundles.

### E1. Share family — G19 evidence strengthened (no severity change)

The desktop share plane is 16 files in `packages/ui/src/v4/` (SelectionDock, SelectionPanel,
ReadonlyTimeline, ImportNotice, SuccessDock, Scrim, ReopenTab, Markdown, ModePolicy, ModeMotion,
PreflightCache, …). Shipped renderer strings confirm it reaches the bundle: "Copy link",
"read-only review surface". Output modes: selection share, read-only timeline, markdown export,
and a link mode. The phone's `conv_share` (transcript text export) remains the only arm → G19
stands as documented.

### E2. NEW GAP-G20 (P3): desktop Usage depth — four shipped chart views vs the phone's single plane

The shipped renderer carries dedicated lazy chunks `AppUsageDailyModelTrendChart`,
`AppUsageModelUsagePieChart`, `CodingPlanUsageBarChart`, `CodingPlanUsageLineChart` (+ 
`usageStatsUiParts`, `appUsageChartPalette`): the desktop Usage page renders daily per-model
trend, per-model pie, and two coding-plan usage charts. The phone's Usage screen (verified pass
1 + 2) renders a single summary plane and honestly faults on the rig's unscripted channel — no
trend/pie/chart views exist on the phone. On par would mean at least the daily model trend and
plan-bucket charts (the data planes exist in the protocol the phone already speaks).

### E3. NEW gaps from the desktop Settings inventory (123 files, `packages/ui/src/settings/`)

The desktop Settings app is a full resource manager. Mapping it against the phone's
deliberate boundaries (B3) leaves four boundary-adjacent planes the phone neither has nor
claims:

- **G21 (P4): custom slash-command authoring.** Desktop: `CommandsSection.tsx` +
  `CommandForm.tsx` (create/edit custom commands). Phone: consumes custom commands (the /deploy
  leg renders and inserts them) but has no authoring surface. Related P3 UX note from D3: the
  phone's bare-"/" panel is a non-scrollable column — the user-commands section renders below
  the fold and is only reachable by typing a prefix; fix the panel scroll in the same breath.
- **G22 (P4): memory settings plane.** Desktop: `MemorySettingsSection.tsx`. Phone: absent.
- **G23 (P4): coding-plan management.** Desktop: `CodingPlanEntryButton.tsx`,
  `CodingPlanUpgradeDialog*.tsx` (upgrade/bucket management on top of the usage charts in G20).
  Phone: the ADR-020 quota section exists but no plan management/upgrade flow.
- **G24 (P4): settings-sync plane.** The desktop carries a settings-sync subsystem (118
  `settingsSync` references in the shipped renderer, `settings/settings-sync/` in source,
  step titles like "settingsChangedByYou"). Phone: single-device settings, no sync plane.

(Desktop keyboard-shortcut recording (`useShortcutRecording`, "Keyboard Shortcuts" shipped
string) and command palette are desktop form-factor — NOT gaps. Hooks/MCP/plugin/workflow/
automation authoring remain deliberate B3 boundaries — automations CRUD is already at full
phone parity per pass 1.)

### E4. Parity cross-checks that found NO new gap

- **Git**: desktop `GitPane.tsx` (546 lines) exposes refresh + change cards with
  find-in-changes (`GitPane/fileChangeFindSearch.ts`); the only desktop git mutation surface
  found is checkpoint restore (`checkpointRestored` in the main bundle) — matching the phone's
  ADR-021 bounded set (rewind/undo verified live in pass 1). At parity/bounded-by-design.
- **Terminal**: desktop `TerminalSession.tsx`/`TerminalTabTrigger.tsx` (interactive, tabbed) —
  the phone's strictly view-only terminal is ADR-022, unchanged.
- **Find-in-conversation** (G5), **CUA permission panel** (G18 — `cua-permission-panel.html`
  ships as a separate renderer document), **quota banner** (G11) — all re-cited with exact
  shipped artifacts; severities unchanged.

## Part F — remediation order update (supersedes Part C ordering only where pass 2 applies)

1. **GAP-A8 FIRST** — the attachment-send crash class is a P1 crash on a primary surface:
   give the three composer launchers registry keys/registration that keep the derived request
   code ≤16 bits (register via the activity-level registry with short explicit keys, or bump
   androidx.activity; add a Robolectric/instrumented pin that launch() opens the picker without
   throwing for all three arms).
2. **GAP-A1/A2** (unchanged from Part C round A) — plus the sharpened A2 mechanism: assert
   `interactions.markResolved` fires on the accepted-ack path (state-plane + row-anchored
   classes) rather than only checking the wire.
3. **GAP-A6/A7 together** — the search overlay needs an inset-padded container AND the
   results-replacement contract restored; one pin can cover both (results visible below the
   status bar, grouped list absent while a query is active).
4. Part C round B/C/D unchanged; insert G20 (usage charts) into round D polish, G21–G24 as
   boundary-decision tickets (decide: on-par means authoring/sync/plan-management on the
   phone, or documented companion boundaries in B3).

Verification record (pass 2): emulator+relay rig ran ~75 min with ZERO emulator deaths; the
APP crashed 3× — all three crashes are GAP-A8's attach arms, each reproduced deliberately and
each followed by a clean relaunch with stored-pairing reconnect. No other crash, ANR, or
error-dialog was observed. Unit suites were NOT re-run this pass: the app code tree is
byte-identical to `fd7046a`, whose 1487-test green record (Part A, pass 1) remains the valid
baseline for this pass; the only deltas since are docs and a 2-line debug print in the
test-only mock module.

---

# Third pass — 2026-10-28 evening (APPENDED — pass 3; not part of the original audit)

Scope: same mandate, third run ("live emulation + verify everything + REA the macOS app +
document every gap"), on the SAME tree. `140631a` and everything after `fd7046a` is
docs-only, so all pass-1/2 defects and G-gaps are expected to still exist; pass 3 re-confirms
the load-bearing ones, resolves the pass-2 WATCH, deep-walks surfaces both passes left
shallow, and adds two NEW app defects (A9, A10) and two NEW desktop gaps (G25, G26). No code
was changed by this pass — verification + documentation round only.

Tree under audit: `main` @ `140631a` (app code identical to `fd7046a`; versionCode 51,
versionName 1.3.0), debug APK 30,286,232 bytes (the fd7046a build, installed over existing
data). Desktop target unchanged: `/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…`
(re-measured this pass, byte-identical — the pass-1/2 REA Evidence remains valid).

Evidence: 35 screenshots `/tmp/walk3/*.png`; mock relay wire log `/tmp/mock-relay-pass3.log`
(175 v4call receipts incl. the pass-3 send id=73); app logcat (ZCODE-PAIR tag + the GAP-A8
FATAL stack); REA extraction `/tmp/zcode-app-extracted` (pass-2 tree, reused) + desktop
source reference clone v3.14.3 `~/Desktop/DEVPROJECTS/zcode/repo`.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` relaunched via the plain `java -cp` recipe with the same
all-flags arming (`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1 MOCK_WORKFLOWS=1
MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1 MOCK_ATTACHMENT=1
MOCK_SCENARIO=HAPPY`). Emulator alive ~35 min with zero emulator deaths; app state:
upgrade-over-existing-data, stored pairing auto-reconnected on first launch (update.check
sees desktop 3.14.3).

## Part G — pass-3 live emulation

### G1. Newly found defects

- **GAP-A9 (UX break, P2): the queue-mode composer has NO send affordance while the agent is
  stoppable — the exact state queue mode exists for.** The primary composer control MORPHS
  STOP↔SEND on `canStop && stopState==STOPPABLE` alone (`WriteComponents.kt:910-912`); in
  ENQUEUE mode the slot shows STOP unconditionally and SEND simply does not exist. Live proof
  in synth-task-1 (the effect-routing session): sent "ping-rig3" → composer flipped to "Keep
  typing to queue follow-up changes" + Queued chip; typed "/deploy prod" → dump + pixels show
  the composer holding the text with only +/Queued/Stop present (`/tmp/walk3/05`); IME Enter
  inserts a NEWLINE (`/deploy prod&#10;`, no IME send action is configured —
  `composerKeyboardOptions` sets only capitalization/ascii, `WriteComponents.kt:1155-1164`);
  the relay log records NO send call. The Queued pill is a passive label
  (`composer_queue_pill`, `:886-904`) and the QueueStrip renders only when items exist. The
  user is invited to type but cannot dispatch until the agent stops or the control flips
  non-stoppable — and tapping Stop is self-defeating (it kills the run). Desktop contract is
  the INVERSE and is the fix anchor: `showStopControl = canStop && !hasDraftToSubmit`
  (`ConversationComposer.tsx:1136`, with the header comment "running+空草稿 → Stop") — a
  typed draft ALWAYS keeps the send path, even mid-run; the desktop additionally supports
  modified-Enter delivery reversal (`:1099`). Fix: gate the morph on a blank draft (desktop
  semantics), optionally add an IME send action; pin with a Robolectric test that the send
  control exists with a non-blank draft while `canStop`.
- **GAP-A10 (UX, P4): a rejected direct-endpoint add closes the dialog and discards every
  typed field; the only feedback is a transient auto-dismiss toast.** Mechanism:
  `AddDirectEndpointDialog`'s onAdd sets `showAddDialog = false` BEFORE the outcome is known
  (`SettingsScreen.kt:397`), and `ConnectionHub.addDirectEndpoint` probes the server with a
  10s budget before saving (`ConnectionHub.kt:1190`) — so the dialog is already gone while
  the probe runs and a Rejected outcome can only surface as the `switchMessage` toast
  (`AutoDismissMessage`). Live: added "Probe" → `ws://10.0.2.2:18999/ws` (dead port) → at +3s
  the dialog is closed, no endpoint card, no visible error. Secondary: the fallback-URL
  candidates are never validated client-side (the `valid` gate checks only the primary URL's
  scheme, `SettingsScreen.kt:439`; candidates pass through a bare split/trim/filter) while
  the dialog note claims "Validated before saving". Fix: keep the dialog open on Rejected
  with an inline error, validate candidate schemes with the same gate.

### G2. Pass-2 WATCH resolved (queue-mode slash send)

- **Stoppable window:** cannot send at all — that is GAP-A9 above.
- **Non-stoppable window** (after tapping Stop, control flips back): the SAME draft sends
  cleanly end-to-end — wire `sendConversationCommandV4` (mock id=73), user row + AGENT
  "synthetic reply to: /deploy prod" rendered, draft cleared (`/tmp/walk3/08`).
- Pass-2's "wire-acked/no-row/no-clear" observation is re-attributed: that send rode from
  Synthetic task 2 (D4.1 rig routing), and the draft-preserved outcome indicates a
  non-ACCEPTED outcome mapping from the mock for a non-default session — rig fidelity, not an
  app defect. The real break is A9.

### G3. Pass-1/2 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed:** composer + → Photo FATALs the process
  with the identical stack (`IllegalArgumentException: Can only use lower 16 bits for
  requestCode` at `FragmentActivity.checkForValidRequestCode` via the activityResultRegistry,
  20:20:06). One arm this pass (deliberate); the other two arms are the same registry path.
- **GAP-A2 (approvals never clear, P1) — re-confirmed:** the inbox still lists BOTH the
  Permission and Question cards ("task Synthetic task · 11 min ago") and the Tasks bell badge
  reads 2, with no answer having been dispatched this pass between the checks.
- **G10 (bare draft screen) — fresh pixel evidence:** the New-task dialog renders
  workspace selector + description + model line with NO suggested prompts (`/tmp/walk3/22`).
- A1/A3/A5 not re-exercised this pass (app code unchanged; the pass-2 evidence stands as the
  latest on-device record).

### G4. Surfaces deep-walked at pass 3 (new legs; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| Move to group… | Sheet with No group / Backend (✓ current) / New group…; moving task 3 to "No group" → "Removed from group" toast, Backend group disappears from the list; wire rides the LEGACY channel: `zcode-task.applyGroupedTaskViewOrder` ok in 67 ms + `listGroupedTaskViewStructure` reads (app logcat) |
| Model-calls search filter | Typing "429" narrows the sheet to exactly the RateLimitError: 429 card (existence verified pass 1; behavior verified now) |
| Terminal (fresh endpoint state) | Per-endpoint consent gate renders fresh → Enable → "Not attached." + Attach → "VIEW-ONLY live — /bin/zsh" with Pause/Search/Copy visible output/Detach; Search opens an inline "Search output" field |
| Context sheet goal/todos panels | Rendered fully (Status capsule, goal with verification timeline ✓iter1/−iter2, Todos 1/2, workflow runs, plans 1/3) — and both panels are strictly READ-ONLY (collapse chevrons only, no edit/toggle affordances) — the phone-side anchor for new G26 |
| Compaction lifecycle rows | Re-verified incidentally after relaunch: "Context automatically compressed 180K → 42K tokens" ✓ AND "Context compression failed · Retry" ⚠ both render |
| Multi-question flow | The mock's second question card ("Which extras?" with Channel options) renders alongside the first — the pinned stack handles a follow-up question correctly |
| App relaunch state restore | Back-press overshoot to launcher → relaunch → app restores INTO the conversation with nav stack + timeline + pinned cards intact |
| New-task dialog re-verified | Create disabled while description empty; model line "Model: workspace default" |
| Direct endpoint add flow | Dialog fields + sealed-token note + disabled-Add-when-empty (pass-2 evidence re-confirmed); probe-before-save behavior → GAP-A10 |
| Approvals inbox | Both cards render with Allow once / Deny and "Open task to answer" (A2's staleness is the defect, not the rendering) |

### G5. Rig learnings for the next pass

1. **uiautomator dumps go near-empty after ModalBottomSheet sessions** (the dump captures a
   stale/secondary window — 20-40 KB XML with zero text nodes while the screenshot shows a
   full screen). After any sheet leg, drive by screenshot + fixed coordinates and only
   re-dump after a fresh screen transition.
2. **The IME shifts dialogs** — field coordinates move between keyboard-up and keyboard-down
   states; re-screenshot after EVERY keyboard toggle before tapping blind (three mis-typed
   fields this round came from one stale mapping).
3. `tail -n +$VAR` with a variable that picked up surrounding whitespace fails with
   "illegal offset" — compute line offsets with `$(($(...)))` arithmetic, not pasted strings.

## Part H — macOS desktop pass 3 (targeted enumeration)

Asar digest re-measured identical (`99e280fb90b626cc…`) so the pass-1/2 REA analysis and B/E
conclusions stand; this pass enumerated planes the earlier passes did not cite, from the
v3.14.3 source clone plus the extracted shipped tree.

### H1. NEW GAP-G25 (P4, boundary decision): subagent management plane

The desktop Settings carries a full subagent MANAGEMENT page —
`packages/ui/src/settings/SubagentsSection.tsx` ("子智能体管理页集中维护作用域列表、表单和
启用状态"): scope lists, create/edit forms, per-subagent model selection, enable states
(AgentsCapability-gated). The phone already speaks the plane — `subagents.list` fires on the
wire (pass-2 receipt at slash-menu open; `Ports.kt:649`) — but consumes it only for the
slash-command catalog hints and renders NO subagent surface at all, unlike plugins/skills/
MCP which all have read-only viewers (the INV-9 viewer pattern). Boundary decision for the
roadmap: on-par at minimum means a read-only subagent viewer in Settings → Insights, matching
the established viewer pattern.

### H2. NEW GAP-G26 (P3): goal affordances — desktop manages the goal from the UI, phone only via slash text

Desktop evidence: the composer action menu exposes an "add-goal" action
(`ChatPromptActionMenu.tsx:30`, offered on new-session empty drafts so the goal is
session-level), and the goal banner renders pause/resume ICON buttons
(`ConversationStatusPanel.tsx:490-520`, `data-goal-action="pause"/"resume"`). The phone's
goal verbs are fully MODELED AND WIRED — `sendGoal`/`pauseGoal`/`resumeGoal` ride the slash
dispatch (`ConversationViewModel.kt:432-443`) — but are reachable ONLY by typing
`/goal …`/`/goal pause`/`/goal resume` text; the context-sheet goal panel is strictly
read-only (pass-3 live evidence, G4). On-par fix: a "Set goal" affordance on the sheet's goal
panel + pause/resume icons beside the goal title — the VM paths already exist, so this is UI
surfacing only.

### H3. Evidence strengthening for existing gaps (no severity changes)

- **G4 (queue item delete)** — exact desktop anchors: `ConversationQueuePanel.tsx:16-18`
  ships `TID_V4_QUEUE_ITEM_DELETE/EDIT/SEND_NOW` and `:33-38` wires `onDeleteItem` to the
  `deleteQueueItem` command. Phone state unchanged (delete modeled-never-sent; send-now/edit
  exist in the phone's QueueStrip).
- **G15 (background work)** — the desktop composer carries a background-work INDICATOR with
  counts (bash/workflow/subagents, `composer/ConversationBackgroundWorkTrigger.tsx`); the
  phone shows no background-work awareness beyond G15's output-viewer gap. Same P3.
- **G17 family (rich previews)** — `ConversationWorkflowCompletion.tsx` + WorkflowCompletionCard
  render workflow-run completion cards with artifact previews (`useWorkflowRunArtifacts`);
  the phone lists runs with states in the context sheet only. Additional evidence for G17's
  "narrower previews" claim.

### H4. Cross-checks that found NO new gap

- **File rewind granularity** — the desktop's `ConversationFileRewindDialog` previews
  safe/unsafe/ignored file classes for a whole-set rewind (no per-file selection); the
  phone's rewind preview sheet matches that shape (pass-1 verified). At parity.
- **Command center** (`CommandCenterDialog.tsx`, palette-style search) — desktop form-factor,
  NOT a gap (B3 precedent).
- **Task sidebar family** (`TaskList*`, `SortableWorkspaceSidebar`, `TaskInteractionBadge`)
  — the phone's tasks plane covers the same verb set (passes 1–2); the phone's own search
  overlay defects remain tracked as GAP-A6/A7, and G13 already covers the live-activity
  difference.
- **`WorkspaceWebRemoteControlTrigger`** — the desktop-side of the direct-endpoint plane the
  phone already joins (A10's dialog is the phone's client for it); no new gap.

## Part I — remediation order update (supersedes only where pass 3 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 3, identical FATAL).
2. **GAP-A9 joins round A (P2):** adopt the desktop morph contract — show the send control
   whenever the draft is non-blank (`showStop = canStop && draftBlank`), i.e. anchor the gate
   on `hasDraftToSubmit` like `ConversationComposer.tsx:1136`; optionally add an IME send
   action. One-expression fix + Robolectric pin (send control exists with a non-blank draft
   while `canStop`).
3. GAP-A1/A2 and A6/A7 unchanged (Part F items 2–3).
4. **GAP-A10 into round D polish (P4):** keep the add-endpoint dialog open on Rejected with
   an inline error + extend the `valid` gate to the fallback candidates.
5. **G26 (P3) into round C:** goal set/pause/resume affordances on the context-sheet goal
   panel (VM paths already wired). **G25 joins the G21–G24 boundary-decision tickets** (P4;
   minimum on-par = read-only subagent viewer following the INV-9 pattern). G4/G15/G17 now
   carry exact desktop file anchors for whoever implements them.

Verification record (pass 3): emulator+relay rig ran ~35 min with ZERO emulator deaths; the
APP died exactly once — the deliberate GAP-A8 Photo-arm repro — followed by a clean relaunch
that restored the conversation stack; no other crash, ANR, or error-dialog was observed. Unit
suites were NOT re-run: the app code tree is byte-identical to `fd7046a` (all commits since
are docs-only), whose 1487-test green record (Part A) remains the valid baseline — the same
reasoning accepted in pass 2.

---

# Fourth pass — 2026-10-28 night (APPENDED — pass 4; not part of the original audit)

Scope: same mandate, fourth run ("live emulation + verify everything + REA the macOS app +
document every gap"). All commits after `fd7046a` are docs + a 2-line mock debug print, so
every pass-1/2/3 defect and G-gap is expected to still exist; pass 4 re-confirms the
load-bearing ones on device, deep-walks surfaces all three passes left untouched (app
shortcut INVOCATION, the conversation query map, the collaboration-mode picker, Send
feedback, Usage retry, dark-theme dialogs/search, the row message-actions sheet), and adds
two NEW desktop gaps (G27, G28). No code was changed by this pass — verification +
documentation round only.

Tree under audit: `main` @ `045c1ea` (app code identical to `fd7046a` — `git diff
fd7046a..045c1ea` is the report file + 2 mock lines; versionCode 51, versionName 1.3.0 via
`dumpsys package`), debug APK 30,286,232 bytes, sha256 `342164798266a24f…` (the fd7046a
build, installed over existing data, auto-reconnected to the fresh mock on first launch;
update.check sees desktop 3.14.3). Desktop target unchanged: `/Applications/ZCode.app` asar
sha256 `99e280fb90b626cc…` (re-measured this pass, byte-identical — the pass-1/2 REA
Evidence remains valid; asar re-extracted to `/tmp/zcode-app-extracted-p4`).

Evidence: 36 screenshots `/tmp/walk4/*.png` + uiautomator dumps `/tmp/walk4/d*.xml`; app
logcat (ZCODE-PAIR tag: the allowOnce receipt, the usage fault, `feedback.create ok`, the
A8 FATAL + process death); desktop source reference clone v3.14.3 `29628c9` at
`~/Desktop/DEVPROJECTS/zcode/repo` (asar digest unchanged, so no new REA analysis run).

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` via `gradle --no-daemon :tools:mockzcode:mockRelayServer`
with the same all-flags arming (`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1
MOCK_WORKFLOWS=1 MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1
MOCK_ATTACHMENT=1 MOCK_SCENARIO=HAPPY`). Emulator alive ~45 min with zero emulator deaths.

## Part J — pass-4 live emulation

### J1. NEW verified surfaces (first walked this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| Approvals app shortcut (INVOKED) | `am start -a …action.SHORTCUT --es zcode.shortcut approvals` deep-links straight into the Approvals screen ("Requests waiting across your tasks") with both cards rendered — pass 2 verified publication only; the launch path now verified live |
| Conversation query map | The timeline's query-map affordance (next to the last agent row) opens a bottom sheet listing every user query ("Jump to query 1 → here is the screenshot", "Jump to query 2 → ping-rig4"); "Jump to query 1" scrolls the timeline so that row sits at the top — desktop has the same plane (`ConversationTurnNavigator.tsx`, `chat.turnNavigator.label` = "Conversation query map", `onJumpToQuery`) — PARITY, not phone-better |
| Collaboration mode picker | Tapping the `mode: yolo` chip opens "Switch mode" with Build ("Confirm changes before running.") / Edit ("Edit files automatically.") / Plan ("Plan before editing.") / Full access ("Run with fewer confirmations."); switching to Plan echoed on the session bar (`plan · GLM-5.3-Flash · max`, chip content-desc flips), restored to Full access after — passes 1–3 walked model+effort but never the MODE switch |
| Send feedback (Settings) | Full dialog: Bug / Suggestion / Slow performance chips + "What happened?" + Contact (optional) + Submit → `feedback.create ok` on the wire (21:41:11) → success state "Ticket mock-ticket-5072 created — thank you!"; the desktop counterpart plane exists (`feedback/feedbackSubmissionJob.ts`) — the phone is the companion client of the same plane |
| Usage retry leg | Retry fires `getAppUsageStats` on the wire, receives the mock's honest `fault.mock.unscripted` and stays on "Usage is unavailable" — the retry path works, the fault state is honest (mock doesn't script the channel) |
| Message actions sheet | LONG-PRESS (not tap) on a user row opens "Message actions" — for a SENT row only Copy renders; the Edit item is gated (`editable && onEdit != null`, sent rows fail the gate). Desktop has no message-edit affordance at all — PARITY |
| Endpoint card | Tap and long-press are no-ops with a single endpoint (display-only); switching/removal surfaces appear only with multiple endpoints — no defect |
| Dark theme across new surfaces | Settings (rows + toggles + live-connection controls), Tasks, and the search overlay all render correctly in dark; the A6 status-bar band is still visible in dark (below) |

### J2. Pass-1/2/3 defects re-confirmed this pass (all at `045c1ea`, code unchanged as expected)

- **GAP-A8 (attach-send crash, P1) — re-confirmed:** the attach menu renders all four arms;
  tapping **Photo** FATALs with the identical stack (`IllegalArgumentException: Can only use
  lower 16 bits for requestCode` at `FragmentActivity.checkForValidRequestCode`,
  21:44:04) and the process dies (`Process com.zcode.android.app (pid 4038) has died`);
  clean relaunch restored the app. Third consecutive pass with the same repro.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a THIRD time, now with a fresh answer
  in the same session:** this pass dispatched a new "Allow once" (`approvals.answer ::
  interaction=mock-approva option=allowOnce`, 21:33:11) — the Permission card stayed open in
  the dump 3 s later, the Question card too, and the Tasks bell badge still reads 2.
- **GAP-A6 (search overlay in status-bar band, P2) — fresh pixel evidence in dark:** the
  search field renders at window level and the system clock paints INSIDE it ("Search
  tasks" placeholder overlapped by "9:42", `/tmp/walk4/31`).
- **GAP-A9 (queue-mode send dead-end, P2) — re-confirmed:** "ping-rig4" sent while idle →
  mock flips running → composer flips to Queued chip + "Stop the agent"; a typed draft
  ("xfollowup-draft") sits with ONLY Stop present, no send affordance; after Stop the same
  draft gets its Send control back (the complementary leg from pass 3 G2 still holds).
- **G21 UX note (slash panel non-scrollable) — re-confirmed by swipe:** a swipe-up on the
  bare-"/" panel produced zero scroll (same rows at identical bounds, `/help`…`/expert`
  fixed); user commands stay below the fold, reachable only by typing a prefix.
- A1/A3/A5/A7/A10 not re-exercised this pass (app code unchanged; the pass-2/3 evidence
  stands as the latest on-device record).

### J3. Rig learnings for the next pass

1. **Back-press overshoot exits the app when the conversation/tasks root is showing** — the
   launcher appeared twice this pass from an extra `keyevent 4` (once mid-relaunch); the
   app relaunches cleanly but the extra back costs a whole navigation cycle.
2. The message-actions sheet opens on LONG-PRESS (`input swipe x y x y 700`), not tap —
   taps on user rows are consumed by selection/no-op.
3. The mode chip lives in the EXPANDED config row (tap the session-bar config chip first);
   tapping the collapsed chip coordinates only expands the row.

## Part K — macOS desktop pass 4 (targeted enumeration)

Asar digest re-measured identical (`99e280fb90b626cc…`) so the pass-1/2 REA analysis and
B/E/H conclusions stand; this pass enumerated settings/timeline planes the earlier passes
did not cite, from the v3.14.3 source clone.

### K1. NEW GAP-G27 (P4, boundary decision): model-provider management plane

The desktop Settings carries a full provider-management section —
`packages/ui/src/settings/ModelProviderSection.tsx` (1,161 lines): add/edit provider
entries with API keys (`getProviderFormApiKey`, `addProviderAction`), re-authorization
after connection ("provider apiKey 会先于权益接口结果落盘"), provider listing. The phone's
model picker (verified pass 1) CONSUMES the provider list with groups/badges/search but has
no add/edit/credential surface anywhere. Joins the G21–G26 boundary-decision tickets;
minimum on-par = a read-only provider viewer following the INV-9 viewer pattern, with
credential entry documented as desktop-side.

### K2. NEW GAP-G28 (P4): inbound attachment preview affordance — desktop previews media/PDF, phone thumbnails are dead-ends

Desktop: `ChatMediaAttachmentPreviewDialog.tsx` opens attachments as VIDEO (loading/
ready/unsupported states) and PDF (`pdf-viewer.js` with zoomIn/zoomOut controls) plus
generic media preview. Phone: `AttachmentView` (`RowRenderers.kt:1330-1350`) renders images
as a STATIC thumbnail (bitmap via the attachment-read channel, cached) and every other type
as a named chip — the composable has NO clickable modifier at all. Live proof: tapping the
mock.png thumbnail in the timeline produced no viewer, no zoom, nothing (screenshots
06→07). On-par minimum: tap-to-zoom full-screen image viewer; PDF passthrough via an
external-viewer intent (no PdfRenderer bundling needed for the first cut).

### K3. Evidence strengthening for existing gaps (no severity changes)

- **G18 (CUA family)** — the desktop additionally carries `BrowserSettingsSection.tsx`
  (browser-use plugin, Chrome data import/clear, embedded-browser certificate toggle) —
  more desktop-side computer-use/browser tooling the phone deliberately doesn't mirror;
  same desktop form-factor bucket as G18, not a separate gap.
- **Settings inventory completion** — `MigrationSection.tsx` (data migration between
  desktops, range/workspace filters), `ShortcutSettingsSection` (already B3 form-factor),
  and the onboarding AGENTS.md migration (`OnboardingDialog.tsx:320` "Copy CLAUDE.md to
  AGENTS.md") are all desktop-local device-management/config-authoring planes — B3
  boundaries, not gaps.

### K4. Cross-checks that found NO new gap

- **Todos** — desktop todos are agent-driven too (`messageStreamShowTodos` is only a
  DISPLAY setting in `SessionPane.tsx:1368`; the TodoWrite stream is tool output) — no
  user-edit plane on either side; the phone's read-only todos panel in the context sheet is
  at parity.
- **Message edit/delete** — desktop has no edit/delete for sent messages (`editMessage`/
  `deleteMessage`: zero matches); the phone's gated Edit item (J1) never fires for sent
  rows — parity.
- **Query map** — desktop `ConversationTurnNavigator.tsx` is the same plane (J1); parity.
- **Feedback** — desktop owns the same plane (`feedback/feedbackSubmissionJob.ts`); the
  phone's dialog is the companion client — parity.

## Part L — remediation order update (supersedes only where pass 4 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 4, identical FATAL + process death).
2. GAP-A1/A2, A6/A7, A9, A10 unchanged (Part I ordering stands; A2 now has a third
   same-session receipt, A6 a dark-mode pixel capture).
3. **G28 (P4) into round D polish:** tap-to-zoom image viewer + PDF/video named-chip
   passthrough (external intent) — the attachment bytes already flow through the
   attachment-read channel the thumbnails consume.
4. **G27 joins the G21–G26 boundary-decision tickets** (P4; minimum on-par = read-only
   provider viewer per INV-9, credentials stay desktop-side).

Verification record (pass 4): emulator+relay rig ran ~45 min with ZERO emulator deaths;
the APP died exactly once — the deliberate GAP-A8 Photo-arm repro (third pass running the
same repro) — followed by a clean relaunch. No other crash, ANR, or error-dialog was
observed. Unit suites were NOT re-run: `git diff fd7046a..045c1ea` contains only the report
file and 2 mock-module lines, so the app code tree is byte-identical to the 1487-test green
baseline (Part A) — the same reasoning accepted in passes 2 and 3.

---

# Fifth pass — 2026-10-29 small hours (APPENDED — pass 5; not part of the original audit)

Scope: same mandate, fifth run ("live emulation + verify everything + REA the macOS app +
document every gap"). All commits after `fd7046a` are docs + a 2-line mock debug print, so
every pass-1..4 defect and G-gap is expected to still exist; pass 5 deep-walks surfaces all
four passes left untouched (the off-peak plane, compaction Retry, the jump-to-latest unread
badge COUNTING, Share→Copy end-to-end, terminal Copy visible output, diagnostics markers,
skills search, the notification-detail cycler, conversation landscape) and adds one NEW app
defect (A11) and one NEW desktop gap (G29, with a record correction). No code was changed by
this pass — verification + documentation round only.

Tree under audit: `main` @ `7f0b1f9` (app code identical to `fd7046a` — `git diff
fd7046a..7f0b1f9` is the report file + 2 mock-module lines; versionCode 51, versionName 1.3.0
via `dumpsys package`). The pass-4 install is still on the AVD (lastUpdateTime 2026-10-28
21:30:55 — the fd7046a build, 30,286,232 bytes, sha256 `342164798266a24f…`); it
auto-reconnected to the fresh mock on first launch and update.check sees desktop 3.14.3.
Desktop target unchanged: `/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…`
(re-measured this pass, byte-identical — the pass-1/2 REA Evidence remains valid).

Evidence: 45 screenshots `/tmp/walk5/*.png` + uiautomator dumps `/tmp/walk5/d*.xml`; mock
relay wire log `/tmp/mock-relay-pass5.log` (compaction-Retry receipt id=19, ping send id=79,
queued-draft send id=91); app logcat (`diagnostics.mark`, `approvals.answer … option=allowOnce`,
the A8 FATAL + `Process com.zcode.android.app (pid 4483) has died`); desktop source reference
clone v3.14.3 `29628c9` at `~/Desktop/DEVPROJECTS/zcode/repo`.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` via `gradle --no-daemon :tools:mockzcode:mockRelayServer`
with the same all-flags arming (`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1
MOCK_WORKFLOWS=1 MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1
MOCK_ATTACHMENT=1 MOCK_SCENARIO=HAPPY`). Emulator alive ~40 min with zero emulator deaths.

## Part M — pass-5 live emulation

### M1. NEW verified surfaces (first walked this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **Off-peak plane — record correction** | The phone HAS a full off-peak monitor/control UI that passes 1–4 never reported: `OffPeakLine` rows inside the Automations section (`AutomationsSection.kt:207-246,575-664` — status label per the desktop `offPeak.status.*` vocabulary (INV-8), pause/continue/cancel with a cancel-confirm sheet, `queuePosition`/`failureReason` on the row model `ViewerModels.kt:424-434`), wired end-to-end (`ViewerPorts.kt:59,244-250` → `off-peak-task` channel; `ViewerTools.kt:110-114` workspace-filter/sort/cap 20; pins `OffPeakControlsUiTest`/`OffPeakLongTailUiTest`/`AutomationOffPeakDecodeTest`/`TaskManagementWireTest:168-194`). NOT exercised live: the rig CANNOT reach it — `DefaultAutomationsPort.lists()` evaluates `tools.automations(...)` FIRST (`ViewerPorts.kt:52-59`), the mock faults `fault.mock.unscripted:listAutomations` (no env flag arms that channel; `MockRelayMain.kt` scripts neither automations nor `offPeakTaskList`), the exception short-circuits BEFORE the off-peak read fires, and the section renders only when the list is non-empty. The on-screen Automations body therefore stays at pass-1's honest fault; the two cards beside it are the saved WORKFLOWS (MOCK_WORKFLOWS), not automations |
| Compaction-failed Retry | Tapping Retry on "Context compression failed" fires `sendConversationCommandV4 ok in 41ms` (mock id=19); the failed row persists because the mock acks without mutating the lifecycle (rig fidelity, same class as rename/archive) |
| Jump-to-latest unread badge COUNTING | Query map "Jump to query 1" scrolls the timeline away from the bottom → FAB appears; with the mock emitting 4 new rows while scrolled away, the FAB renders the red badge **"4"**; tapping it jumps to the newest row ("synthetic reply to: badge-leg") and the FAB + badge disappear. Pass 1 verified presence only (B2.9) — the counting/clear behavior is now verified live |
| Conversation query map | Re-verified incidentally: the sheet now lists THREE queries ("Jump to query 1/2/3") — it grows with new sends; jump lands on the target row (query 1's "here is the screenshot") |
| Share → preview sheet → Copy | Context-sheet Share chip opens the app's own share PREVIEW sheet (title "Share", task name + transcript preview lines, a Copy pill, and the SYSTEM chooser below with Bluetooth/Gmail/Messages/Drive); Copy dismisses both sheets with a toast (Toast lifecycle in logcat; clipboard content not host-readable on API 30). Pass 1 verified only that a share sheet with Copy EXISTS |
| Terminal "Copy visible output" | Attached ("VIEW-ONLY live — /bin/zsh", Pause/Search header chips, bottom action row): tapping Copy flips the chip label in place to **"Copied"** (empty pane → empty copy; the confirm behavior is the verification) |
| Diagnostics "Add a marker" | `diagnostics.mark :: user marker at 2026-10-29T00:24:43.562Z phase=CONNECTED` — timestamped marker written into the app log with the connection phase |
| Skills search filter | Typing "sql" narrows the viewer to exactly `skill-sql-migrate` (release/commit skills removed from the tree); clearing restores all three |
| Notification detail cycler | The row CYCLES on tap — `Notification detail: Minimal` ⇄ `Notification detail: Task titles` (two levels, live label swap, explainer text unchanged). Pass 1 saw only Minimal; the second level and the cycling behavior are new evidence. Left on Minimal |
| mock.png red block — NOT a defect | The attachment thumbnail renders as a solid RED rectangle because the rig arms a 1×1 RED pixel PNG (`MockRelayMain.kt:99-104`, base64 `…GP4z8AA…`); the render is CORRECT at any size. Documented so future passes don't chase it |
| Per-turn diff dialog (re-observed) | "Changes · turn 44 — Could not load changes: fault.fileChanges.none" — the honest degrade again (opened accidentally via a "view"-chip tap en route) |
| MCP rows | github (stdio · 12 tools · Connected), docs-search (http · 0 tools · Disabled), playwright (sse · ECONNREFUSED detail inline) — row taps are display-only no-ops; failure detail is inline text, matching pass 1 |

### M2. NEW defect

- **GAP-A11 (UX break, P3): the conversation screen renders broken in landscape.** First
  conversation-landscape walk (the ledger's recorded "no-card landscape squeeze" is the
  TASKS screen; this is the conversation WITH scripted cards). Live evidence
  (`/tmp/walk5/32-landscape.png`, `user_rotation=1`): (1) the pinned interaction card clips
  to a ~58px sliver — only the bottom edge of the custom-answer field renders while the
  "Agent needs your input" content is cut entirely; (2) the next agent row's text
  ("synthetic reply to: queued-draft-p5") renders OVERLAPPING the clipped pink strip; (3)
  the jump-to-latest FAB floats mid-content on top of the "YOU 12:12 AM" row; (4) the
  3-button nav bar (right edge in landscape) paints OVER the app content — the conversation
  column extends under the system buttons with no nav-bar inset, unlike Tasks landscape's
  centered/margined column (pass 2). Portrait unaffected. Fix direction: give the
  conversation scaffold the same landscape treatment as Tasks (inset/margined column,
  height-capped pinned stack, FAB anchored to the composer edge).

### M3. Pass-1..4 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed a FOURTH time with the same deliberate
  repro:** composer + → Photo → identical FATAL (`IllegalArgumentException: Can only use
  lower 16 bits for requestCode` at `FragmentActivity.checkForValidRequestCode:714`,
  00:28:34) and `Process com.zcode.android.app (pid 4483) has died`; clean relaunch
  restored the conversation stack. Fourth consecutive pass reproducing it.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a FOURTH time, fresh answer this
  session:** inbox "Allow once" dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 00:29:17) — the Permission card AND the Question card both remained in
  the dump 4 s later. The crash-restart between legs also re-showed the inbox counting from
  the stored records (badge/stale-records survive process death).
- **GAP-A9 (queue-mode send dead-end, P2) — re-confirmed (third observing pass), now with
  the full cycle in one session:** "ping-rig5" sent while idle (mock id=79) → composer flips
  to "Keep typing to queue follow-up changes" + Queued chip; typed draft
  "queued-draft-p5" held with ONLY "Stop the agent" present, no send affordance; after Stop
  the Send control returns in the same slot with the draft intact; the draft then sends
  cleanly (`sendConversationCommandV4 ok in 96ms`, mock id=91) and the mock replies.
- A1/A3/A5/A6/A7/A10 not re-exercised this pass (app code unchanged; the pass-2/3/4
  evidence stands as the latest on-device record for each).

### M4. Rig learnings for the next pass

1. **Host-injected drags cannot scroll the conversation timeline or the pinned question
   card while a scripted interaction is pending** — `input swipe` (5 shapes incl. slow
   drags and fast flings) AND an `input motionevent DOWN/MOVE/UP` sequence all produced
   identical screens. The question-card Submit stays unreachable (the pass-2 "Submit pushed
   off-screen" learning compounds with this). Programmatic navigation still works — the
   query-map jump is the reliable way to move the timeline. Interaction legs that need
   scrolling are rig-blocked until a working scroll driver is found.
2. **uiautomator dumps go STALE (repeating pre-dialog content, byte-identical) after any
   dialog/sheet session** — the post-dialog dumps kept showing the closed diff dialog.
   Verify against screenshots; trust a dump only after a fresh screen transition.
3. **The screenshot display scale VARIES between captures** — two Share-chip taps missed
   because device coords were derived with the wrong scale. Calibrate per capture: measure
   a node in the CURRENT dump whose on-screen position is visible, derive the scale from
   it, then tap. (The Share chip finally landed at (326,2128) after calibrating off
   "Ship the release".)
4. **AX-vs-paint artifact, second sighting class:** bottom-anchored WRAPPED rows report
   [0,0][0,0] while painted — the context sheet's second chip line (Model calls/Share) and
   the terminal's bottom action row (Copy visible output/Detach). Tap by pixel; do not
   treat the AX absence as "not on screen".
5. **mock.png is a 1×1 RED PNG by rig design** — its thumbnail legitimately renders red
   (M1 row above). One rig cycle was spent proving this; don't repeat it.
6. Back-press overshoot re-confirmed (one extra back from Settings root exited to the
   launcher); `input text` lands in whichever field holds focus — tap the exact field
   (re-dump first) or the text silently goes elsewhere.

## Part N — macOS desktop pass 5 (targeted enumeration)

Asar digest re-measured identical (`99e280fb90b626cc…`) so the pass-1/2 REA analysis and
B/E/H/K conclusions stand; this pass enumerated the settings planes the earlier passes did
not cite, from the v3.14.3 source clone.

### N1. NEW GAP-G29 (P4, boundary decision): off-peak authoring + run history — the phone monitors, only the desktop creates

Desktop: a full off-peak (闲时任务) management plane in Settings — `OffPeakTaskList.tsx`
(2-column card grid with the "#N in queue" position badge and continue/pause/delete hover
menu), `OffPeakEditView.tsx` (create/edit form, `resolveLocalizedOffPeakCreateTitle`),
`OffPeakHistoryTab.tsx` (run history), `OffPeakEditActionsMenu.tsx`, and
`offPeakUiPresentation.ts` (status footers, model-selection issue warnings); the host-side
service is `OffPeakTaskService` (`packages/desktop/src/host/index.ts`), and the protocol
registers the plane via the `offPeakToolEnabled` capability flag on sessionStart
(`command.ts:61-64` — "不透传则 OffPeakCreate/OffPeakList 永不注册") plus
`offPeakTaskId`/`offPeakRunType(init|resume)` on session start (`command.ts:105-124`).
Phone state (pass-5 M1 record correction): the monitor/control half EXISTS — rows with
pause/continue/cancel riding the same channel — but there is NO create/edit surface and NO
run-history view, and the phone's sessionStart never sends `offPeakToolEnabled` (zero
matches in core/data), so the phone can only see tasks the DESKTOP created with the tool
enabled. Joins the G21–G27 boundary-decision tickets; minimum on-par = a create/edit entry
point on the existing Automations/off-peak section + a history list, reusing the wire the
phone already speaks.

### N2. Evidence strengthening for existing gaps (no severity changes)

- **G24 (settings-sync)** — the desktop's sync plane reaches beyond desktops:
  `ExternalAgentImportDialog.tsx` imports skills/commands/MCP/plugins from EXTERNAL AGENTS
  (`SettingsSyncAgentSummary`, `SettingsSyncCategory`, "外部 Agent 导入弹窗复用
  skills/commands 的交互状态机") — the sync/import subsystem is larger than "sync between
  your desktops".
- **G1 (hook-trust review card)** — the settings-side files of the same plane surfaced:
  `workspaceHookReviewCommands.ts`, `workspaceHookTrustState.ts`,
  `useWorkspaceHookInlineTrust.ts`, `HooksSection.tsx`/`HookForm.tsx`/`HooksList.tsx` —
  more exact anchors for whoever implements the phone card.
- **G18 (CUA family)** — `ComputerUseSection.tsx` confirmed in Settings (same
  desktop-form-factor bucket as pass 4's `BrowserSettingsSection`).

### N3. Boundary inventory completion (NOT gaps — B3 families, cited for completeness)

- **Plugin store/install/manage** — `PluginStorePage/Card/DetailView/ListView`,
  `PluginAddMenu`, `AddMarketplaceSourceDialog`, `PluginScopeMenu`,
  `PluginUninstallConfirmDialog`, `InstalledPluginManagement`, `PluginConfigControls`: the
  desktop browses marketplaces, installs and configures plugins. The phone's plugins viewer
  stays INV-9 read-only by design (B3); this is the same boundary class as MCP management —
  list it in the same boundary-decision ticket if on-par is ever redefined.
- **ProactiveSuggestionsSetting.tsx** — an office-mode proactive task-suggestions toggle
  (`chat.officeSuggestions.*`, `useIsOfficeMode`): desktop interface-mode plane, not a
  phone gap.
- `DataBaseDirControl.tsx` (data directory), `saved-workflows/` (authoring, B3),
  `AutomationScheduled*`/`AutomationTemplate*` design primitives (the phone's automations
  CRUD is already at parity) — desktop-local, unchanged.

### N4. Cross-checks that found NO new gap

- **Off-peak monitor parity** — the phone's row model carries `queuePosition` and
  `failureReason`, matching the desktop card footer ("#N in queue") and status vocabulary;
  the verbs match (pause/continue/cancel). Only authoring/history differ (G29).
- **Skills/MCP viewers** — search filter works (M1), rows display-only with inline failure
  detail; same INV-9 shape as pass 1.
- **Notification detail** — the phone's two-level cycler (Minimal/Task titles) covers the
  privacy-preserving detail contract; the desktop has no equivalent per-notification detail
  plane to mirror (the phone side is the richer one here — see B4).

## Part O — remediation order update (supersedes only where pass 5 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 5, identical FATAL + process death; fourth
   consecutive pass with the same repro).
2. GAP-A1/A2, A6/A7, A9, A10 unchanged (Part L ordering stands; A2 now has a fourth
   same-session receipt, A9 a full-cycle session receipt).
3. **GAP-A11 joins round B (P3):** landscape conversation scaffold — nav-bar inset +
   margined/centered column (Tasks-landscape precedent), height-capped pinned stack with
   clean clipping, FAB anchored to the composer edge. Pin: a Robolectric landscape
   composition test asserting the pinned card's visible height ≥ its title row and no
   overlap between the stack and the first timeline row.
4. **G29 joins the G21–G27 boundary-decision tickets** (P4; minimum on-par = off-peak
   create/edit + run-history list on the existing Automations section; the monitor/control
   half is already at parity and should be stated as such in B3).
5. Rig remedy worth a small mock change in the NEXT code-touching pass: add
   `MOCK_AUTOMATIONS` + `MOCK_OFFPEAK` env arms so `lists()` completes and the off-peak
   section can finally be exercised live (the mock's handlers
   `handleOffPeakTaskPromise`/`offPeakTaskList` are already scripted — only the env flags
   are missing).

Verification record (pass 5): emulator+relay rig ran ~40 min with ZERO emulator deaths;
the APP died exactly once — the deliberate GAP-A8 Photo-arm repro (fourth pass running the
same repro) — followed by a clean relaunch. Census: 1 FATAL total, 0 ANRs, 0 app-level
ERROR log lines. Unit suites were NOT re-run: `git diff fd7046a..7f0b1f9` contains only the
report file and 2 mock-module lines, so the app code tree is byte-identical to the
1487-test green baseline (Part A) — the same reasoning accepted in passes 2, 3 and 4.

---

# Sixth pass — 2026-10-30 (APPENDED — pass 6; not part of the original audit)

Scope: same mandate, sixth run ("live emulation + verify everything + REA the macOS app +
document every gap"). All commits after `fd7046a` remain docs + 2 mock-module lines, so
every pass-1..5 defect and G-gap is expected to still exist; pass 6 deep-walks the task
verb plane end-to-end for the first time (Rename / Archive / Resume / Move-to-group plus
the group CREATE + ASSIGN loop — never executed in passes 1–5), the composer's @-mention
entry, the model-calls search filter, the terminal Pause/Search chips, and the
notification + live-connection toggle persistence. One NEW desktop gap (G30, bots) and a
handful of boundary-plane completions; NO new app defect — the untouched surfaces all
behaved. No code was changed by this pass — verification + documentation round only.

Tree under audit: `main` @ `322b9a3` (app code identical to `fd7046a` — `git diff
fd7046a..322b9a3` is the report file + 2 mock-module lines; versionName 1.3.0 via
`dumpsys package`). The pass-4/5 install is still on the AVD (lastUpdateTime 2026-10-28
21:30:55, the fd7046a build); it auto-reconnected to the fresh mock on first launch and
update.check sees desktop 3.14.3. Desktop target unchanged: `/Applications/ZCode.app`
asar sha256 `99e280fb90b626cc…` (re-measured this pass, byte-identical — the pass-1/2 REA
Evidence remains valid).

Evidence: 36 screenshots `/tmp/walk6/*.png` + uiautomator dumps `/tmp/walk6/d*.xml`; mock
relay wire log `/tmp/mock-relay-pass6.log` (142 v4call lines; renameTask/archiveTask/
resumeTask/createTaskGroup/listGroupedTaskViewStructure receipts); app logcat
(`approvals.answer … option=allowOnce`, the A8 FATAL + `Process com.zcode.android.app
(pid 4739) has died`); desktop source reference clone v3.14.3 `29628c9` at
`~/Desktop/DEVPROJECTS/zcode/repo`.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` via `gradle --no-daemon :tools:mockzcode:mockRelayServer`
with the same all-flags arming (`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1
MOCK_WORKFLOWS=1 MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1
MOCK_ATTACHMENT=1 MOCK_SCENARIO=HAPPY`). Zero emulator deaths; one deliberate app death.

## Part P — pass-6 live emulation

### P1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **Task Rename (full loop)** | Task-2 ⋯ → Rename → dialog with pre-filled field → edit → confirm → `zcode-task.renameTask ok` (00:55:08). The row label stays "Synthetic task 2" because the mock acks without mutating its task store (the pass-5-documented rig-fidelity class for rename/archive); the app's dispatch path is what this pass verifies |
| **Task Archive (typed confirm)** | Task-3 ⋯ → Archive → confirm sheet `Archive "Synthetic task 3"? Archived tasks can be restored later.` (Cancel/Archive) → `zcode-task.archiveTask ok` (00:58:48). First execution of the verb (passes 1–5 only listed the menu item) |
| **Task Resume** | Task-3 ⋯ → Resume → `zcode-task.resumeTask ok` (01:03:21). First execution |
| **Move to group… sheet** | Task ⋯ → Move to group… → bottom sheet "Move to group" listing `No group / ● Backend ✓ / New group…` — the current group carries a checkmark. First walk of the sheet (pass 1 listed the menu row only) |
| **Task-group CREATE** | Sheet → "New group…" → dialog (name field + Create) → "Rig-p6" → `zcode-task.createTaskGroup ok` + immediate `zcode-task.listGroupedTaskViewStructure begin/ok` refresh → the Tasks list renders a new `● Rig-p6 [1] ✏️` group header. The phone can AUTHOR task groups — the desktop's `✏️` group-edit affordance counterpart verified live for the first time |
| **Task-group ASSIGN** | Sheet → pick "Rig-p6" → "Moved to group" snackbar → Synthetic task 3 now renders UNDER the Rig-p6 header (count 1). The mock's group store DOES mutate for the group plane (unlike rename/archive) — the full create→assign→render loop is observable end-to-end |
| **Workspace chips** | All ⇄ Synthetic One selection toggle verified (filled chip swaps; tapping "Synthetic One" fills it and "All" un-fills). The filter itself is non-discriminating on this rig — every mock task lives in Synthetic One, so the list content is identical either way (recorded so future passes don't chase a "broken" filter) |
| **Composer @-mention entry** | + menu carries `Attach a file / Mention a file / Photo / Take photo`; "Mention a file" inserts `@` into the composer and focuses it. The live suggestion panel stays hidden by the empty-hits gate (`WriteComponents.kt:699` renders only `mentionHits.isNotEmpty()`): the port's `searchWorkspaceFiles` is the mock's unscripted channel (same class as the Files panel), `runCatching → getOrDefault(emptyList())` swallows the fault — rig-blocked, NOT an app break (the mechanism is unit-pinned in `MentionFilesUiTest`, which feeds the provider directly). Bare `@` with no query also shows nothing by design |
| **Model-calls search filter** | Context sheet → Model calls → pane with search field + per-call cards (main `glm-5.3 · 4200ms · tool_calls / Checking the workspace state / tools: bash`, main `glm-5.3 · 950ms / RateLimitError: 429`). Typing "bash" narrows the pane to exactly the tool-call card — the 950ms error-only card is filtered out. Pass 1 verified the sheet exists; filtering behavior is new evidence |
| **Terminal Pause ⇄ Resume** | Attached terminal (`live — /bin/zsh`): the header Pause chip flips in place to Resume and back. Pass 5 verified Copy; the pause cycle is new |
| **Terminal Search** | The Search chip opens a `Search output` field spanning the pane top. (Empty pane → no matches to walk; the field's presence + focus is the evidence) |
| **Notification toggles persist** | "Task finished / failed" switch flips OFF and back ON, state survives re-dumps (the three switches: Approval requests / Task finished-failed / Connection lost, all default ON) |
| **Auto-reconnect checks toggle** | OFF by default; flips ON and persists; restored to OFF. ("Live connection" window switch also OFF by default — both are opt-in) |
| Question card + compaction lifecycle (re-observed) | The full multi-option elicitation card (Channel radio: Tag the release / Cut a branch + "Or type your own answer") and the complete compaction row set (Compressing context → Context automatically compressed 180K → 42K tokens → Context compression failed + Retry) rendered again while walking |

### P2. NEW defects

None. Every surface touched this pass that hadn't been executed before behaved correctly;
the two re-confirmed defects are the standing P1s below.

### P3. Pass-1..5 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed a FIFTH time with the same deliberate
  repro:** conversation composer + → Photo → identical FATAL
  (`IllegalArgumentException: Can only use lower 16 bits for requestCode` at
  `FragmentActivity.checkForValidRequestCode:714`, 01:20:20) and
  `Process com.zcode.android.app (pid 4739) has died`; clean relaunch restored the
  conversation stack and reconnected (`listSavedWorkflowRuns ok`, update.check 3.14.3).
  Fifth consecutive pass reproducing it.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a FIFTH time, fresh answer this
  session:** inbox "Allow once" dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 01:19:29) — the Permission card AND the Question card both remained
  in the dump 5 s later, and the inbox badge (2) still counts from the stored records
  carried since pass 5 (stale records survive process death, re-observed).
- A1/A3–A7/A9–A11 not re-exercised this pass (app code unchanged; the pass-2..5 evidence
  stands as the latest on-device record for each).

### P4. Rig learnings for the next pass

1. **The rendered-screenshot scale is NON-UNIFORM vs device pixels in this environment**
   (x-scale ≈1.19 and y-scale ≈1.17 observed on the SAME capture, and it drifts between
   captures). Deriving tap coords from a global scale misses. Correct method: take the
   dump, tap ONLY nodes the dump bounds directly, and for AX-merged/[0,0] rows derive x
   from a same-row dump-anchored sibling and y from the adjacent-row pitch — never from
   the displayed image.
2. **The context-sheet "Model calls"/"Share" chip line sits directly under the sheet's
   DRAG HANDLE touch target** — a tap landing a few px high long-presses the handle
   (a "Drag handle" toast renders) and can close the just-opened pane. Approach the chip
   row from its own dump-pitch coordinates, and prefer the Files/Git/Terminal row (fully
   AX-exposed at y≈2041) as the anchor: the second chip line is one ~84px pitch below.
3. **`input text` inserts at cursor position 0 when a field gains focus without a caret
   position** ("@s" typed as "s@") — `keyevent KEYCODE_MOVE_END` first, or type the full
   query in one `input text` after clearing.
4. **The mock's group store MUTATES for `createTaskGroup` + group assignment** (the new
   group renders and the assigned task moves under it), unlike rename/archive where the
   ack doesn't mutate the task store. Group-plane legs are therefore fully observable
   end-to-end; rename/archive assertions must target the wire receipt, not the label.
5. Stale-dump + dialog learning reconfirmed twice more (post-Archive-sheet dump kept
   pre-cancel content; post-Rename dump kept the dialog). Screenshot after every dialog
   transition; fresh dump only after a route change.
6. `input tap` on the confirm sheet while the keyboard is up hits DISPLACED buttons —
   dismiss the keyboard (`keyevent 111`) before tapping dialog confirms (cost one
   no-op Rename round).

## Part Q — macOS desktop pass 6 (targeted enumeration)

Asar digest re-measured identical (`99e280fb90b626cc…`) so the pass-1/2 REA analysis and
B/E/H/K/N conclusions stand; this pass enumerated the ui/src planes the earlier passes did
not cite, from the v3.14.3 source clone.

### Q1. NEW GAP-G30 (P4, boundary decision): bots/integrations management plane

Desktop: a full chat-bot integration subsystem — `BotsDialog.tsx` (data loading, saving and
polling orchestration, explicitly noted in its header) + `BotsDialog/` cards
(`BotSummaryCard`, `ProviderSettingsCard`, `WorkspaceAccessCard`, `shared.tsx`), the
`BotConfig` shape in `packages/shared/src/bots.ts` (provider, enabled, `credentialRef`,
`webhookUrl`/`webhookSecretRef`/`webhookAuthHeaderName`, `feishuAppId`,
`providerUserId`, `displayName`, `allowedWorkspaces[]`, `BotWorkspaceRef`,
`BotServiceStatus`), surfaced from `WebRemoteControlDialog.tsx`. The desktop can register
webhook/Feishu bots that drive ZCode sessions remotely, scoped to workspaces. Phone state:
zero bot surfaces anywhere (no matches in app/core/data/features). This is a genuinely
missing plane (not a documented boundary): on-par minimum = a read-only bots viewer in the
Insights/Endpoints family + the create/edit/credential entry if on-par is ever redefined.
Joins the G21–G29 boundary-decision tickets as its own row because, unlike G21–G29, the
phone has NO related surface to extend.

### Q2. Evidence strengthening for existing gaps (no severity changes)

- **G17/G28 (preview depth)** — the desktop's `presentation/` plane surfaced:
  `pptxRendererPreviewEngine.ts` (a full pptx RENDER engine), `presentationPdfPrintExport`,
  `presentationPreviewControls`, `presentationTextSelection`, `presentationElementModel` —
  the desktop previews (and PRINTS/EXPORTS) presentations, deepening the attachment/
  artifact preview gap the earlier passes documented at the preview-source level.
- **G9 (shared-context handover)** — the desktop composer is a Lexical plugin suite
  (`mentions/MentionPlugin.tsx`, `PromptClipboardPlugin`, `promptSerialization.ts`,
  `mentionMarkdown.ts`, `mentionPanelRouting.ts`): the mention/context-ref family is
  editor-grade on the desktop, while the phone has the data path (port + menu entry +
  unit pins) but no live panel on this rig — one more anchor for whoever finishes the
  phone mention panel.

### Q3. Boundary inventory completion (NOT gaps — B3 families, cited for completeness)

- **resource-manager/** (`ResourceManagerApp`, `resourceUsageView.ts`, storage bridge):
  the desktop's own process telemetry (per-process CPU/memory grouped base / builtin-plugin
  / community-plugin) — desktop-form-factor self-monitoring, not a companion feature.
- **command-center/** (`CommandCenterDialog`, search history) + **quickpick/**: keyboard
  global-action palette — desktop input-form-factor (the phone's app shortcuts +
  navigation cover the intent).
- **git-graph/** (`GitGraphDialog/Pane/Display`, `layoutAlgorithm.ts`): the desktop draws
  the commit GRAPH; the phone's GitScreen cites git-graph parity for CONTENT (7-char-hash
  commit rows, MM/dd HH:mm dates) and the ADR-021 write tier is already broader than the
  read-only original — visualization-only delta, boundary class.
- **SSH/remote-connection wizard** (`SSHDialog.tsx`, `RemoteConnection*`): SSH host
  authoring is a desktop-form plane; the phone's endpoint model (relay QR + direct
  endpoints, A10's form) is the companion shape.
- **shortcuts/** plane: keyboard-shortcut management — desktop-form.
- **DeveloperToolsPane / DesktopTopOverlay / window caption controls**: desktop chrome.

### Q4. Cross-checks that found NO new gap

- **Elicitation plane parity** — the desktop's 1,161-line `ElicitationDialog.tsx` speaks
  the SAME resolveInteraction plane the phone has shipped since pass 1 (the coordinator's
  `submitElicitationAnswers` even cites "desktop ElicitationDialog.buildElicitationResponseContent"
  as its wire-shape reference, and `submitElicitationDecline` mirrors the desktop's
  decline/cancel actions). The pass-1 multi-question card + decline variant is the phone
  side of this exact dialog — parity, not a gap.
- **Bots ABSENCE on the phone is total** (Q1) — checked bots/scratch/integration greps
  across app/core/data/features to be sure it isn't an unexercised-but-present plane.
- **Task verbs** — the phone's verb set now empirically covers rename/archive/resume/
  pin/mark-read/move-to-group/group-create/delete-confirm, matching the desktop's task
  context menu surface class (`useTaskListItemContextActions.ts`).

## Part R — remediation order update (supersedes only where pass 6 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 6, identical FATAL + process death; fifth
   consecutive pass with the same repro).
2. GAP-A1/A2, A6/A7, A9, A10, A11 unchanged (Part O ordering stands; A2 now has a fifth
   same-session receipt).
3. **G30 joins the G21–G29 boundary-decision tickets** (P4; note it is the only one with
   NO existing phone surface to extend — a read-only bots viewer is the minimum-on-par
   seed, mirroring the plugins/MCP viewer pattern).
4. Mock remedies for the NEXT code-touching pass (cheap, unblocks two rig-blocked legs):
   `MOCK_WORKSPACE_FILES` (serve `searchWorkspaceFiles` so the live mention panel can be
   exercised) — joining pass 5's `MOCK_AUTOMATIONS`/`MOCK_OFFPEAK` request.
5. Pass-6 P1 surfaces (group create/assign, model-calls search filter, terminal
   Pause/Search, notification/connection toggles, rename/archive/resume wires) need NO
   remediation — recorded as regression anchors: a pin per surface in the G-fix batches
   would lock them in.

Verification record (pass 6): emulator+relay rig ran ~45 min with ZERO emulator deaths;
the APP died exactly once — the deliberate GAP-A8 Photo-arm repro (fifth pass running the
same repro) — followed by a clean relaunch that reconnected on its own. Census: 1 FATAL
total, 0 ANRs, 0 app-level ERROR log lines. Unit suites were NOT re-run: `git diff
fd7046a..322b9a3` contains only the report file and 2 mock-module lines, so the app code
tree is byte-identical to the 1487-test green baseline (Part A) — the same reasoning
accepted in passes 2–5.

---

# Seventh pass — 2026-10-30 (APPENDED — pass 7; not part of the original audit)

Scope: same mandate, seventh run ("live emulation + verify everything + REA the macOS app +
document every gap; append only what's new"). All commits after `fd7046a` remain docs + 2
mock-module lines, so every pass-1..6 defect and G-gap is expected to still exist; pass 7
executes the surfaces no earlier pass ran: the task-CREATE sheet's model picker (search +
selection + propagation), the FULL create loop with a fresh task, the in-conversation
Switch-model sheet, the Reasoning-effort picker (first execution of the effort plane in any
pass), the message long-press actions sheet + Copy, the git-pane Snapshot CREATE verb, the
approvals launcher-shortcut intent, the glance-widget receiver inventory, the connection-lost
notification END-TO-END (relay kill → shade → recovery ladder), and the skills-row expand
affordance. FOUR new desktop gaps (G31–G34) and three boundary-plane completions; the two
standing P1s re-confirmed with fresh receipts (A8 6th repro, A2 6th receipt). No code was
changed by this pass — verification + documentation round only.

Tree under audit: `main` @ `b00a8db` (app code identical to `fd7046a` — `git diff
fd7046a..b00a8db` is the report file + 2 mock-module lines; versionName 1.3.0 via `dumpsys
package`). Fresh `adb install -r` of the current debug build over the pass-4/5 data
(lastUpdateTime 2026-10-30 01:38:50); the stored pairing auto-reconnected on first launch.
One first-launch observation: `update.check :: desktop=null state=UpToDatenull` — the check
races the bootstrap; after the bootstrap completes it reports desktop 3.14.3 state=UpToDate
(verified again post-A8-relaunch). Desktop target unchanged: `/Applications/ZCode.app` asar
sha256 `99e280fb90b626cc…` (re-measured AND re-analyzed this pass, byte-identical).

Evidence: 27 screenshots `/tmp/walk7/*.png` + 36 uiautomator dumps `/tmp/walk7/d*.xml`; mock
relay wire log `/tmp/mock-relay-pass7.log` (`hub.createTask … outcome=Accepted`,
`approvals.answer … option=allowOnce` receipts); app logcat (the A8 FATAL +
`Process com.zcode.android.app (pid 4261) has died: prcp TOP`, the connection-loss ladder
RECONNECTING→RECOVERING→CONNECTED, `Connection lost` posting in NotificationService); REA
Evidence `ev_43c0a5668a598e3547afe154a4342bca26867d0d8f93d9a0ce66426c4d89f2b9` (fresh
shipped-asar analysis, 23,186 relevant files / 22,125 parsed JS / 1,359 modules, same
documented post-signing `pty.node` record-and-continue note); desktop source reference clone
v3.14.3 `29628c9` at `~/Desktop/DEVPROJECTS/zcode/repo`; shipped-bundle binary-grep receipts
in Part T.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100` relaunched via the gradle task with the same all-flags
arming (`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1 MOCK_WORKFLOWS=1
MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1 MOCK_ATTACHMENT=1
MOCK_SCENARIO=HAPPY`). The relay was deliberately killed ONCE mid-pass for the
connection-loss leg (`pkill -f MockRelayMainKt` — the emulator-safe method) and restarted
with identical arming. Zero emulator deaths; one deliberate app death (A8).

## Part S — pass-7 live emulation

### S1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **New-task model picker: search + selection + propagation** | Create sheet chip `Model: workspace default` → full grouped picker (`Default / OPENAI-COMPATIBLE / Z.AI`: glm-4.6, glm-5.1, glm-5.1-air, glm-5.1-flash + `Search models`); typing "air" narrows the list to exactly glm-5.1-air; selecting it returns to the sheet with the chip reading `zai/glm-5.1-air`. The pass-4 model-picker evidence covered the in-conversation picker; the create-sheet picker and its search filter are new |
| **Task CREATE full loop (fresh task)** | Description "Rig-p7" + model zai/glm-5.1-air → Create → `hub.createTask :: workspace=/synthetic/ws-one outcome=Accepted` (01:45:38) → the sheet closes and the task renders under `WORKSPACE Synthetic Workspace` (count 3) with ▶ Running and the row auto-selected. The F-CREATE-STUCK racing-snapshot fix re-verified with a genuinely NEW task on a fresh install |
| **In-conversation Switch-model sheet** | Session-bar model chip (`model GLM-5.3-Flash, thought max, tap to change`) opens the same grouped `Switch model` sheet — the conversation-side picker rides the identical component as the create sheet |
| **Reasoning-effort picker (FIRST execution of the effort plane)** | Session-bar config chip expands the interactive chip row (`Effort · max`, model chip, `account:zai-individual-coding-plan/GLM-5.3-Flash · max`); tapping `Effort · max` opens a `Reasoning effort` sheet (high / max / medium / xhigh + Close); selecting `high` flips the session bar to `yolo · GLM-5.3-Flash · high` and the chip to `Effort · high` in place. This is the UI over the VM's A3.4 `setThoughtLevel` wire path (session-scoped — no dedicated v4call; it rides the next command) — the desktop's `ThoughtLevelCycleControl` counterpart, verified live for the first time |
| **Message long-press actions sheet** | Long-press on the user message → `Message actions / Copy` bottom sheet; Copy closes the sheet. (User messages carry Copy; the agent-turn actions — Fork/Retry/👍👎/Undo files — are the pass-1 row-actions plane, unchanged) |
| **Git-pane Snapshot CREATE** | Git pane → Checkpoints section → `Snapshot` action → snackbar `Snapshot ckpt-1 created` + the `ckpt-1` checkpoint row renders + footnote `Snapshots created elsewhere aren't listed here`. Pass 1 listed the Checkpoints section; the create VERB is first executed here (and works even while the git log honestly reports no-git-channel — the checkpoint plane rides its own channel) |
| **Approvals launcher shortcut** | Shortcut inventory verified via `dumpsys shortcut`: `Approvals` (shortcut_approvals) + `Connection` (shortcut_connection), intent extra `zcode.shortcut=approvals`; firing the exact shortcut intent navigates to the Approvals inbox (`Requests waiting across your tasks` with both pending cards + `Open task to answer`) |
| **Glance-widget receiver** | `PendingApprovalsWidgetReceiver` registered in `dumpsys appwidget` (min 28161×10241, resizable, not bound on the rig launcher — placement is a host-side gesture the rig can't perform; registration + provider wiring is the on-rig evidence) |
| **Connection-lost notification END-TO-END (first live execution)** | App backgrounded → relay killed (`pkill -f MockRelayMainKt`) → ladder enters RECONNECTING (02:04:03) → `ZCode Companion / Connection lost / now` posts to the shade (verified expanded, screenshot 25) → relay restarted → app foregrounded → RECONNECTING → RECOVERING → CONNECTED in ~6 s (02:09:04→02:09:10) and the Tasks screen shows `Connected`. The notification itself has NO content intent for connection-scoped posts (deep links are task-scoped only, `AndroidNotificationSink.kt:56`) — tapping is a no-op by design; recorded as a small UX observation, not a defect |
| **Skills row tap = accordion expand** | Tapping a skill row expands it in place — the descriptions render under each entry (`Generate and apply safe SQL migrations from schema diffs.` etc.). Pass 1 verified the viewer list and pass 5 the search filter; the expand affordance is new evidence |
| **Voice input availability gate (rig-gated)** | The composer's + menu carries NO `Dictate` item on this image: `PlatformVoiceInput.available` (`SpeechRecognizer.isRecognitionAvailable`) is false here even though the recognizer resolver exists (emulator quirk) — the gate working as designed (`VoiceInput.kt`: "the affordance never renders where recognition is missing"). The path is unit-pinned (`VoiceInputTest.kt`); on real hardware the item renders. Rig-gated class, NOT an app break |
| Automations honest-unavailable state (re-observed) | The Tasks automations section renders `This feature isn't available on the connected desktop.` under `+ New automation` — the unscripted `listAutomations` fault surfaces as a clear notice (pass-5's rig-blocked class, visually re-confirmed) |

### S2. NEW defects

None. Every newly executed surface behaved; the two standing P1s were re-confirmed with
fresh receipts (S3).

### S3. Pass-1..6 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed a SIXTH time, identical repro:** conversation
  composer + → Photo → FATAL `IllegalArgumentException: Can only use lower 16 bits for
  requestCode` (02:02:26) and `Process com.zcode.android.app (pid 4261) has died: prcp TOP`.
  Relaunch recovered cleanly: transient `hub.phase → UNPAIRED` line (the pre-connect phase,
  NOT credential loss) → AUTHENTICATING → CONNECTED with the conversation stack restored and
  `update.check :: desktop=3.14.3 state=UpToDate`.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a SIXTH time, fresh answer this
  session:** inbox `Allow once` dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 02:00:44) — the Permission card AND the Question card both remained in
  the dump 3 s later, and the inbox badge still counts the stale stored records.
- A1/A3–A7/A9–A11 not re-exercised this pass (app code unchanged; the pass-2..6 evidence
  stands as the latest on-device record for each).

### S4. Rig learnings for the next pass

1. **tap-by-text can resolve a node reporting `[0,0]` bounds** (dialog buttons observed
   during a keyboard-dismissal window) — the helper returns `0 0` instead of NOTFOUND;
   treat any `0 0` as NOTFOUND and fall back to a screenshot-derived tap.
2. **The connection-loss leg is cheap and safe with the emulator UP**: `pkill -f
   MockRelayMainKt` (never lsof-by-port) → notification posts ≈2.5 min later; restart the
   relay with the same arming and foreground the app — RECOVERING→CONNECTED in ~6 s. Good
   standing leg for future passes.
3. **Skills rows expand in place** (accordion) — not a route change; dumps before/after a
   tap on the SAME screen are the evidence pattern (screenshot after every dialog
   transition, fresh dump only after route change — reconfirmed).
4. Back-press on the Tasks ROOT exits the app (reconfirmed again this pass) — navigate with
   taps, not back-presses, when the root screen is showing.
5. First-launch `update.check desktop=null` on a fresh `install -r` is a bootstrap race,
   self-heals to `3.14.3` — don't chase it as a regression.

## Part T — macOS desktop pass 7 (targeted enumeration)

Asar digest re-measured identical AND freshly re-analyzed this pass (Evidence
`ev_43c0a566…`, sha `99e280fb90b626cc…`), so the pass-1..6 REA and B/E/H/K/N/Q conclusions
all stand; this pass enumerated the ui/src planes no earlier pass cited, each with a
shipped-bundle binary-grep receipt in the asar.

### T1. NEW GAP-G31 (P4, boundary decision): whiteboard plane — sketch → send-to-chat

Desktop: `WhiteboardPane.tsx` (363 lines) + `lib/whiteboard.js` + `whiteboardStore` — a full
drawing surface (pencil / eraser / image-insert / palette with 6 swatches, strokes with
undo/redo, per-workspace key `buildWhiteboardWorkspaceKey`) whose `dispatchWhiteboardAddToChat`
sends the finished sketch INTO the conversation as an image attachment. Shipped receipt: 59
`whiteboard` hits in the asar. Phone state: zero whiteboard surfaces anywhere (grep across
app/core/data/features). Unlike the pure-visualization planes, the whiteboard produces
CONVERSATION INPUT — the natural on-par analog is a sketch→send entry in the composer's
+ menu (the produced images already reach the phone today as inbound attachments, i.e. the
G28 preview class). Joins the G21–G30 boundary-decision tickets; mobile sketch→send is the
minimum-on-par seed.

### T2. NEW GAP-G32 (P4): git branch switch / create / commit-assist — phone models the verb, never sends it

Desktop: `GitBranchSwitcher.tsx` (379 lines) + `git-branch-switcher/` (`GitBranchDialogs`
create + switch-assist dialogs, `display.ts` branch search/labels, `switchAssist.ts` with a
blocked→commit assist state machine: commit preview files, commit totals, affected-file
selection before switching). Shipped receipts: `git-branch-switcher` ×3, `GitBranchSwitcher`
×1 in the asar. Phone state: `GitPort.switchBranch(targetBranchName)` is MODELED
(`features/git/.../GitPort.kt:77`, null-default never-wire class) but no screen dispatches it
— this row joins the "Modeled, never sent" family (G3 workflow-resume, G4 queue-delete, G6
snooze, G8 selection-side-session). ADR-021 note: branch switch/create is a NEW write-tier
candidate (the current bounded set is rewind/undo/commit-class); switch-assist
(commit-before-switch with preview) is the UX the port would need to honor.

### T3. NEW GAP-G33 (P4): model-trajectory drill-in — desktop expands a call into a payload pane

Desktop: the 16-file `ModelTrajectory*` family (`ModelTrajectoryPane/PaneDetails/Parts`,
`ModelTrajectoryTimeline`, `SearchBar/Search/SearchHighlight`, `ModelTrajectoryToolPayload`,
`ExpansionMenu`, `RoleStyles`, `Format`) — any model call in the timeline expands into a
searchable trajectory pane with per-call TIMELINE and tool PAYLOAD detail. Shipped receipt:
`ModelTrajectory` ×12. Phone state: `TrajectorySheet.kt` (155 lines) renders call cards
(duration · head text · tool NAMES as one joined string · error) with a search field
filtering timeline+records — no per-call expansion, no payload detail. Pass 6 verified the
sheet's search filter; the depth delta (payload drill-in) is the gap.

### T4. NEW GAP-G34 (P4, small): update release-notes display

Desktop: `UpdateStatusDialog.tsx` + `UpdateStatusButton.tsx` + `UpdateReleaseNotesTooltip.tsx`
(renders `releaseNotesMarkdown` per locale). Shipped receipt: `Release notes` ×2 in the asar.
Phone state: the in-app update sheet shows version/compat from `ReleaseMeta` — which carries
`versionName / versionCode / maxTestedDesktop / sha256 / apkName` only
(`ReleaseInfo.kt:130`), no notes field. On-par minimum: a notes line (markdown) in the update
sheet, sourced from the release meta block the ReleaseParser already fails-closed on.

### T5. Boundary inventory completion (NOT gaps — B3 families, cited for completeness)

- **TreemappingPane** (696 lines + `treemappingActivity` model): file-activity TREEMAP of the
  conversation (rectangle layout + detail region, `useTreemappingConversationMessage`) —
  git-graph-class visualization-only delta; shipped ×5. The phone's Files/Git surfaces cite
  content parity, not activity visualization.
- **browser-use/ side pane** (`UnifiedBrowserView`, `HumanBrowserView`,
  `ResponsiveBrowserViewport`, `BrowserViewportToolbar`, zoom/resize): the CUA plane's LIVE
  browser viewport — the desktop WATCHES the agent drive a browser. Strengthens G18: the
  phone could at least surface browser-use screenshot frames inside its tool rows; today it
  renders generic tool groups.
- **PreviewPane.tsx** (1,806 lines): the workspace side preview surface — deepens the
  G17/G28 preview-depth family (the desktop previews artifacts/files in-place; phone
  thumbnails are dead-ends per G28).
- **ExternalAgentImportDialog**: cross-agent import scanning — imports skills/commands/MCP
  config FROM external agents (`claudeCode` ×21, `codexCli` shipped receipts). Rides the
  existing skills/commands/MCP authoring boundary (B3, INV-9 read-only viewers on the
  phone); cited so the boundary's full desktop extent is on record.
- **DeveloperToolsPane / DesktopTopOverlay / window controls / WelcomeScreen /
  SortableWorkspaceSidebar / DirectoryBrowser**: desktop chrome and form-factor planes,
  unchanged.

### T6. Parity cross-checks that found NO new gap

- **Scratch workspaces** — the desktop's `ChatEmptyScratchWorkspaceDialog` (empty-chat →
  scratch workspace) meets the phone's existing scratch-TASK creation (C7:
  `onCreateScratchTask` resolves the scratch name→path then creates against it,
  `TaskListScreen.kt:378`); both author work against scratch spaces. Parity.
- **Thought-level control** — the desktop's `ThoughtLevelCycleControl` (chat-input toolbar)
  meets the phone's effort picker (`setThoughtLevel` A3.4, live-verified in S1). Parity.
- **Permission surfaces** — desktop `PermissionDialog.tsx` ↔ the phone's approval cards +
  shade-level Approve-once/Deny actions (`AndroidNotificationSink` C3). Parity.

## Part U — remediation order update (supersedes only where pass 7 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 7, identical FATAL + process death; sixth
   consecutive pass with the same repro).
2. GAP-A1/A2, A6/A7, A9, A10, A11 unchanged (Part R ordering stands; A2 now has a sixth
   same-session receipt).
3. **G31–G34 join the boundary-decision tickets** (all P4): G31 whiteboard (with the
   mobile sketch→send note), G32 branch switch (Modeled-never-sent family + ADR-021
   write-tier note), G33 trajectory drill-in, G34 release notes (smallest; a meta-field +
   one sheet line).
4. **New small for the polish ledger:** connection-scoped notifications have no tap target
   (by design today — deep links are task-scoped, `AndroidNotificationSink.kt:56`);
   deep-linking the Connection-lost post to the connection screen is cheap on-par polish.
   Also carry the S1 first-launch `update.check desktop=null` bootstrap race as a
   cosmetic-if-ever note (self-heals; observed once on a fresh install).
5. Mock remedies for the NEXT code-touching pass: unchanged from Part R (`MOCK_WORKSPACE_FILES`
   + `MOCK_AUTOMATIONS`/`MOCK_OFFPEAK`) — pass 7 needed no new arms.
6. Pass-7 P1 surfaces (create-sheet model picker + full create loop, effort picker,
   snapshot create, approvals shortcut, connection-lost notification cycle, skills expand,
   message-actions Copy) need NO remediation — recorded as regression anchors: a pin per
   surface in the G-fix batches would lock them in.

Verification record (pass 7): emulator+relay rig ran ~40 min with ZERO emulator deaths and
ONE deliberate relay kill/restart (connection-loss leg); the APP died exactly once — the
deliberate GAP-A8 Photo-arm repro (sixth pass running the same repro) — followed by a clean
relaunch that reconnected on its own. Census: 1 FATAL total, 0 ANRs. Unit suites were NOT
re-run: `git diff fd7046a..b00a8db` contains only the report file and 2 mock-module lines,
so the app code tree is byte-identical to the 1487-test green baseline (Part A) — the same
reasoning accepted in passes 2–6.

# Eighth pass — 2026-10-30 (APPENDED — pass 8; not part of the original audit)

Scope: same mandate, eighth run ("live emulation + verify everything + REA the macOS app +
document every gap; append only what's new"). App code unchanged since `fd7046a`, so every
pass-1..7 defect and G-gap is expected to still exist; pass 8 executes the surfaces no
earlier pass ran: BOTH never-invoked launcher shortcuts (Connection + New task), the task
Pin/Unpin verbs, the task DELETE verb (first execution in any pass, on a fresh rig-created
task), the approvals "Open task to answer" navigation, the needs-approval tool-row expand,
the SHADE notification deep-link end-to-end (tap a task-scoped notification → task
conversation), the checkpoint CREATE→RESTORE loop (three snapshots + the Restore dialog +
wire), the terminal-consent DENY path, and the Diagnostics EXPORT verb. One new phone-side
ledger item (checkpoint list is mount-local), the two standing P1s re-confirmed with fresh
receipts (A8 7th repro at `WriteComponents.kt:832`, A2 7th receipt). On the desktop side the
drift enumeration against newly fetched upstream tags surfaced a SHIPPED plane every prior
pass missed (G35, the web-remote-control browser-companion surface) plus a protocol WATCH
(Relay V2 upstream) — see Part W. No code was changed by this pass — verification +
documentation round only.

Tree under audit: `main` @ `f582edf` (app code identical to `fd7046a` — `git diff
fd7046a..f582edf` is the report file + 2 mock-module lines; versionName 1.3.0 via `dumpsys
package`, lastUpdateTime 2026-10-30 11:05:44). Fresh `adb install -r` over the pass-7 data;
the stored pairing auto-reconnected on first launch (AUTHENTICATING → CONNECTED ~2 s) with
the recorded first-launch `update.check :: desktop=null state=UpToDatenull` bootstrap race,
self-healed to `desktop=3.14.3` (same as pass 7). Desktop target unchanged:
`/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…` (re-measured byte-identical AND
freshly re-analyzed) — but see W3: the bundle's Info.plist reads **3.14.5**, and the
"3.14.3" strings in app logs are the MOCK-served version, not the real desktop.

Evidence: 16 screenshots `/tmp/walk8/*.png` + 52 uiautomator dumps `/tmp/walk8/d*.xml`;
mock relay wire log `/tmp/mock-relay-pass8.log` (all-flags arming identical to pass 7:
`MOCK_ROWS=40 MOCK_GOAL=1 MOCK_APPROVAL=1 MOCK_ROW_ACTIONS=1 MOCK_WORKFLOWS=1
MOCK_TASKMGMT=1 MOCK_QUESTION=1 MOCK_HOOKS=1 MOCK_COMPACT=1 MOCK_ATTACHMENT=1
MOCK_SCENARIO=HAPPY`); app logcat receipts quoted inline; REA Evidence
`ev_efbace083192d31fcaae4b099b771acbd61331f343a667a161ebab8f2b32f0ac` (fresh shipped-asar
analysis via the rea CLI — the MCP path times out at this harness's 30 s wall — 23,186
relevant files / 22,125 parsed JS / 1,359 modules, byte-identical census to pass 7's
`ev_43c0a566…`, same documented post-signing `pty.node`/`spawn-helper` record-and-continue
note); desktop source reference clone `~/Desktop/DEVPROJECTS/zcode/repo` at v3.14.3
`29628c9`, now also carrying fetched tags **v3.15.1** and **v3.17.1**.

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay on `10.0.2.2:18100`. Zero emulator deaths; one deliberate app death (A8);
one app exit via back-press overshoot at the Tasks root (known rig trap, recovered by
relaunch).

## Part V — pass-8 live emulation

### V1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **Connection app shortcut INVOKED** | The exact shortcut intent (`act=com.zcode.android.app.action.SHORTCUT --es zcode.shortcut connection`, explicit `-n …MainActivity`) opens the **Connection screen**: `MockBook · desktop 3.14.3 · Connected · Last refreshed 1 min ago` plus the full state-ladder legend (Not paired / Validating a pairing link / Proving identity to the relay / Connecting / Opening the workspace bridge / Synchronizing / Re-syncing after reconnect / Connected "Live. Tasks and conversations stream." / Reconnecting / Could not reconnect… / Disconnected / Connection failed / Pairing expired or invalid / Pairing removed from this device / Check for updates / Closed). Pass 7 verified shortcut publication only; this route has never been walked |
| **New-task app shortcut INVOKED** | `zcode.shortcut=new_task` opens the **New task create sheet directly** (workspace prefilled `Synthetic One`, description field focused, Model line) — the third published shortcut is now launch-verified (approvals was pass 7) |
| **Task Pin verb** | Task-3 ⋯ → Pin → `zcode-task.setTaskPinned ok` (11:10:49) + `Task pinned` toast + immediate `listGroupedTaskViewStructure` refresh. The row does NOT move under Pinned — the mock acks without mutating its pinned store (the pass-2 rename/archive rig-fidelity class); the dispatch path is what's verified |
| **Task Unpin verb** | Pinned task-1 ⋯ → Unpin → `zcode-task.setTaskPinned ok` (11:11:27) + `Task unpinned` toast. First execution of the unpin arm |
| **Task Delete verb (FIRST execution in any pass)** | Created a fresh task `Rig-p8` (`hub.createTask :: workspace=/synthetic/ws-one outcome=Accepted`, 11:13:57 — re-verifying the pass-7 create anchor en route) → its ⋯ menu → Delete → confirm sheet `Delete task?` → `zcode-task.deleteTask ok` (11:15:17) + `Task deleted` toast. The row persists because the mock acks without mutating (rig fidelity) |
| **Approvals "Open task to answer"** | The inbox card affordance navigates INTO the task conversation (session bar `yolo · GLM-5.3-Flash · max`, the pending `Tool Bash — Needs approval` row, compaction lifecycle rows) — first navigation execution of that affordance (passes 1–7 rendered it only) |
| **Needs-approval tool-row expand** | `Tool Bash, Needs approval. Tap to expand.` flips in place to `Tap to collapse` revealing the INPUT block (`echo synthetic`) + `Needs approval` chip. No inline Approve/Deny on the row — the cards/inbox remain the answer surfaces, consistent with the approval model |
| **Shade notification deep-link END-TO-END (FIRST live execution)** | App backgrounded → the shade holds BOTH task-scoped interaction notifications (`ZCode needs your approval` + `ZCode has a question for you`, channel=permissions, importance HIGH, each with a real contentIntent) → tapping the approval notification opens the app DIRECTLY into the Synthetic task conversation with the pending Bash approval on screen. Pass 7 proved the connection-lost post has NO intent; this pass proves the task-scoped posts DO — the deep-link pair is now fully characterized |
| **Checkpoint create + RESTORE loop (FIRST execution of restore)** | `Snapshot` → `git-checkpoint.createCheckpoint ok` (ckpt-2, 11:37:20; ckpt-1 re-created at 11:20:36 earlier) → with TWO checkpoints in the list the head row renders a **Restore** button → confirm sheet `Restore this checkpoint?` → `git-checkpoint.restoreBetweenCheckpoints ok` (11:39:13). The restore verb is wired end-to-end (pass 1 only listed the Checkpoints section) |
| **Terminal consent DENY path** | Settings → `Terminal (view-only)` switch OFF (checked=false verified in the dump) → conversation Terminal pane now renders the fail-closed `Enable terminal (view-only)` gate instead of attach. The gate re-arms when consent is revoked (passes 1–6 only walked the enable direction); consent restored ON after the leg |
| **Diagnostics Export verb** | Settings → Diagnostics → `Export diagnostics` → the SYSTEM share sheet opens with `zcode-diagnostics-1793359347557.txt` as the attachment (Bluetooth/Gmail/Messages/Drive targets). Pass 1 listed the row; the export verb is first executed here |
| Plugins On/Off are display-only (re-confirmed deliberately) | Tapping a plugin's On/Off indicator produces no state change and no wire call (`listPlugins` read is the only channel traffic) — `Installed plugins and their state (read-only)` is honest, INV-9 class |

### V2. NEW defect / ledger item

- **Checkpoint list is mount-local (small, P4).** `GitScreen.kt:753` holds the checkpoint
  list in `remember { mutableStateOf(emptyList()) }`, appended only on create
  (`listOf(meta) + checkpoints`, `GitScreen.kt:780`); NO list call fires on entry (logcat
  receipt — only `createCheckpoint` traffic). Live proof: ckpt-1 created at 11:20:36 was no
  longer listed after the A8 restart (11:29) even though the host still holds it — the card
  fell back to `No snapshots in this session`. Consequence: the Restore affordance (which
  needs an EARLIER checkpoint in the list, `GitScreen.kt:817-823`) is unreachable after any
  app restart, so the phone can only restore inside one uninterrupted screen mount while
  the desktop's checkpoint plane persists. Remedy: hydrate the list from the host on entry
  (the `restoreBetweenCheckpoints` channel already exists and works — V1).

### V3. Pass-1..7 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed a SEVENTH time, identical repro:**
  conversation + → Photo → FATAL `IllegalArgumentException: Can only use lower 16 bits for
  requestCode` at `WriteComponents.kt:832` (11:28:47) and `Process com.zcode.android.app
  (pid 4420) has died: prcp TOP`. Relaunch recovered cleanly: AUTHENTICATING → CONNECTED in
  ~2 s with the conversation stack restored.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a SEVENTH time, fresh answer this
  session:** inbox `Allow once` dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 11:16:38) — the Permission card AND the Question card both remained in
  the dump 3 s later (byte-identical dump size).
- A1/A3–A7/A9–A11 not re-exercised this pass (app code unchanged; the pass-2..7 evidence
  stands as the latest on-device record for each).

### V4. Rig learnings for the next pass

1. **Dynamic app shortcuts need the explicit component on `am start`** — action-only start
   fails with `unable to resolve Intent` (the shortcut intents are launcher-delivered, not
   manifest-filtered); add `-n com.zcode.android.app/.MainActivity` and the exact
   `--es zcode.shortcut <id>` fires the launcher-identical path.
2. **The two task-scoped notifications post at CONNECT time** (pendingInteractions
   reconciliation on bootstrap) and STAY in the shade while the app is backgrounded — the
   shade deep-link leg is cheap and repeatable with no new mock arm. The connection-lost
   kill-leg from pass 7 remains the other standing notification leg.
3. The create sheet's Cancel/Create STILL never materialize AX bounds ([0,0] in every dump
   this pass, same as pass 7) — screenshot-derived taps remain the reliable path there.
4. **Back-stack shape:** Approvals → `Open task to answer` pushes the conversation ON TOP
   of the approvals screen — back from that conversation lands in the INBOX, not Tasks;
   from there one more back reaches Tasks. Plan back-presses to the route stack, not to
   assumed roots.
5. Checkpoint-restore leg needs TWO creates within ONE screen mount (the list is
   mount-local, V2); with the app freshly (re)mounted, create ×2 then the head row carries
   Restore — a 3-tap leg once armed.

## Part W — macOS desktop pass 8 (targeted enumeration + drift)

Asar digest re-measured byte-identical (`99e280fb90b626cc…`) AND freshly re-analyzed
(Evidence `ev_efbace08…`; 23,186 relevant files / 22,125 parsed JS / 1,359 modules — the
identical census to pass 7), so the pass-1..7 REA/B/E/H/K/N/Q/T conclusions all stand. NEW
this pass: the upstream reference tags v3.15.1 and v3.17.1 were fetched into the source
clone, and the drift enumeration against them surfaced a SHIPPED plane every prior pass
missed — because the v3.14.3 source map does not contain it.

### W1. NEW GAP-G35 (P3, boundary decision): the shipped web-remote-control plane — the desktop hosts its own browser-based mobile companion

Shipped receipts: **267 `WebRemoteControl` + 108 `web-remote-control` hits** in the audited
asar, with a full early-surface identifier inventory — verbs `StartWebRemoteControl /
StopWebRemoteControl / ResetWebRemoteControlPairing / GetWebRemoteControlStatus /
SyncWebRemoteControlTasks / SyncWebRemoteControlWorkspaces /
WebRemoteControlReconnectWorkspace`, transports `WebRemoteControlDeviceTransport +
RpcTransportAssembler + RelayPayloadSerializer + RpcTransportEncodingError`, auth
`createNodeWebRemoteControlRelayAuthProvider`, pairing `buildWebRemoteControlExternalQrUrl`
+ pairing-result/start-result telemetry, `createWebRemoteControlFeatureGate`, and the UI
`WebRemoteControlMobileShell + WebRemoteControlMobileTaskHome + WebRemoteControlDeviceHeader
+ WebRemoteControlTaskViewReporter + WebRemoteControlDialog`. The v3.14.3 reference has ZERO
web-remote-control files — the plane landed on main between that tag and the shipped 3.14.5
content, which is why passes 1–7 never enumerated it. Meaning: the macOS app SHIPS a
hosted browser UI that pairs via QR and drives tasks / chat / terminal / workspace sync
from another device's BROWSER — the companion app's core mission now has an in-desktop
alternative surface (runtime enablement is feature-gated). By v3.17.1 the plane grew a
formal contract (`web-remote-control/CONTRACT.md` + `docs/web-remote-control/
relay-v2-pairing.md`): mobile pages home/chat held by a stable shell, view-claims derived
from Page Visibility, workspace switcher, theme menu, OAuth completion forwarding. Boundary
decision for the roadmap: the phone remains the NATIVE client (push notifications with
shade actions, glance widget, offline cache, launcher shortcuts are phone-only — none of
that exists in a browser tab), but "on par" must now answer the browser-shell question:
either document the plane under B3 or plan interop. Leaving it unowned leaves a whole
product plane unrecorded.

- **WATCH (P4): Relay V2.** The v3.17.1 contract defines the successor pairing/transport
  protocol — JWT bearer auth, device/terminal roles, device-registration REST
  (`/api/v1/remote/device*`), 60-second single-use WS tokens, strict V1/V2 field
  separation. The formal V2 contract is NOT in the shipped asar (0 hits for `remote/v2`,
  `relay-v2`, `RelayV2`, `relay_v2`; the 31 `device_mid` hits belong to the current pairing
  plane's machine-id vocabulary). The phone speaks the current relay pairing (sid+hash
  links, RELAY_ZAI) — any future desktop update that flips the relay to V2 would strand the
  phone's pairing path. Carry this WATCH into any pairing/transport work.

### W2. Upstream drift enumeration (NOT shipped — reference for future passes)

`git diff v3.14.3..v3.17.1 -- packages/ui/src` = 1,462 files changed (+124,559 / −38,269),
584 NEW files. New families with phone-relevant semantics (all verified ABSENT from the
shipped asar by binary-grep — TodoPanel / TaskSearchDialog / MessageChangeSummaryPanel /
image-preview-editor / gen-ui / ConversationShareLinkRow = 0 hits): **TodoPanel** (a
dedicated todos panel — the phone's context-sheet todos are read-only, the G26 family),
**TaskSearchDialog** (task search as a dialog — the phone's search overlay is GAP-A6/A7
broken), **ToolCallBlocks** renderers + `ToolPresentation` + `presentationCapabilities`
(richer per-tool rendering than the phone's generic tool rows — the G18 family),
**image-preview-editor** (10 files), **gen-ui** (15 files + CONTRACT — a generative-UI
plane), **conversation-share-interaction** (10), **workspace-config-catalog** (8),
**conversation-workspace-target** (8), **file-menu** (14), **side-pane-full-view** (12),
**settings-scope** (10), **data-sync** (19), **highspeed** (17 + share-card assets),
**project-sidebar** (74) and **plugin-ui** (71, desktop chrome). Recorded as DRIFT WATCH,
not gaps: the shipped macOS app the phone must match is the 3.14.5-content asar, and every
future desktop update widens this list — re-run the shipped-asar greps after any desktop
update before treating a family as a live gap.

### W3. Record correction (version provenance)

- The audited `/Applications/ZCode.app` Info.plist reads **CFBundleShortVersionString
  3.14.5**; its asar is byte-identical across passes 1–8 (sha `99e280fb…`, mtime Sep 30) —
  the earlier passes' "3.14.3 desktop" statements came from the MOCK relay's served
  bootstrap version, not from the real desktop bundle. The v3.14.3 source-clone tag is the
  nearest TAG but predates the shipped 3.14.5 content — W1 is the concrete case where that
  mismatch hid a shipped plane. Going forward: treat asar binary-greps as the shipped
  truth and the clone as the nearest map.

### W4. Parity cross-checks that found NO new gap

- **Notification deep-links** — the desktop's notification click focuses the task ↔ the
  phone's shade tap deep-links into the task conversation (V1) — parity, now live-verified
  on the phone side.
- **Approvals navigation** — the desktop's approval click-through into the task ↔ the
  phone's `Open task to answer` (V1) — parity.
- **Terminal consent** — the phone's view-only consent gate re-arms on revoke (V1 deny
  path) ↔ the desktop's terminal plane is opt-in — parity on the consent model.

## Part X — remediation order update (supersedes only where pass 8 applies)

1. **GAP-A8** stays FIRST (re-confirmed pass 8, identical FATAL at `WriteComponents.kt:832`
   + process death; seventh consecutive pass with the same repro). GAP-A2 second (seventh
   same-session receipt). All other A-ordering stands (Part U).
2. **New small for the polish ledger:** checkpoint list mount-local (V2) — hydrate from the
   host on entry; the restore affordance then survives restarts (the wire channel
   `restoreBetweenCheckpoints` already works).
3. **G35 joins the boundary-decision tickets (P3, now the highest of the G-class):**
   web-remote-control — answer the browser-shell boundary (document under B3 or plan
   interop); carry the **Relay V2 WATCH** into any pairing/transport work.
4. **Drift WATCH:** upstream v3.15.1/v3.17.1 families (W2) — re-run the shipped-asar
   binary-greps after any desktop update; the "on par" target moves with it.
5. Pass-8 P1 surfaces (shortcuts, pin/unpin/delete verbs, approvals deep-links, the shade
   notification deep-link, the checkpoint create→restore loop, export, consent deny) need
   NO remediation — recorded as regression anchors: a pin per surface in the G-fix batches
   would lock them in.

Verification record (pass 8): emulator+relay rig ran ~35 min with ZERO emulator deaths and
ONE deliberate app death (the GAP-A8 Photo-arm repro, seventh pass running the same repro),
followed by a clean relaunch that reconnected on its own; one app exit via the known
back-press root trap, recovered by relaunch. Census: 1 FATAL total, 0 ANRs. Unit suites
were NOT re-run: `git diff fd7046a..f582edf` contains only the report file and 2
mock-module lines, so the app code tree is byte-identical to the 1487-test green baseline
(Part A) — the same reasoning accepted in passes 2–7.
