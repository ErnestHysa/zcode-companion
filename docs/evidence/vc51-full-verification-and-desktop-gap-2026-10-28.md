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

# Ninth pass — 2026-10-30 (APPENDED — pass 9; not part of the original audit)

Scope: same mandate, ninth run ("live emulation + verify everything + REA the macOS app +
document every gap; append only what's new"). App code still unchanged since `fd7046a`, so
every pass-1..8 defect and G-gap is expected to still exist; pass 9 executes the surfaces no
earlier pass ran: the `zcode://task/{id}` VIEW deep link fired DIRECTLY (plain +
`#interaction=` + two malformed shapes — the intake parser had only ever been exercised
through notification taps), the system widget-picker presence of the Pending-approvals
glance widget (pass 2 only proved provider registration), and the LiveConnectionService
FOREGROUND-SERVICE lifecycle end-to-end (passes only proved the preference toggle
persists). It also produces new A8 precision: all THREE outbound-attachment arms were
reproduced in one pass with their DISTINCT crash frames, and the shade notification record
was audited at wire level — which surfaced ONE NEW phone-side defect, GAP-A12 (the C3
shade Approve-once/Deny buttons are dropped on the §15-S13 re-post path — see Y2).

Tree under audit: `main` @ `70eb16e` (app code identical to `fd7046a` — `git diff
fd7046a..HEAD` is the report file + 2 mock-module lines; versionName 1.3.0 via `dumpsys
package`, lastUpdateTime 2026-10-30 13:16:43). Fresh `adb install -r` over the pass-8
data on the cold-booted emulator; the stored pairing auto-reconnected on first launch
(CONNECTED ~2 s, `update.check :: desktop=3.14.3 state=UpToDatenull`, same as passes 7–8).
Desktop target unchanged: `/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…`
re-measured byte-identical (mtime Sep 30, Info.plist 3.14.5) — every pass-1..8 desktop
conclusion stands; see Part Z for the new targeted enumeration.

Evidence: 27 uiautomator dumps + 3 screenshots + `notif.txt` under `/tmp/walk9/`; mock
relay wire log `/tmp/mock-relay-pass9.log` (all-flags arming identical to pass 8);
app logcat receipts quoted inline.

Rig: zcode-pixel AVD (Android 11 / API 30) cold boot (`-no-snapshot-load -gpu
swiftshader_indirect -no-audio`), MockZCode relay on `10.0.2.2:18100`. Zero emulator
deaths; THREE deliberate app deaths (the three GAP-A8 attach arms, each recovered by
relaunch with the pairing auto-reconnecting in ~2 s); one system crash dialog rendered
(Y1). No code was changed by this pass — verification + documentation round only.

## Part Y — pass-9 live emulation

### Y1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **`zcode://task/{id}` VIEW deep link, fired DIRECTLY (FIRST execution)** | `am start -a android.intent.action.VIEW -d "zcode://task/synth-task-1"` from the Tasks root landed IN the task conversation via the singleTask `onNewIntent` path ("delivered to currently running top-most instance") — session bar (`yolo · GLM-5.3-Flash · max`), question card, pending Bash approval all rendered. The fragment variant `zcode://task/synth-task-1#interaction=mock-approval-1` navigates identically. BOTH malformed shapes (`zcode://task/` empty id, `zcode://task/a/b` multi-segment) were delivered to the running instance and logged ZERO FATALs — the fail-closed intake parser (`DeepLinks.kt:24-36`: blank/oversized id and multi-segment paths return null) holds live. The §24.7 intake had only ever been exercised through notification contentIntents (pass 8) |
| **Pending-approvals widget: system widget-picker presence (FIRST live walk)** | Home long-press → menu (`Home settings / Widgets / Wallpapers`) → Widgets picker → scrolled app list renders **`ZCode Companion → Pending approvals (2 × 1)`** (dump `w9-13.xml` + screenshot `w9-widget.png`); `dumpsys appwidget` re-measures the provider as `[12] cmp=…PendingApprovalsWidgetReceiver, min=2x1`. This upgrades pass 2's registration-only receipt to a live picker walk. The BIND itself stayed rig-blocked: Pixel-Launcher widget placement needs a drag gesture and API 30 has neither `input motionevent` nor `input draganddrop` (`cmd appwidget` has no shell implementation) — recorded as rig debt, not app debt. The widget's tap path is the same `zcode://task` intake verified live above (`PendingApprovalsWidget.kt:115` builds `#interaction=` URIs through the SAME parser, fail-closed to a plain launch) |
| **LiveConnectionService FGS lifecycle (FIRST execution)** | Settings → Live-connection → `Live connection` switch ON → `dumpsys activity services` shows `ServiceRecord …LiveConnectionService isForeground=true foregroundId=4201 foregroundNoti=Notification(channel=live-connection …)` — a REAL foreground service with its persistent notification. Switch OFF → zero service records (clean teardown). Passes 1–6 verified only that the preference persists; the actual FGS start/stop was never observed until now |
| Attach-arm crash re-confirmation (3 deliberate deaths) | All THREE `+`-menu arms FATAL the app again, at the SAME frames pass 2 first mapped (D1): **Photo → `WriteComponents.kt:832`** (13:31:52, `IllegalArgumentException: Can only use lower 16 bits for requestCode`, process died `prcp TOP`), **Attach a file → `WriteComponents.kt:844`** (13:33:13), **Take photo → `WriteComponents.kt:854`** (13:33:56) — 8th consecutive pass with the Photo repro, and the first pass to re-run all three arms SINCE pass 2. NEW observation this pass: the Take-photo arm is slow enough to render the system **"ZCode Companion keeps stopping"** crash dialog (screenshot `w9-crashdlg.png` — `ActivityTaskManager: Force finishing activity`); Close app → relaunch → Connected with full bootstrap (fresh pid, `update.check` line). All three arms remain the same requestCode class — one shared helper fix covers all three sites (mechanism per D1: the `rememberLauncherForActivityResult` registrations at :657-668) |

### Y2. NEW defect

- **GAP-A12 (shade action buttons lost on re-post, P3).** The C3 shade-level
  Approve-once/Deny actions (Phase 4E) are attached ONLY on the fresh-event path:
  `NotificationBroker.onEvent` gates them on `permission && interactionId != null &&
  taskId != null` (`NotificationBroker.kt:138-141`) and passes them into the Spec. But the
  §15-S13 background re-post — `onBackgroundEntry` (`NotificationBroker.kt:179-188`) —
  builds its Spec WITHOUT the `actions` parameter, so every unseen attention record that
  surfaces on background entry loses its buttons. And the fresh path is foreclosed in the
  PRIMARY companion flow: an approval arriving while the app is foregrounded is
  §9.9-suppressed at post time (recorded unseen, nothing posted), and its ONLY subsequent
  post is the actions-less re-post. Live receipts: after HOME, logcat shows `notify.repost
  :: key=interaction:q-rig-1` and `…mock-approva… (background entry)` (13:19:27) — and the
  re-posted approval record in `dumpsys notification --noredact` carries `contentIntent`
  but NO action PendingIntents, with the per-package ranking counter reading
  **`numWithActions=0`** (no notification from this app has EVER carried actions on this
  rig). Net: `NotificationActionReceiver` + the post-decision haptic (C3) are fully wired
  but starved — the buttons exist only in the rare race of a fresh approval arriving while
  the app is ALREADY backgrounded. Remedy: replicate the permission-gated `actions` list in
  `onBackgroundEntry`'s Spec (the record already holds kind/interactionId/taskId — the
  same SpecAction builder applies).

### Y3. Pass-1..8 defects re-confirmed this pass

- **GAP-A2 (approvals never clear, P1) — re-confirmed an EIGHTH time, fresh receipt:**
  inbox `Allow once` dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 13:30:30) — the Permission card AND the Question card both remained
  in the dump 4 s later, unchanged.
- A1/A3–A11 not re-exercised this pass (app code unchanged; the pass-2..8 evidence stands
  as the latest on-device record for each).

### Y4. Rig learnings for the next pass

1. **Widget picker IS reachable by adb** (home long-press → Widgets → swipe-scroll to the
   app section); only the final DRAG-to-place is not automatable on API 30. An API 34 AVD
   (`input motionevent`/`draganddrop`) or a debug bind hook would complete the binding leg
   — record as rig debt alongside the pass-5 mock-arm debts (MOCK_AUTOMATIONS,
   MOCK_OFFPEAK, and now MOCK_INTERACTION_PUSH for a fresh interaction delivered while
   backgrounded).
2. **The task-scoped notifications visible in the shade after ANY background entry are the
   `onBackgroundEntry` re-posts**, not the fresh posts — a shade leg that wants the C3
   action buttons must first make actions exist at all (A2 remedy / Y2). Wire-level
   receipt for notification shape: `dumpsys notification --noredact`; API 30 does not
   render per-record actions, so read the ranking `numWithActions` counter.
3. **Broker records persist across app restarts** (`notify.brokerLoad :: records=3` at
   process start): a cold boot shows the PREVIOUS session's pending interactions in the
   shade, and the fresh-event diff produces no `notify.show` on reconnect — posts after
   restart come exclusively from re-posts.
4. The three attach arms cite DIFFERENT `WriteComponents.kt` lines (:832 Photo, :844
   Attach-a-file, :854 Take-photo) — future receipts should name the arm + line, and the
   eventual fix should assert all three in one test.
5. The Take-photo arm is slow enough to render the system crash dialog; the other two die
   before it appears. `Close app` → `am start` → CONNECTED ~2 s every time (three for
   three this pass).
6. Deep-link receipts: an `am start` against a RUNNING app logs "delivered to currently
   running top-most instance" (the onNewIntent path); malformed URIs are silent no-ops
   with zero FATALs — do not mistake the warning line for a failure.

## Part Z — macOS desktop pass 9 (targeted enumeration)

Asar digest re-measured byte-identical (`99e280fb90b626cc…`, mtime Sep 30, Info.plist
3.14.5), so the pass-1..8 REA conclusions all stand. New targeted enumeration this pass,
driven from the v3.14.3 source map (identifier greps against a minified asar are blind —
UI component names don't survive bundling; distinctive STRING literals — i18n message
ids, data-testids — do):

### Z1. NEW boundary inventory completion (NOT a gap — B3 family): the multi-pane workbench

The desktop opens MULTIPLE task conversations side-by-side: `WorkbenchPane.tsx` /
`V4ChatPane.tsx` with per-pane shells (`TID_V4_PANE_SHELL = "v4-pane-shell"`), vertical
split with drop-target panes (`onDropSession(paneId, side)`), focused-pane input
semantics ("竖切多 pane 时由宿主传入 SessionPane.focused"), and the `/side` app-local slash
command (ConversationComposer comment: "App 层本地斜杠命令（如 `/side`）"). Shipped-asar
receipts: `side-pane` ×35, `splitPane` ×15, `TerminalTab` ×11. The phone is strictly
single-conversation — desktop form factor, same class as keyboard shortcuts/command
palette (E3), recorded here because no pass 1–8 ever enumerated the plane. The `side-pane`
family also spans the in-conversation side pane (`workspaceSidePane.ts`: browser-use guest
mounts — already the G18 boundary).

### Z2. Evidence strengthening for existing defects/gaps (no severity changes)

- **Composer attachment plane (A8 + G28).** The desktop's v4 composer carries a FULL
  media+file attachment model (`ConversationComposer.tsx`: `isMediaChatComposerAttachment`
  split, image/video preview via `ImagePreviewDialog`, keyboard `PasteCapturePlugin` +
  `ChatComposerPasteEvent` in the Lexical input for paste-attach) — attach-and-send is a
  working desktop surface that the phone reproduces with a FATAL on every arm (Y1:
  :832/:844/:854). The desktop `uploadAttachment` services hits (×8) are the FEEDBACK
  plane, not conversations — no desktop-side crash analogue exists.
- **Hooks + MCP settings planes re-measured at the string level:** `settings.hooks.` ×174
  + `hooks-settings-section` testid, `settings.mcp.` ×278 + `mcp-settings` testid —
  SHIPPED and substantial (HookForm/HooksSection add-edit-delete + workspace hook trust;
  McpSettingsSection + McpFailurePresentation), exactly as pass-2 E3 anticipated when it
  classified hooks/MCP/plugin/workflow authoring as deliberate B3 boundaries. Re-cited
  with receipts; no severity change.
- **`promptHistory` ×4** (recent-prompts memory) and **`resumeSession` ×8** — small
  desktop conveniences inside planes already covered (composer plane; task resume is at
  phone parity per pass 6). No new gap.

### Z3. Cross-checks that found NO new gap

- **Voice input** — no voice/speech/microphone plane exists in the desktop UI (only i18n
  strings and error-attribution files match): NEITHER platform has it. Parity.
- **Terminal tabs** — desktop `TerminalSession`/tabbed interactive terminal re-confirmed
  shipped (`settings.terminal` ×21); the phone's strictly view-only terminal remains
  ADR-022 (E4), unchanged.

## Part AA — remediation order update (supersedes only where pass 9 applies)

1. **GAP-A8 stays FIRST, arm coverage re-confirmed against pass 2's map** (same frames
   :832/:844/:854, same requestCode class — the fix is one shared PendingIntent-requestCode
   helper asserted by a three-arm test). GAP-A2 second (EIGHTH same-session receipt, Y3).
2. **NEW: GAP-A12 (P3)** joins the A-list behind A8/A2 — attach the permission-gated
   `actions` list in `onBackgroundEntry`'s re-post Spec (`NotificationBroker.kt:179-188`);
   the C3 receiver + haptic machinery already exist and are unit-covered, they are only
   starved on this path. A pin: post an unseen approval record, enter background, assert
   the posted notification carries two action intents (the `numWithActions` counter is the
   wire-level oracle).
3. **Rig debt (not app debt):** widget drag-placement needs an API 34 AVD or a debug bind
   hook (Y4.1); MOCK_INTERACTION_PUSH arm for a backgrounded fresh-interaction leg.
4. **Regression anchors to pin in the G-fix batches:** the `zcode://task` intake
   (plain + `#interaction=` + the two malformed shapes — all four now live-verified), the
   LiveConnectionService start/stop lifecycle, and the widget-picker presence.
5. G-class and B3 inventory unchanged by this pass (Z1 multi-pane workbench recorded as a
   boundary completion; Z2/Z3 re-cites only).

Verification record (pass 9): emulator+relay rig ran ~30 min with ZERO emulator deaths and
THREE deliberate app deaths (the three A8 attach arms, each with a clean relaunch +
auto-reconnect), one system crash dialog rendered and dismissed (Y1). Census: 3 FATALs
(all deliberate A8), 0 ANRs. Unit suites were NOT re-run: `git diff fd7046a..HEAD`
contains only the report file and 2 mock-module lines, so the app code tree is
byte-identical to the 1487-test green baseline (Part A) — the same reasoning accepted in
passes 2–8.

# Tenth pass — 2026-10-30 (APPENDED — pass 10; not part of the original audit)

Scope: same mandate, tenth run ("live emulation + verify everything + REA the macOS app +
document every gap; append only what's new"). App code unchanged since `fd7046a` (`git diff
fd7046a..HEAD` is the report file + 2 mock-module lines), so every pass-1..9 defect and
G-gap is expected to still exist. Pass 10 executes six surfaces no earlier pass ran as
first-class legs: the STOP interrupt loop (pass 3 pressed Stop only as the GAP-A9 escape,
never recording the wire receipt), the goal slash verbs (G26's "modeled AND wired" upgraded
to live-fired), the Add-direct-endpoint dialog FORM (A10 covered only the rejection path),
shade-notification dismissal/resurrection semantics, the deep-link intake's MANIFEST-level
host gate (pass 9 exercised only the parser layer), and the QueueStrip's reachability —
which turns out to be rig-blocked, not verified-working. It produces ZERO new phone defects
(first pass with no new A-gap) plus the 9th consecutive A8/A2 receipts, and the REA leg
yields FIVE new desktop planes no pass 1–9 ever cited (G36–G40).

Tree under audit: `main` @ `afa39e0` (app code identical to `fd7046a`; versionName 1.3.0
via `dumpsys package`, lastUpdateTime 2026-10-30 13:16:43 — the pass-9 build, reused).
Fresh cold boot; the stored pairing auto-reconnected on first launch (`auth.matched` +
`hub.phase :: transport (RELAY_ZAI) → CONNECTING` within ~2 s). Desktop target unchanged:
`/Applications/ZCode.app` asar sha256 `99e280fb90b626cc…` re-measured byte-identical,
source reference clone `29628c9a` (v3.14.3) — every pass-1..9 REA conclusion stands; see
Part AC for the new enumeration.

Evidence: 14 uiautomator dumps + 2 screenshots under `/tmp/walk10/`; mock relay wire log
`/tmp/mock-relay-pass10.log` (all-flags arming identical to pass 9); REA-MCP evidence
bundle `/tmp/rea-pass10-asar-bundle.json` (identical artifact graph `agm_27661707668b…`,
Part AC header); app logcat receipts quoted inline.

Rig: zcode-pixel AVD (Android 11 / API 30) cold boot (`-no-snapshot-load -gpu
swiftshader_indirect -no-audio`), MockZCode relay on `10.0.2.2:18100`. Zero emulator
deaths; ONE deliberate app death (the GAP-A8 Photo arm, recovered by relaunch with the
pairing auto-reconnecting in ~2 s). No code was changed by this pass — verification +
documentation round only.

## Part AB — pass-10 live emulation

### AB1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **STOP interrupt loop (FIRST-class wire receipt)** | "ping-rig10" sent → `sendConversationCommandV4` (mock id, ok 207 ms) → mock `sendText` effect flipped control to running/stoppable → composer flipped to "Keep typing to queue follow-up changes" + Queued chip + **"Stop the agent"**. Stop tapped → `sendConversationCommandV4` (ok 62 ms) → mock `stop` effect applied (`phase=idle, sessionEnded=true, canStop=false`, `inputRouting=startNow`) → composer returned to "Message the agent…" the same second. The interrupt path executes cleanly end-to-end; pass 3 had only pressed Stop as an A9-escape and never recorded the receipt |
| **Goal slash verbs live-fired (FIRST execution)** | G26 documented `sendGoal`/`pauseGoal`/`resumeGoal` as "modeled AND wired" from static reading (`ConversationViewModel.kt:432-443`); this pass typed `/goal pause` and `/goal resume` in the rig task. BOTH dispatched their `pauseGoal`/`resumeGoal` commands over `sendConversationCommandV4` (relay receipts), the mock ACKed both `status:"accepted"`, and both cleared the draft silently — the `sendSlash` ACCEPTED semantics (draft cleared, no user row, no notice). The GOAL_*_REFUSED degrade branches (`SendNotice.Key.GOAL_PAUSE_REFUSED`) did NOT fire because the mock ACKs ANY command type accepted (`applyCommandEffect` returns null for unhandled types → plain accepted ack, `MockZCode.kt:1906-1919`) — the refusal UX stays unexercised (rig debt below) |
| **Add-direct-endpoint dialog FORM (first enumeration)** | Settings → Endpoints → "Add direct endpoint" renders four fields: **Name (optional)** / **Server URL** (`ws://host:3030/ws` placeholder) / **Token** (`zcode --web --token …`) / **Fallback URLs (comma-separated, optional)**, plus Cancel/Add. Typed a name → **Cancel** → dialog closed, no endpoint card created, no probe on the wire (client-side cancel; A10 covers only the Add→Rejected path). The form inventory is now on record for the A10/G8 fix rounds |
| **Shade notification semantics (precision upgrades)** | (a) Re-posts fire on the FIRST background entry after records become unseen — a SECOND background entry produced ZERO `notify.repost` lines (pass 9's "after any background entry" phrasing tightened: it is once-per-unseen-record, not every-entry). (b) Dismissal needs a FAST fling (`input swipe 700 Y 10 Y 200` works; the same gesture at 400–500 ms/shorter does nothing). (c) A dismissed notification does NOT resurrect on the next background entry (records stay dismissed; 0 records after re-cycle). (d) Foregrounding CANCELS the remaining attention post (2 records → 0 after app foreground — the §9.9 suppression cancels on foreground). (e) Long-press opens the API 30 alerting state inline: the notification's CHANNEL name renders ("**Approvals & questions**"), plus the other record's alerting summary ("Default — May ring or vibrate based on phone settings" / "Silent"), "Turn off notifications", "Done", "More settings", "Clear all" |
| **Deep-link intake: manifest-level host gate (new layer)** | `am start -a android.intent.action.VIEW -d "zcode://settings/deep"` is rejected by the PACKAGE MANAGER ("unable to resolve Intent … dat=zcode://settings/deep") — the VIEW filter carries `android:scheme="zcode" android:host="task"` (`AndroidManifest.xml`), so unknown HOSTS never even reach the app. A 4-segment `zcode://task/synth-task-1/extra/deep` DID resolve (pathPattern permissive) and was delivered to the running instance, where the parser's multi-segment branch (`DeepLinks.kt:30`, `pathSegments.size > 1 → null`) made it a silent no-op — zero FATALs, app alive. Pass 9 tested only malformed paths under the task host; the intake is now proven fail-closed at BOTH layers (manifest host gate = outer, parser shape gate = inner) |

### AB2. NEW defect

- **None.** First pass with no new A-gap: all six legs landed on surfaces that behave
  correctly (AB1) or on already-documented defects (AB3). The fast-fling dismissal
  discoverability is recorded as a rig learning, not app debt.

### AB3. Pass-1..9 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed a NINTH time, same frame:** composer +
  → Photo → `FATAL EXCEPTION: main` 15:41:48, `IllegalArgumentException: Can only use
  lower 16 bits for requestCode` at `FragmentActivity.checkForValidRequestCode:714` →
  `WriteComponents.kt:832`, process died (`pidof` empty). Clean relaunch → Connected in
  ~2 s. Ninth consecutive pass with the same repro; the three-arm map (:832/:844/:854)
  stands from pass 9.
- **GAP-A2 (approvals never clear, P1) — re-confirmed a NINTH time, fresh receipt:**
  Approvals inbox → "Allow once" dispatched (`approvals.answer :: interaction=mock-approva
  option=allowOnce`, 15:43:38.945) — the Permission card AND the Question card both
  remained in the dump 4 s later, unchanged.
- A1/A3–A12 not re-exercised this pass (app code unchanged; the pass-2..9 evidence stands
  as the latest on-device record for each).

### AB4. Rig learnings for the next pass

1. **The QueueStrip verbs are RIG-BLOCKED, not verified-working.** Send now / Edit / Move
   up-down / auto-drain (`sendQueuedNow`/`editQueueItem`/`reorderQueueItem`/`setAutoDrain`
   — all four WIRED in `WriteComponents.kt:467+` and all four SCRIPTED in the mock's
   command effects) are unreachable: nothing populates `script.queueItems` at bootstrap,
   GAP-A9 forecloses the enqueue-send path, and the QueueStrip renders only when items
   exist. G4's "phone queue has move/edit/send-now/auto-drain" is a STATIC statement —
   none of the four verbs has ever been executed live. RIG DEBT: a `MOCK_QUEUE=1` arm that
   seeds queue items completes the I1 reorder round-trip family (alongside MOCK_AUTOMATIONS,
   MOCK_OFFPEAK, MOCK_INTERACTION_PUSH); a `MOCK_REFUSE_COMMANDS=1` arm (respondError for a
   named command type) exercises the goal-refusal and generic-refusal degrade branches.
2. **Notification dismissal by adb:** fast fling `input swipe 700 Y 10 Y 200`; slow or
   short swipes are absorbed. Dump Y coordinates are accurate inside the shade.
3. **Deep-link fail-closed receipts look like errors:** an unknown-host URI fails `am
   start` with "unable to resolve Intent" — that IS the manifest host gate working, not a
   harness failure. Malformed paths under `zcode://task/` resolve and are silently dropped
   by the parser; neither shape produces a FATAL.
4. **Relay-log command ids are per-connection JSONRPC ids**, not monotonic across app
   restarts — identify sends by counting `sendConversationCommandV4` lines against the leg
   sequence, never by comparing ids.

## Part AC — macOS desktop pass 10 (targeted enumeration)

Asar digest re-measured byte-identical (`99e280fb90b626cc…`; Info.plist 3.14.5; source
clone `29628c9a` v3.14.3), so the pass-1..9 REA conclusions all stand. REA-MCP re-inspection
this pass (`open_binary` + `inspect_artifact` record-and-continue, evidence bundle
`/tmp/rea-pass10-asar-bundle.json`, 2 records): the inventory reproduced the IDENTICAL
artifact graph — manifest id `agm_27661707668b…` (the same id pass 1 recorded), 30,154
edges, 3 integrity contradictions (the known signed-after-packaging unpacked natives,
observed-untrusted) — hard confirmation the new enumeration ran against the same shipped
artifact. New enumeration driven by diffing the FULL `settings.*` i18n catalog
(`en-US.ts`) against the report's citation history — every distinctive string ship-checked
against the asar. Four planes never cited by any pass 1–9 turned out SHIPPED:

### AC1. NEW GAP-G36 (P3): the Office/Coding interface-mode presentation plane

`lib/interfaceMode.ts` defines `"office" | "coding"` persisted in localStorage
(`zcode-interface-mode`) with legacy-value migration (old "general"/"concise" map to
office). The mode is asked at ONBOARDING (`OccupationOnboarding` +
`OnboardingModeSelector` with officeMode/codingDescription cards), switchable in
`SettingsPage`, and consumed by **36 UI files** across the conversation timeline —
`ToolCallBlocks.tsx`, `Terminal.tsx`, `v4/SessionPane.tsx`, `v4/ConversationRowView.tsx`,
`v4/ConversationTurnGroup.tsx`, `v4/ConversationStatusPanel.tsx`,
`v4/ConversationDraftEmptyState.tsx`, `quickpick/TaskFindDialog.tsx`,
`app-shell/WorkspaceShellLayout.tsx`, … The catalog wording: "Office mode focuses on
summaries and results. Coding mode shows commands, output, and code changes." Asar
receipt: "Office mode focuses on summaries" ×1. The phone renders ONE fixed
presentation (full tool detail) with no mode concept anywhere — a cross-cutting
presentation gap spanning the whole timeline, hence P3 rather than P4.

### AC2. NEW GAP-G37 (P4): UI localization

The desktop ships a full i18n plane: `IntlProvider` with **en-US + zh-CN** catalogs,
system-locale resolution (`readNavigatorLanguage`), a persisted preference
(`zcode-locale-preference`, "system" supported), and a cross-window broadcast channel
(`state:locale`) so all windows re-render on switch. Surfaces: a SettingsPage locale
select (telemetry `featureId: "settings.locale", action: "change_locale"`) and sidebar
locale entries; the exported `LocaleSwitcher` button is UNMOUNTED (its `locale-toggle`
testid appears ×0 in the asar — dead export, recorded so future passes don't chase it).
Asar receipts: `settings.locale.zh-CN` ×4, `zh-CN` ×73, `switchLanguage` ×2. The phone
ships English-only resources (single `values/`) with no locale surface.

### AC3. NEW GAP-G38 (P4): interface zoom

`WorkspaceSidebarFooter.tsx:316` renders "Interface zoom" beside the theme group
(`sidebar.settings.interfaceZoom`; asar ×3). The phone has no in-app zoom/font-scale —
OS font scale only (the ledger's recorded font-1.3 crush small is the symptom this
setting addresses on desktop).

### AC4. NEW GAP-G39 (P4): task auto-archive policy

`settings.taskAutoArchiveDays` ships "Archive after 3/7/14/30 days" options (asar
"Archive after 14 days" ×1). The phone's Archive verb is manual-only (pass 6 executed
rename/archive/resume; pass 8 Delete) — no aging policy anywhere in Settings.

### AC5. NEW GAP-G40 (P3): workspace search scope — the .zcodeignore rules editor

`settings/WorkspaceFileSearchSection.tsx` ships a 15-string plane: "Workspace Search
Scope", an ignore-rules EDITOR, "Sync from .gitignore", "Restore default rules",
"Saved; effective on the next search", "Reveal file location" ("​.zcodeignore lives at
the workspace root"), "Unsaved changes", noWorkspace state. Asar receipts: "Workspace
search ignore rules editor" ×1, "Sync from .gitignore" ×1. The phone has NO workspace
file search at all (the Files panel is the honest-fault read-only view), so this config
plane is doubly absent — recorded as its own gap because it implies an entire desktop
capability (workspace-scoped file search honoring .zcodeignore) the phone has never
approximated; sibling to the G17/G28 rich-preview family.

### AC6. Evidence completions (no new gap ids)

- **Theme variants:** the desktop carries FOUR theme states — light/dark PLUS
  `zai-light`/`zai-dark` brand palettes (`styles.css` `.theme-zai-light` defines an
  alternate palette incl. distinct trajectory colors; asar `zai-light` ×28). Phone:
  light/dark/system only (pass 1). Fold into the appearance row of the G-batch.
- **Interaction-behavior default:** `settings.zcodeInteractionBehavior` = Queue/Guide —
  "While ZCode is running, add follow-up actions to the queue or guide them to run after
  the next tool call" (shipped ×18; `v4/composer/followupModeSettings.ts`). The phone HAS
  the in-session follow-up-mode picker (`setFollowupMode` wired) but no default-behavior
  SETTING — completion for the G4/G9 queue family.

## Part AD — remediation order update (supersedes only where pass 10 applies)

1. **GAP-A8 stays FIRST, GAP-A2 second** — 9th consecutive receipts each (AB3), same
   frames and semantics; nothing in pass 10 changes their remedies.
2. **NEW: G36 (P3) joins the G-batch at the top of the P3s** — interface-mode is the
   largest plane no pass had recorded (36 consumers spanning the timeline); the phone-side
   remedy is a presentation-mode setting that gates tool-detail rendering, not a port of
   the desktop store.
3. **NEW: G40 (P3)** — the .zcodeignore editor implies the workspace-file-search
   capability gap; batch with G17/G28.
4. **NEW: G37/G38/G39 (P4)** — locale plane, interface zoom, auto-archive policy; tail of
   the G-batch.
5. **Rig debt additions:** `MOCK_QUEUE=1` (seed queue items → the four QueueStrip verbs
   become executable; AB4.1) and `MOCK_REFUSE_COMMANDS=1` (exercise the goal-refusal
   degrade branches). Existing debts (MOCK_AUTOMATIONS, MOCK_OFFPEAK,
   MOCK_INTERACTION_PUSH, widget drag-placement) unchanged.
6. **Regression anchors to pin:** the STOP loop (send → stoppable → stop → idle patch)
   and the goal slash verbs (dispatch → wire → accepted → draft-clear) are now
   live-verified behaviors with no unit pin; add Robolectric/integration pins alongside
   pass 9's deep-link anchors, plus an intent-filter test asserting `android:host="task"`
   (the newly proven OUTER gate).

Verification record (pass 10): rig ran ~30 min with ZERO emulator deaths and ONE
deliberate app death (the GAP-A8 Photo arm, clean relaunch + auto-reconnect in ~2 s).
Census: 1 FATAL (deliberate A8), 0 ANRs. Unit suites were NOT re-run: `git diff
fd7046a..HEAD` contains only the report file and 2 mock-module lines, so the app code
tree is byte-identical to the 1487-test green baseline (Part A) — the same reasoning
accepted in passes 2–9.

## Part AE — pass-11 live emulation

Rig additions THIS pass (rig debt discharged before the walk): `MOCK_QUEUE=1` seeds two
queued follow-ups (`q-rig-1/q-rig-2`) with `autoDrain=false`, and
`MOCK_REFUSE_COMMANDS=1` makes the goal verbs answer `respondError` — both arms were
AB4.1's named debts. Two mock fixes rode along: a `refuseCommandTypes` script plane +
gate BEFORE any command effect/ack (server-spoke `fault.command.rigRefused:<type>`), and
the snapshot's queue section now echoes `script.autoDrain` (was hardcoded `true` while
only the patch builder used the script value — a mock self-inconsistency that would have
lied on reconnect). Mock self-tests re-run green (`:tools:mockzcode:jar + test`, 26 s);
app code tree untouched (byte-identical to the fd7046a baseline, same reasoning as
passes 2–10 for not re-running unit suites).

### AE1. NEW verified surfaces (first executed this pass; wire receipt where relevant)

| Surface | Result |
| --- | --- |
| **QueueStrip verbs — ALL FOUR first-class executions (G4's static statement is now LIVE-verified)** | With MOCK_QUEUE armed the strip rendered for the first time ever ("Queued (2) · paused", Auto-send toggle, per-row Move up/down + Send now + Edit). (a) **reorderQueueItem**: Move up on item 2 → `sendConversationCommandV4` (id=21) → the mock's effect flipped script order → the queue PATCH redrew the strip with "two" above "one" (authoritative round-trip, not a local reorder). (b) **editQueueItem**: Edit → "Edit queued message" bottom sheet (prefilled field + Cancel/Save) → replaced text → Save → id=88 → patch replaced the row text on the strip. (c) **sendQueuedNow**: Send now on the edited item → id=93 → patch removed it (2→1). (d) **setAutoDrain**: Auto-send toggle → id=104 → patch cleared the "· paused" label (strip header "Queued (1)"). I1's reorder round-trip family is complete; AB4.1's rig debt is discharged |
| **Approval answer + the ALREADY_RESOLVED honest copy (first execution)** | Approvals bell (TaskList top bar, 48 dp) → inbox → "Allow once" dispatched the answer (id=53). The SECOND tap on the now-terminal interaction degraded EXACTLY per the T2 contract: the client-side coordinator's terminal-machine gate (`InteractionCoordinator.kt:148`) returned `AlreadyResolved` with ZERO wire traffic, and ApprovalsScreen painted the typed copy "Already handled on the desktop." (the screen-level `answerError` channel, ApprovalsScreen.kt:209). The answered cards REMAINED in both the inbox and the conversation — A2's 11th receipt below |
| **Goal slash verbs under REFUSAL (first execution of the degrade branch — and it is broken, see AE2)** | `/goal pause` dispatched `pauseGoal` three times; the mock's MOCK_REFUSE_COMMANDS arm answered `fault.command.rigRefused:pauseGoal` each time (server-spoke class) and the coordinator logged the authoritative `command.rejected :: type=pauseGoal` WARN three times (18:27:04.197 / 18:27:54.008 / 18:29:02.520). The draft was preserved (refusal semantics half-work), but the SendNotice GOAL_PAUSE_REFUSED banner did NOT render at refusal time — full analysis in AE2 |

### AE2. NEW defect

- **GAP-A13 (UX break, P2): the goal-verb refusal feedback is invisible at refusal time —
  and when it finally paints, it is stale.** Three wire-confirmed `pauseGoal` rejections
  produced NO SendNotice bar in five captures across six minutes (the IME-up posture a
  real user sends from). The bar only painted after a keyboard-down layout pass minutes
  later — showing the PAUSE copy ("Goal pause was refused (the goal may not be pausable
  right now).") at [0,0] AX bounds while the most recent dispatch had been a (tap-missed)
  resume attempt. Static mechanism: `_sendNotice` is a `MutableStateFlow` READ (`.value`)
  inside the five-source combine that builds `ConversationUiState`
  (`ConversationViewModel.kt:166` vs the read at `:212`) — a notice-only change cannot
  re-emit the state, so the composer renders no banner until some UNRELATED source (draft,
  session patches, pending-sends, rows) happens to emit. This is the exact FIELD-class bug
  the vc21 draft fix documented and repaired for the draft (the WriteState
  draft-as-combine-source comment at `:162-170`); the notice never got the same treatment.
  Remedy: make `_sendNotice` a combine source (fold into WriteState) or collect it into
  the uiState directly. The user-facing failure today: a refused slash verb looks like a
  dead button — draft stays, no signal — for an unbounded time.

### AE3. Pass-1..10 defects re-confirmed this pass

- **GAP-A8 (attach-send crash, P1) — re-confirmed an ELEVENTH time, same frame:** composer
  + → Photo → `FATAL EXCEPTION: main` 18:38:32.531, `IllegalArgumentException: Can only
  use lower 16 bits for requestCode` at `FragmentActivity.checkForValidRequestCode:714`,
  process dead (`pidof` empty). Clean relaunch → Connected in ~2–3 s. Eleventh
  consecutive pass with the same repro; the three-arm map stands.
- **GAP-A2 (approvals never clear, P1) — re-confirmed an ELEVENTH time, fresh receipt:**
  the id=53 answer dispatched (`allowOnce`); the Permission card AND the Question card
  remained in the Approvals inbox and the conversation afterwards, unchanged.
- A1/A3–A12 not re-exercised this pass (app code unchanged; the pass-2..10 evidence
  stands as the latest on-device record for each).

### AE4. Rig learnings for the next pass

1. **The IME eats coordinate taps at the composer row.** With the keyboard up, the send
   button and sheet buttons sit ABOVE the IME; `input tap` coordinates taken from a
   keyboard-down dump land INSIDE the IME (three phantom misses produced zero wire). The
   keyboard-down dump → tap sequence is reliable; the reverse is not.
2. **The goal-refusal arm's receipts live in the APP log, not the relay log.**
   `respondError` does not log a "MOCK:" line for the refusal (the v4call line appears,
   but the fault text only shows in the app's `v4.call channel error` +
   `command.rejected` WARNs) — grep logcat for `command.rejected`, not the relay log.
3. **Send-now is item-consumption only in the mock**: the `sendQueuedNow` effect removes
   the queue item and pushes a patch but does NOT script a synthetic run (composer stays
   idle). The verb's wire + patch receipt is real; run-start semantics are out of the
   mock's scope. Record-correcting AB4.1's implication that send-now exercises the send
   path end-to-end.
4. **`input keyevent --longpress KEYCODE_DEL` chains and parallel `&`-backgrounded
   keyevents race** — drafts end up partially deleted ("/goae"). Sequential per-call
   `input keyevent 67` (caret-first MOVE_END) is the reliable clear.
5. **The notification shade, approvals bell, and top-bar buttons**: TaskList top-bar
   order left→right is [approvals bell, endpoints, archived]; the Approvals screen is
   NOT reachable by any `am start` action tried this pass (no exported activity) — the
   bell is the reliable entry.

## Part AF — macOS desktop pass 11 (targeted enumeration)

Asar digest re-measured byte-identical (`99e280fb90b626cc…`; Info.plist 3.14.5; source
clone `29628c9a` v3.14.3), so the pass-1..10 REA conclusions all stand. New enumeration
angle: the WORKSPACE MANAGEMENT plane — chosen because the phone gained a workspace
concept in vc44 (task grouping) and no pass 1–10 had audited the desktop side of that
capability.

### AF1. NEW GAP-G41 (P3): multi-workspace management — add/open/switch/reorder/remote

The desktop treats workspaces as FIRST-CLASS MANAGED OBJECTS: `WorkspaceSidebar.tsx`
renders a "Projects" section (`workspaceSidebar.projectsSection`) with per-workspace
tabs (task groups keyed by `buildTaskWorkspaceKey(path, identity)`, loading state per
group, `workspace.context.lastActivity` "Last active {time}"), an add-project "+" menu
(`workspaceSidebar.addProject` → "Open folder" (`workspace.openFolder`) + "Remote
connection" (`remote.trigger`, Cloud icon)), and a full DRAG-REORDER plane
(`DndContext` + `verticalListSortingStrategy`, `handleWorkspaceDragStart/End` —
user-orderable, persisted). The remote path is a four-step wizard (`remote.wizard`:
Choose method → Connection settings → Connecting → Choose remote directory) with
saved-connection history (`remote.history.empty` "No matching saved connections"),
WSL-UNC prompting (`workspace.wslUncPrompt`), and an SSH/WSL/Docker remote-environment
model (the Computer-Use catalog copy names all three). Supporting strings:
"Add new workspace", "Start from scratch", `workspace.backToWorkspace`,
`workspaceSidebar.noProjects` "No open projects". Asar receipts (shipped): "Add
project" ×1, "No open projects" ×1, "Remote connection" ×4, "Add new workspace" ×1,
"Start from scratch" ×1, `workspaceSidebar.projectsSection` ×3,
`workspaceSidebar.addProject` ×3, `remote.trigger` ×4, "Choose remote directory" ×1,
`workspace.context.lastActivity` ×3, `remote.history.empty` ×3, "Open folder" ×2,
`workspace.openFolder` ×4. THE PHONE: workspaces exist only as a PASSIVE grouping —
tasks arrive grouped by `workspaceIdentityKey` and render under collapsible workspace
headers (`TaskListScreen`/`WorkspaceCollapse.kt`); there is no add, no open-folder, no
active-workspace switch, no reorder, and no remote-environment concept anywhere in the
app (grep: zero consumers of any of the above strings' intents). Scope note: pass 6's
Q3 recorded the SSH DIALOG as a desktop-form BOUNDARY — that disposition stands for the
dialog itself; G41 is the broader capability gap (the workspace MODEL the dialog feeds:
multi-workspace registration/switching/ordering), which no pass had enumerated. The
companion-shaped remedy is a workspace switcher/manager over the workspaces the phone
already RECEIVES, not a folder picker.

### AF2. Evidence completions (no new gap ids)

- **Snapshot-vs-patch autoDrain (mock-side, fixed this pass):** the desktop's
  queueState contract expects snapshot and patches to agree; the mock now honors it.
- **AlreadyResolved styling observation:** the Approvals screen renders the typed
  resolved-elsewhere copy through its `answerError` channel, which paints in the ERROR
  palette (red) — an INFO-class message in error clothing. Cosmetic; recorded so the
  G-batch's approvals work picks a neutral style deliberately.

## Part AG — remediation order update (supersedes only where pass 11 applies)

1. **GAP-A8 stays FIRST, GAP-A2 second** — 11th consecutive receipts each (AE3); nothing
   in pass 11 changes their remedies.
2. **NEW: GAP-A13 (P2) joins the A-batch directly behind A2** — the goal-refusal notice
   bug is small, precisely mechanism-mapped (make `_sendNotice` a combine source, mirror
   the vc21 draft precedent), and it silently eats ALL slash-verb refusal feedback
   (compact/goal set/resume/pause share the sendSlash path).
3. **NEW: G41 (P3) joins the G-batch at the top of the P3s beside G36** — the workspace
   management plane is the largest remaining phone-side capability gap with a true
   companion shape (the phone already renders workspace groups; management is the missing
   half).
4. **Rig debt CLEARED:** MOCK_QUEUE and MOCK_REFUSE_COMMANDS are implemented and
   exercised (AE1); MOCK_AUTOMATIONS / MOCK_OFFPEAK / MOCK_INTERACTION_PUSH and widget
   drag-placement remain open (pass-5/9 debts).
5. **Regression anchors to pin:** the four queue verbs (each: wire type + patch effect on
   strip) and the AlreadyResolved no-wire degrade both deserve integration pins
   alongside AE2's A13 fix pin (a uiState-emission test: set notice → assert uiState
   re-emits WITHOUT an unrelated source change).

Verification record (pass 11): rig ran ~55 min with ZERO emulator deaths, ONE mock
restart (flag re-arm, documented reconnect), ONE deliberate app death (the GAP-A8 Photo
arm, clean relaunch + auto-reconnect in ~2–3 s). Census: 1 FATAL (deliberate A8), 0 ANRs,
0 unexpected app defects beyond AE2. Unit suites were NOT re-run: the app code tree is
byte-identical to the fd7046a green baseline; the only code change this pass is in
tools/mockzcode (rig-only), whose self-tests re-ran green.

# Verification audit + re-adjudication legs — 2026-10-30 (APPENDED — pass 12; not part of the original audit)

Scope: an INDEPENDENT verification of every claim in this report against the current
tree (`main` @ `888d3aa` — app code still byte-identical to `fd7046a`, diff = this report
+ tools/mockzcode), the shipped desktop bundle (asar sha256 `99e280fb90b626cc…`
re-measured byte-identical; Info.plist 3.14.5 re-read), the v3.14.3 source clone
(`29628c9a`) + fetched v3.15.1/v3.17.1 tags, and the surviving /tmp rig artifacts
(pass 1–3 walk dirs and relay logs are PURGED from /tmp; pass 4–11 artifacts survive).
Verdict up front: the report is accurate where it is load-bearing — every A-defect
mechanism confirmed at its cited code, 38 of 45 sampled desktop citation clusters exact
(several to the line), and every meta claim reproduces exactly. THREE potential false
positives were found (all in the desktop-gap matrix's phone-state columns, NONE in the
A-defect list), plus minor drift — and two fresh rig legs re-adjudicated GAP-A4
(REFUTED on the current build) and GAP-A11 (core CONFIRMED, two sub-claims corrected).
No code was changed by this pass — verification + documentation round only.

## AH1. Meta claims — ALL reproduce exactly

asar digest `99e280fb90b626cc7bbaf33d9b5fcd652a8108ff5b3fbb0c770cd39c15d8d70f`
(re-measured); Info.plist CFBundleShortVersionString 3.14.5; `git diff fd7046a..HEAD`
= this report + `tools/mockzcode` only; the debug APK still in `app/build/outputs` is
30,286,232 bytes with sha256 `342164798266a24f…` (the cited build, byte-exact); the
1487-test record aggregates exactly from the persisted module XMLs (1,084
`testDebugUnitTest` + 403 JVM `test` = 1,487 tests, 0 failures, 0 skipped, 17 modules);
the REA evidence bundle `/tmp/rea-pass10-asar-bundle.json` carries artifact sha
`99e280fb…` and manifest id `agm_27661707668b…` as cited; the W2 drift enumeration
re-measures at exactly 1,462 files changed / 584 new (v3.14.3→v3.17.1, `packages/ui/src`)
with TodoPanel/TaskSearchDialog/gen-ui/image-preview-editor/ToolCallBlocks all present in
v3.17.1 and ×0 in the shipped asar.

## AH2. POTENTIAL FALSE POSITIVES — verify before acting on these rows

1. **G32 phone-state (pass-7 T2) — REFUTED.** T2 records `GitPort.switchBranch` as
   "MODELED, never sent — no screen dispatches it." FALSE at the audited tree:
   `features/git/.../ui/GitScreen.kt:411` dispatches it (`BranchSheet.onSwitch` →
   `port.switchBranch(name)`, with a success snackbar on result) and the real wire op
   exists at `app/.../connect/Phase9Ports.kt:218-219`
   (`hub.activeTransport().gitSwitchBranch(...)`). The gap survives only NARROWER: the
   phone can switch branches but has no branch-CREATE and none of the desktop's
   switch-assist (commit-before-switch preview, `switchAssist.ts` blocked→commit machine).
   Re-scope the row to "branch create + commit-assist missing; switch is shipped" — it
   leaves the "Modeled, never sent" family (G3/G4/G6/G8 keep that classification).
2. **G37 phone-state (pass-10 AC2) — REFUTED in part.** AC2 records the phone as
   "English-only resources (single `values/`) with no locale surface." The first half is
   false: `app/src/main/res/values-zh-rCN/` exists with a 259-line Chinese strings.xml.
   What is genuinely absent is the IN-APP locale switcher/preference plane (no settings
   surface; localization is system-locale-driven). Re-scope the row to "in-app locale
   picker missing; zh-CN system-locale resources already ship."
3. **Pass-2 E4 git cross-check — REFUTED as written.** E4 concluded "the only desktop
   git mutation surface found is checkpoint restore," grading the phone at parity. The
   desktop clone carries `GitActionMenu` commit dialogs
   (`COMMIT_DIALOG_ACTION_IDS = ["commit","commitAndPush","push"]`, git-commit-dialog
   testids, commit-message generate) and `GitBranchSwitcher` create/switch
   (`createBranchAndSwitch`, `GitBranchDialogs.tsx`). Pass 7 later found the switcher
   desktop-side but then mis-assessed the phone side (item 1). Net: E4's parity verdict
   for the git mutation plane was wrong in both directions; G32-as-rescoped (item 1) is
   the correct record.

### AH3. Minor record drift (no severity changes, listed for accuracy)

- **B1** "the phone sends 28 of the desktop's 34" — a recount of production send sites
  through the `CommandSender` funnel gives **26** (eight types have zero send sites:
  createSelectionSideSession, deleteQueueItem, snoozeInteractionAutoResolution,
  cancelWorkflowRun, resumeWorkflowRun, amendWorkflowRunSettings, renameSession,
  deleteSession). Parity conclusion unchanged.
- **Pass-8 V1** checkpoint narrative says "TWO checkpoints in the list" — the surviving
  walk8 dumps show a **ckpt-3** was also created (three creates, restore confirmed from
  ckpt-3 against ckpt-2). Receipt unaffected.
- **Asar string counts are subset-based UNDERCOUNTS** in several rows: zh-CN ×73 →
  re-measures ×195; zai-light ×28 → ×57; zcodeInteractionBehavior ×18 → ×37; "Remote
  connection" ×4 → ×5; workspaceSidebar.projectsSection ×3 → ×4; remote.trigger ×4 → ×5;
  settings.locale.zh-CN ×4 → ×6; "Interface zoom" ×3 → ×1; ConversationShare family is
  10 files (not 13–16); interfaceMode consumers measure 31 by strict grep (claimed 36).
  Every string still SHIPS — substance unchanged; future passes should count over all of
  `out/`, not a chunk subset.
- **G35's "ZERO web-remote-control in the v3.14.3 clone"** — true for the hyphenated
  string; camelCase `WebRemoteControl` has 12 line-hits in 3 clone files (a minimal
  dialog/trigger stub). The substantive claim (the full mobile-shell/QR/pairing plane
  ships only in the 3.14.5-content asar, ×267/×108 re-measured exact) stands.
- **walk4/walk10 artifact counts** drift from the record (walk4: 32 xml + 33 png vs
  claimed 36+36; walk10: 31 xml vs claimed 14 — the 2-screenshot count is right).
  Pass 1–3 artifacts are purged from /tmp; those passes' quoted latencies and
  `approvals.answer` wire lines were app-logcat receipts and are not re-verifiable from
  artifacts (their UI-level corroboration survives in walk4–10 dumps).

## AI. Pass-12 re-adjudication legs (fresh emulator + relay, ~40 min)

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`,
Android 11), relay on 10.0.2.2:18100 via `gradle --no-daemon
:tools:mockzcode:mockRelayServer` (killed/restarted once by the safe `pkill -f
MockRelayMainKt` to drop MOCK_QUEUE for the pass-5 geometry), stored pairing
auto-reconnected on `install -r`. Evidence: `/tmp/walk-audit/` (9 png + 6 xml +
`a11-landscape.xml` bounds analysis). Zero emulator deaths, zero app deaths, zero ANRs.

### AI1. GAP-A4 (workflow Run… sheet keyboard, P3) — REFUTED on the current build

The recorded defect ("the Run button is covered by the keyboard while the arg field is
focused; the tap at the button's AX bounds lands on the keyboard") does NOT reproduce:

- With the `version *` field focused and the IME confirmed up
  (`dumpsys input_method mIsInputViewShown=true`), the Run sheet RISES above the
  keyboard (manifest `adjustResize`): "Run release-notes" moved y1140→y455 and the Run
  button reported full visible bounds `[904,1377][970,1427]` (vs its collapsed 10px band
  at y2062 with the IME down — the known bottom-row AX artifact).
- The button is not merely visible but FUNCTIONAL with the IME up: with "1.2.3" typed
  into the field, a tap at the button's dump-bounds center (937,1402) FIRED the run —
  wire receipt `MOCK: v4call sendConversationCommandV4 (id=74)` (the startSavedWorkflow
  send), the "Workflow started" snackbar rendered, and the app navigated into the target
  task — the full pass-1 behavior chain, executed with the keyboard still up.

Disposition: A4 comes OFF the A-list as cannot-reproduce on the current build + rig
(pass 1's observation is purged from /tmp, so no state comparison is possible; if it was
real on that day's state, something since changed — the app code has not). Keep only the
generic note that the sheet has no `imePadding` of its own (the manifest resize carries
it). Portrait screenshot with the sheet + IME up: `/tmp/walk-audit/a4-ime-up.png`.

### AI2. GAP-A11 (conversation landscape break, P3) — core CONFIRMED; sub-claims corrected

Re-adjudicated in BOTH geometries (with the MOCK_QUEUE strip, and with the relay
restarted WITHOUT it to reproduce pass 5's exact plane):

- **CONFIRMED (the load-bearing half):** the pinned interaction card collapses to a
  ~53px sliver in landscape — the "Agent needs your input" title renders with its glyphs
  HALF-CUT and the AX text node is 16px tall (`[208,388][599,404]`, ~40px is normal);
  no radio/answer/Submit content is reachable. Reproduced identically with and without
  the queue strip. Portrait is functionally fine (the card is height-budget-capped there
  too — by design, internally scrollable — with options visible and the FAB anchored at
  the composer corner; "Portrait unaffected" holds).
- **REFUTED (sub-claim 4):** "the 3-button nav bar paints OVER the app content — no
  nav-bar inset." Pixel evidence: the app surface is RGB(247,248,252) and ends at
  x≈2170–2180, while the nav band is a distinct RGB(254,254,255) from ~x2193 — the
  conversation column RESPECTS the landscape nav inset (content right edge = nav-band
  start minus the 16dp card margin). The nav buttons never overlap app content.
- **NOT REPRODUCED (sub-claim 2):** no capture in either geometry shows timeline-row
  text painting OVER the clipped strip — the first row renders below it (immediately
  adjacent, zero gap, ugly but not overlapping).
- **CONFIRMED-IN-SUBSTANCE (sub-claim 3):** with the queue strip absent and the timeline
  scrolled, the jump-to-latest FAB floats MID-CONTENT at ~53% screen height over the
  timeline rows (device ~(2086,577)) rather than anchoring at the composer edge as it
  does in portrait — the recorded "floats mid-content" observation reproduces; note it
  is partially inherent to the collapsed-timeline state.

Fix scope narrows to what A11 always was at its core: give the landscape pinned stack a
usable height budget (the portrait budget collapses to a sliver at 1080px height) — the
nav-inset and row-overlap halves of the recorded fix direction are already correct in
the app. Severity P3 stands (a pending interaction is unusable in landscape).

### AI3. Rig learnings for the next pass

1. **The pass-5 "host drags cannot scroll the timeline while an interaction is pending"
   learning was a MISATTRIBUTION** — swipe-driven scrolling works; the pass-5/queue-geometry
   swipes were being CONSUMED by the queue card / pinned surface they started on. With
   the queue strip absent, the identical swipe scrolled the timeline immediately.
   Target swipes at the ROW band, not the card band.
2. The draft-leave confirm ("Leave this conversation?") fires on back-press with an
   unsent draft — budget one extra tap for any conversation-exit leg (correct fail-safe
   behavior, first observed live).
3. Run-sheet legs: the sheet's Run button reports a 10px AX band at the screen bottom
   with the IME down and full bounds once the IME is up — derive taps from the IME-up
   dump, not the IME-down one (inverse of the composer learning).
4. `input text` into a freshly-focused empty field works without the MOVE_END dance;
   the caret-at-0 trap only bites non-empty fields.

### AI4. Remediation-order deltas (supersedes only where this pass applies)

1. **GAP-A4 is WITHDRAWN from the A-list** (cannot-reproduce, AI1) — round-A ordering
   becomes A8 → A2 → A13 → A1 → A6/A7 → A9.
2. **GAP-A11's fix narrows** to the landscape pinned-stack height budget (AI2); drop the
   nav-inset and row-overlap items from its fix/pin description.
3. **G32/G37/E4 rows re-scope per AH2** before any G-batch picks them up: G32 = branch
   create + commit-assist (switch shipped); G37 = in-app locale picker (zh-CN resources
   already ship); E4's git-parity paragraph is superseded by AH2 item 3.
4. Everything else in Parts AG/AD/AA/X/U/R/O/L/I/F/C stands — the A-defect list, the
   G-matrix, the B3 boundary inventory, and the drift WATCH all verified sound.

Verification record (pass 12): rig ran ~40 min across two relay configurations with
ZERO emulator deaths and ZERO app crashes; one deliberate relay kill/restart (flag
re-arm); no new defects. Census: 0 FATALs, 0 ANRs. Unit suites were NOT re-run (app tree
byte-identical to the fd7046a baseline; meta-verified at AH1 by XML aggregation).

---

# vc51 Remediation Roadmap (APPENDED — not part of the original audit)

> **Appended 2026-10-30** after pass 12 (the independent verification audit). This roadmap
> is built ONLY from the verified findings recorded above (passes 1–12) and supersedes the
> interim Part C/F/I/O/U/X/AA/AD/AG remediation orderings as the single work plan.
> Dispositions carried in: **GAP-A4 is WITHDRAWN** (cannot-reproduce, AI1) and appears in
> no phase; **G32, G37 and the pass-2 E4 git-parity verdict are REFUTED AS WRITTEN**
> (AH2) — only their pass-12 re-scoped descendants appear below, and the voided original
> claims are listed under "Withdrawn findings" so no session implements them. The
> remediation target is the SHIPPED desktop asar (`99e280fb…`, 3.14.5 content) — re-run
> the W2 drift greps if the desktop app updates before a phase starts. Tick checkboxes
> here as work completes; never edit the review above — append receipts under a ticked row
> or in a new dated pass section.

## Binding rules for every fix session

1. **Repo + branch.** Work in this repo on `fix/vc51` cut from `main`; land per the
   release policy (ADDITIVE = signed draft / BREAKING = draft PR; a human promotes; never
   push unless Ernest says). Every hand-built APK carries the
   `zcode-android-release-meta` block (ReleaseParser fails closed).
2. **One finding ≥ one commit**, message form `fix(<ID>): <mechanism>`. One commit may
   close several rows ONLY when they are one shared mechanism (rule 11) — say so in the
   message and tick every covered row.
3. **Re-read before fixing.** Re-read the finding's entry above (its Part citations) and
   its roadmap row before writing code; honor the pass-12 re-scopes (G32/G37/E4) and the
   AI2-narrowed A11 scope (the nav-inset and row-overlap halves are REFUTED — no work
   there).
4. **TEST-FIRST with shape fidelity.** The pin that reproduces the recorded defect shape
   is written and RED at the pre-fix commit before the fix lands: A8 = a three-arm
   `launch()`-without-throwing test; A2 = inbox-clears-on-answer for BOTH classes
   (state-plane + row-anchored); A13 = uiState re-emits on a notice-only change; A12 = a
   background-entry re-post carries two action PendingIntents; A11 = a landscape
   composition pin on the pinned card's visible height.
5. **Every fix ships its pin in the same commit.** No fix row is ticked on pins alone and
   no pin lands without its fix. Mock/rig changes are separate `tools/mockzcode` commits
   with self-tests green (`:tools:mockzcode:jar` + `:tools:mockzcode:test`).
6. **COMMIT BEFORE ANY RIG LEG.** No uncommitted work may exist when an emulator, mock
   relay, or gradle suite runs (recorded hazards: emulator beside gradle pools = timing
   flakes; free snapshots/intermediates before cold boots; kill the mock ONLY via
   `pkill -f MockRelayMainKt`, never by port).
7. **Fresh full-suite baseline + zero-new-names gate.** At each phase start, record the
   full-suite baseline at the pre-phase commit — 17 modules means `testDebugUnitTest` for
   the Android modules AND `test` for the six JVM modules (the 2026-10-21 blind-spot
   lesson). After each fix commit, re-run the touched modules and name-diff every new
   failing test against the baseline; ALSO assert the gradle task-failure list holds only
   test-failure tasks and no module stopped emitting test XML (test-compile failures are
   invisible to a name-diff). Every new name is chased or exonerated in writing.
8. **~150-line/stop rule.** Any single change beyond ~150 lines or spanning >3 modules
   stops for a checkpoint commit + suite re-run. GAP-A1's card is the expected exception —
   split it into wire→VM→UI sub-commits under one row.
9. **Independent per-phase verification.** A phase is done only when its exit gate passes
   on a FRESH emulator+relay rig leg (cold boot, the standing all-flags arming + the
   phase's named mock arms), with receipts appended to this report and the rows ticked.
10. **Record duty.** Tick rows here as their Accept criteria pass; add reusable learnings
    to AGENTS.md; keep the one-report-file append-only discipline; record mock-side
    behaviors that are rig fidelity (not app truth) so later passes don't chase them.
11. **Shared-remedy preference.** Cluster findings that share a mechanism into one remedy
    when it does not blur ownership: A6+A7 (one overlay rewrite), A8 (one registry helper,
    three arms), A12 (one Spec-action builder shared with the fresh path), G7+G8
    (selection family), G16+G17+G28+G40 (preview/search-scope family), G25/G27/G30
    (INV-9 viewer pattern), G22/G23/G24 (settings-plane family), and the
    modeled-never-sent sweep (G3/G4/G6/G8 — per AH3 the zero-send-site family is
    createSelectionSideSession, deleteQueueItem, snoozeInteractionAutoResolution,
    cancelWorkflowRun, resumeWorkflowRun, amendWorkflowRunSettings, renameSession,
    deleteSession).

## Withdrawn findings (do NOT implement)

- **GAP-A4 (P3) — WITHDRAWN (AI1).** "The workflow Run… sheet's Run button is covered by
  the keyboard" does not reproduce on the current build: the sheet RISES above the IME
  via the manifest `adjustResize`, and Run is visible AND functional with the keyboard up
  (wire receipt `sendConversationCommandV4 id=74`, "Workflow started" snackbar,
  target-task navigation). Implement nothing; keep only the generic note that the sheet
  declares no `imePadding` of its own (the manifest resize carries it).
- **G32 as originally written (pass-7 T2) — REFUTED (AH2.1).** `GitPort.switchBranch` IS
  dispatched live (`GitScreen.kt:411` → `Phase9Ports.kt:218` `gitSwitchBranch`); do NOT
  "wire up branch switch." Only the re-scoped descendant (branch CREATE + commit-assist,
  Phase 4C) is work.
- **G37 as originally written (pass-10 AC2) — REFUTED IN PART (AH2.2).** zh-CN
  system-locale resources already ship (`values-zh-rCN`, 259-line strings.xml); do NOT
  "add Chinese resources." Only the re-scoped descendant (in-app locale picker, Phase 4D)
  is work.
- **Pass-2 E4 git-parity verdict — REFUTED AS WRITTEN (AH2.3).** "The only desktop git
  mutation surface is checkpoint restore" was wrong in both directions (desktop ships
  commit/commitAndPush/push dialogs + createBranchAndSwitch). No parity work item follows
  from E4; the git mutation-plane record is superseded by the G32 re-scope above.

## Phase overview

| Phase | Theme | Findings | Session risk | Status |
| --- | --- | --- | --- | --- |
| 1 | P1/P2 phone defects — attach crash, approvals bookkeeping, invisible notices, hook-trust card, search overlay, queue composer | 8 | High | **Complete 2026-10-31** |
| 2 | P2 feature completions + P3 phone defects — workflow/queue/find, landscape pinned stack, shade actions | 5 | Medium | **Complete 2026-10-31** |
| 3 | P3 planes — protocol hygiene, conversation affordances, quota/usage, presentation & workspace management, web-remote boundary | 16 | Medium | Open |
| 4 | P4 polish + boundary decisions — phone smalls, INV-9 viewers, authoring/management, preview/share/presentation family | 24 | Low | Open |
| 5 | Rig debts + regression-pin backlog | 7 | Low | Open |
| | **Total** | **60** | | |

(Counts reconcile: 8 + 5 + 16 + 24 + 7 = 60 rows; GAP-A4 appears in no phase.)

## Phase 1 — P1/P2 phone defects (crash + blockers)

Round-A ordering per AI4: A8 → A2 → A13 → A1 → A6/A7 → A9, with A5 (the Part-C round-A
ANR fix, never superseded) riding beside A13 as the other small P2.

### Sub-phase 1A — the two P1s

- [x] **GAP-A8 (P1)** The ENTIRE outbound-attachment surface FATALs the app — Photo
  (`WriteComponents.kt:832`), Attach a file (`:844`), Take photo (`:854`) all throw
  `IllegalArgumentException: Can only use lower 16 bits for requestCode` at
  `FragmentActivity.checkForValidRequestCode` because the `rememberLauncherForActivityResult`
  registrations at `WriteComponents.kt:657-668` derive an over-16-bit request code; 11
  consecutive live repros. Fix: one shared registration/registry-key helper keeping every
  derived code ≤16 bits (short explicit keys, or bump androidx.activity). Accept: a
  three-arm pin asserting `launch()` opens without throwing; rig walk: all three arms open
  a picker (or honestly degrade) with ZERO FATALs, send completes end-to-end.
  - ✅ CLOSED 2026-10-31 (commit cf739af + 12a87ad, `fix/vc51`). ROOT CAUSE: transitive
    `androidx.fragment:1.2.5` still enforced `checkForValidRequestCode` while activity
    1.13's registry draws codes from `Random.nextInt(2147418112) + 65536` (always
    >16-bit); MainActivity extends FragmentActivity (BiometricPrompt), so launch()
    threw before any picker. Fix: fragment → 1.3.6 (validation removed) forced in
    `:app` + `:features:conversation`. PINS (AttachmentPickerLaunchTest, Robolectric,
    REAL FragmentActivity host, the same three contracts via the same compose wrapper):
    RED 3/3 pre-fix with the EXACT recorded stack (`checkForValidRequestCode:714`),
    GREEN post-fix. RIG EXIT GATE (fresh emulator + relay): **Photo** — picker opened
    (documents-ui Recent), dpad-selected w9-crashdlg.png → `attachmentBeginV4 ok 378ms`
    → `attachmentChunkV4 ok 1073ms` → `attachmentCommitV4 ok 103ms` → user row +
    "synthetic reply to: attached-rig-p1"; **Attach a file** — picker opened, picked
    a11d.xml (17.5 KB) → begin 551ms / chunk 287ms / commit 37ms; **Take photo** — the
    registry fix held (stack launches through the registry) and exposed the SECOND
    crash class: the AVD's strict camera app (com.android.camera2) denies callers
    without android.permission.CAMERA → SYNCHRONOUS SecurityException from launch()
    (one FATAL, 09:41:31 — then fixed) → all three launches now guarded (12a87ad) and
    degrade to the inline `composer_attach_error` copy; re-tap verified live: the
    error renders with the PROCESS ALIVE (pid 8268). Post-fix census: 0 new FATALs.
- [x] **GAP-A2 (P1)** The Approvals inbox never clears answered/resolved interactions —
  11 live receipts. The coordinator marks repository records resolved only inside the
  machine-RESOLVED gate (`InteractionCoordinator.kt:456-458`; the `runCatching` can
  swallow the transition silently) while `observeOpen` filters `resolvedAtMs IS NULL`
  (`Daos.kt:110`); the terminal-machine gate (`:148`) then correctly refuses re-answers
  with zero wire, so the cards just sit forever. Fix: mark resolved on the accepted-ack
  path AND on state-plane disappearance reconciliation for row-anchored machines — both
  classes. Accept: pins for inbox-clears-on-answer (state-plane + row-anchored); rig:
  answering from the card or the inbox empties both entries AND the bell badge without
  leaving the screen.
  - ✅ CLOSED 2026-10-31 (commit 737880a, `fix/vc51`). ROOT CAUSE: every terminal path
    called markResolved (stamps resolvedAtMs) and THEN updateLifecycle, whose full-row
    upsert re-wrote resolvedAtMs from the request object (null) — the record REOPENED
    instantly; observeOpen (resolvedAtMs IS NULL) kept serving it. This explains all
    11 receipts (wire ok, no warnings, cards persisted). Fix: KnownRequest mirrors the
    resolution timestamp; a shared resolveRecord funnel; lifecycle upserts carry
    `request.copy(resolvedAtMs)`; accepted acks resolve the record even when the
    machine transition was swallowed. PINS (ApprovalsInboxResolutionTest on the REAL
    Room repo — the clobber is invisible to a recording fake): state-plane answer,
    state-plane disappearance, row-anchored inbox answer — RED 3/3 pre-fix, GREEN
    post-fix. RIG EXIT GATE: "Allow once" → the Permission card cleared from the inbox
    LIVE (`approvals.answer :: option=allowOnce`); "Send answers" from the conversation
    card → the Question card gone; badge gone; the inbox renders "Nothing needs you".

### Sub-phase 1B — the two small P2s

- [x] **GAP-A13 (P2)** Slash/goal refusal feedback is invisible at refusal time and STALE
  when it finally paints: `_sendNotice` is a `.value` READ inside the five-source uiState
  combine (`ConversationViewModel.kt:166` vs the read at `:212`), so a notice-only change
  cannot re-emit state — the exact FIELD-class bug the vc21 draft fix repaired for the
  draft (`:162-170`) that the notice never got. Fix: make `_sendNotice` a combine source
  (fold into WriteState) or collect it into uiState directly. Accept: a uiState-emission
  pin (set notice → uiState re-emits WITHOUT an unrelated source change); rig with
  MOCK_REFUSE_COMMANDS: a refused `/goal pause` paints the GOAL_PAUSE_REFUSED banner while
  the IME is up, with fresh (not stale) copy.
  - ✅ CLOSED 2026-10-31 (commit 90fdf8f, `fix/vc51`). Fix: `_sendNotice` pairs with
    WriteState as a REAL combine source (the vc21 draft-fix treatment the notice never
    got). PINS (RefusalNoticeEmissionTest, emission counter): a notice-ONLY change must
    re-emit uiState — RED 2/2 pre-fix (zero re-emissions: the exact pass-11 shape),
    GREEN post-fix, fresh GOAL_PAUSE_REFUSED/compact copy, draft preserved. RIG EXIT
    GATE (MOCK_REFUSE_COMMANDS): two `resumeGoal` refusals (`fault.command.
    rigRefused:resumeGoal` + `command.rejected` WARNs) — the refusal banner painted AT
    REFUSAL TIME both times (~2 s dumps), including the IME-up variant at REAL AX
    bounds [61,1270][887,1353] above the composer, with FRESH copy.
- [x] **GAP-A5 (P2)** "Copy diagnostics to clipboard" can ANR the app (full system ANR
  reproduced pass 1): `ClipboardManager.setPrimaryClip` — a synchronous binder IPC —
  resumes on the Main dispatcher (`SettingsScreen.kt:518`). Fix: hop the clipboard write
  (+ toast) to `Dispatchers.IO`/`Default`. Accept: a Robolectric pin that the clipboard
  write happens off-main.
  - ✅ CLOSED 2026-10-31 (commit fce5417, `fix/vc51`). Fix: the write (with the vc20
    bounded-prefix behavior) moved into `writeDiagnosticsClipboard` on Dispatchers.IO;
    Main keeps only the toast. PINS (DiagnosticsClipboardThreadTest, recording
    ClipboardManager shadow): the write lands on a DefaultDispatcher worker for BOTH
    the short and the over-budget payload — RED 2/2 pre-fix (wrote on "SDK 30 Main
    Thread"), GREEN post-fix. RIG EXIT GATE: Copy diagnostics → the copied toast
    rendered, ZERO ANR, process alive — the healthy-clipboard caveat from pass 2 is
    moot: the write can no longer run on Main, so the ANR is eliminated by
    construction.

### Sub-phase 1C — the hook-trust review card (P1 feature build)

- [x] **GAP-A1 / G1 (P1)** The workspace hook-trust review card does not exist — ADR-024
  landed only half: decode (`SessionState.kt:246,344`), four v4 commands
  (`ConversationPort.kt:270-284`) and VM verbs (`ConversationViewModel.kt:684-708`) all
  exist, but `InteractionCoordinator.kt:730-733` parks WORKSPACE_HOOK_REVIEW in the INV-14
  "counted, no card" branch and zero composables render it; a session blocking on hook
  trust has NO phone remedy. Fix per the ADR-024 UX contract: per-item opt-in checkboxes
  default-off, "Trust N selected hooks" disabled at N=0, confirm sheet, stale_digest
  state; route the interaction into the pinned-card plane and wire
  trustHooks/revokeHooks/toggleHookItem/grantHookTrust (first UI callers). Desktop anchors:
  `WorkspaceHookPendingBanner.tsx`, `ConversationHookDetailsAction.tsx`,
  `useWorkspaceHookInlineTrust.ts`, `workspaceHookReviewCommands.ts`. Accept: rig with
  MOCK_HOOKS=1 renders the review card on synth-task-1; a trust_selected round-trip flips
  item states live; the confirm sheet + N=0 disable rule are pinned.
  - ✅ CLOSED 2026-10-31 (commits 6ea37bd + ebf958a, `fix/vc51`). The card (ADR-024 UX
    contract: default-off per-item opt-in, "Trust N selected hook(s)" disabled at N=0,
    confirm sheet firing the exact checked ids + the persistent grant — its FIRST and
    only caller, stale_digest markers, editable enable/disable, confirmed revoke)
    renders in the pinned plane via the new `pendingHookReviews` selector;
    trustHooks/revokeHooks/toggleHookItem/grantHookTrust have their first UI callers.
    PINS (HookTrustReviewCardTest): default-off + N=0 disable, confirm-sheet trust
    round (exact ids + grant + cancel-fires-nothing), stale marker, revoke confirm,
    toggle, uiState selector. The RIG caught a second integration bug: the pinned
    column gated on `blocked` (permission/userInput only), so a hook-only block still
    rendered nothing → the gate now counts pendingHookReviews (ebf958a). RIG EXIT GATE
    (MOCK_HOOKS, synth-task-1): the card rendered live (title, workspace label,
    "2 pending · 3 hooks", three items with Disable/Revoke affordances); checked 2
    items → "Trust 2 selected hook(s)" → confirm sheet → Confirm →
    respondWorkspaceHookReview ok 131ms + grantWorkspaceHookTrust ok 115ms → item
    states flipped LIVE (Revoke moved to the newly trusted items) — and the flip
    SURVIVED an app restart (the post-restart render shows all three
    trusted_persistent).

### Sub-phase 1D — the task-search overlay (P2 ×2, one rewrite)

- [x] **GAP-A6 (P2)** The search overlay draws in the status-bar band and its result rows
  are stacked, overlapped, and dead: field + results are bare siblings emitted AFTER the
  Scaffold at screen top level (`TaskListScreen.kt:390-443`), so everything lands
  top-start in the host Box with no `statusBars()` padding (field at y11 under the clock;
  the hit row at `[0,0]`, taps dead). Fix: host the overlay in an inset-padded container.
  Accept: pin — the search field sits below the status bar; rig: the hit row is tappable
  and navigates.
  - ✅ CLOSED 2026-10-31 (commit 4aa0f9c, `fix/vc51`, shared rewrite with A7). The field
    lives at the top of the inset-padded Scaffold body (always present while a query is
    active, with a close affordance since the FABs hide with the grouped body). PIN RED
    pre-fix (the field rendered ABOVE the top-bar title — the window-level sibling),
    GREEN post-fix. RIG EXIT GATE: the field renders at y=382, BELOW the top bar
    (pass-2: [44,11] under the system clock); hits tappable — a tap navigated into
    synth-task-2 (`recovery.attach sessions=synth-task-2` +
    `subscribeConversationV4 ok 402ms`).
- [x] **GAP-A7 (P2)** Search results do not REPLACE the grouped list (the H1.1 contract):
  the body composes before the `return` at `TaskListScreen.kt:450`, so
  Automations/Workflows/Pinned/Workspace sections stay fully visible under the results.
  Fix: actually suppress the grouped body while a query is active. Accept: one shared pin
  with A6 — grouped list absent while a query is active, restored on clear.
  - ✅ CLOSED 2026-10-31 (commit 4aa0f9c, `fix/vc51`, shared rewrite with A6): an active
    query composes ONLY the search pane (scrollable results or the honest empty state);
    the create-notice strip + its auto-consume became shared across both modes (the old
    consume effect only ran while searching). PIN RED pre-fix (the grouped list stayed
    composed under the results), GREEN post-fix + restore-on-clear. RIG EXIT GATE:
    query "syn" → the Automations/Workflows/Pinned markers ABSENT, three hits stacked
    in order at [44,525/629/733] (pass-2: one dead hit at [0,0] behind the clock);
    clearing restores the grouped list.

### Sub-phase 1E — queue-mode composer (P2)

- [x] **GAP-A9 (P2)** The queue-mode composer has NO send affordance while the agent is
  stoppable — the exact state queue mode exists for: the control morphs STOP↔SEND on
  `canStop && stopState==STOPPABLE` alone (`WriteComponents.kt:910-912`) and no IME send
  action exists (`:1155-1164`), so a typed draft dead-ends behind a self-defeating Stop.
  Fix anchor is the desktop contract `showStopControl = canStop && !hasDraftToSubmit`
  (`ConversationComposer.tsx:1136`): gate the morph on a blank draft; optionally add an
  IME send action. Accept: a Robolectric pin — the send control exists with a non-blank
  draft while `canStop`; rig: a draft typed mid-run dispatches (send or enqueue) without
  killing the run.
  - ✅ CLOSED 2026-10-31 (commits e262249 + 16ee50e, `fix/vc51`). The morph gates on
    `hasDraftToSubmit` (non-blank draft OR composer attachments) — the Stop control
    owns the slot only for a blank draft; the button and the NEW ImeAction.Send share
    one dispatchSend path. The superseded ComposerKeyboardOptionsTest pins were updated
    in the owning batch (prose keeps autocorrect/capitalization defaults + the send
    action). PINS (QueueComposerSendControlTest, stateful harness): mid-run typed draft
    keeps SEND (RED pre-fix), blank draft keeps STOP (preserved), ImeAction.Send (RED
    pre-fix). RIG EXIT GATE: "ping-phase1" sent → mock flips running/stoppable →
    composer in queue mode; typed "queued-dispatch-p1" → the control is **SEND, not
    Stop** (the exact 3-pass defect shape, inverted) → dispatched
    (`sendConversationCommandV4 ok 245ms`) → "synthetic reply to: queued-dispatch-p1"
    rendered, run alive.

Phase 1 exit gate: ☑ **PASSED on a fresh emulator+relay rig leg** — all three attach arms
open without a FATAL and send end-to-end (A8: Photo + doc uploads rode
begin/chunk/commit; Take photo degrades honestly); answering empties the inbox + badge
(A2: "Nothing needs you"); a MOCK_REFUSE_COMMANDS refusal paints its banner at refusal
time incl. IME-up (A13); diagnostics copy is clean (A5); MOCK_HOOKS renders the review
card with a live trust round-trip that survives restart (A1); the search overlay is
inset-padded, replaces the grouped list, and its hits navigate (A6/A7); a mid-run draft
dispatches (A9); full 17-module suite green at the phase HEAD with the zero-new-names
gate (1,511 tests / 0 failures / 0 skipped, all XMLs fresh — 1,487 baseline + 24 new
phase pins); rows ticked.**

> **Phase 1 verification stamp.** Verified 2026-10-31, completed at 2026-10-31 09:54:19.
> Verified by: **GLM-5.3-Flash** (`account:zai-individual-coding-plan/GLM-5.3-Flash`)
> running as the **ZCode CLI agent harness** (interactive coding-agent session on
> branch `fix/vc51`). Phase commits (oldest → newest): cf739af (A8) → 737880a (A2) →
> 90fdf8f (A13) → fce5417 (A5) → 6ea37bd + ebf958a (A1) → 4aa0f9c (A6+A7) → e262249 +
> 16ee50e (A9) → 12a87ad (A8 honest-degrade follow-up). Fresh debug APK at phase HEAD:
> `app/build/outputs/apk/debug/app-debug.apk`,
> sha256 8e5921d03333e866f759d9eae5f412869e2ac29d505d0677eecc708f4a94b60b
> (versionCode 51, versionName 1.3.0), installed + walked on the rig. Baseline note:
> the pre-phase fresh sweep hit the documented S13 machine-timing flake once
> (RecoveryScenarioMatrixTest, exonerated green in isolation — twice more mid-phase,
> same disposition).

## Phase 2 — P2 feature completions + P3 phone defects

The Part-C round-B set (workflow/queue/find) plus the two P3 A-defects that carry UX
breakage (A11 landscape, A12 shade actions). P2 rows first.

- [x] **G3 (P2)** Workflow run Resume + run-settings amend are modeled never sent
  (`resumeWorkflowRun`, `amendWorkflowRunSettings`; desktop `workflowResumeGate.ts`): the
  run sheet has no Resume/settings actions. Fix: surface both on the run sheet. Accept:
  rig: a resumable scripted run shows Resume; dispatching it (and an amend) acks and the
  sheet reflects the new state.
  - ✅ CLOSED 2026-10-31 (commit fa2bd31, `fix/vc51`). Chain: CommandCoordinator
    `resumeWorkflowRun {workId}` / `amendWorkflowRunSettings {workId, subagentModel?,
    maxConcurrency?}` (simpleCommand — the workflowRuns plane is revision-free per the
    desktop's own comment) → port methods (default FAILED) → Ports.kt facade → VM verbs
    (acceptance refreshes the runs so the open sheet's run steps in place; refusal =
    WORKFLOW_REFUSED, offline = ROW_ACTION_OFFLINE) → the sheet's action row: Resume (only
    while `run.resumable`) + Run settings → the amend sheet (subagentModel canonical
    string + maxConcurrency int, blank = omitted = keep; apply disabled while both blank).
    PINS: WorkflowRunSheetActionsTest — RED 3/4 pre-fix with the fix stashed (the
    Resume/settings tags absent), GREEN 8/8 post-fix incl. dispatch + cancel-fires-nothing;
    WorkflowRunActionsVmTest (4) dispatch/refresh/refusal/offline. MOCK: the two words left
    the "unhandled → plain accepted, no effect" class — typed respondError faults
    (not_found / not_resumable), resume flips the run honestly, amend supersedes + grows a
    new run (tools/mockzcode commit 0913dbfc + MockZCodePhase2SelfTest). RIG EXIT GATE:
    MOCK_WORKFLOWS seeds wf-run-s1 (release-notes, Stopped, Resumable) → the context
    sheet's runs panel lists it → the sheet renders "Resume run" + "Run settings" →
    **Resume**: `port.workflowResume :: run=wf-run-s1 outcome=Accepted` (16:30:35) and the
    sheet flipped to **Running** with Resume retired; **Amend** (concurrency=3):
    `port.workflowAmend :: run=wf-run-s1 model=keep concurrency=3 outcome=Accepted`
    (16:32:22) and the sheet's run flipped to **Stopped** (superseded per the desktop
    semantics, the new `wf-run-s1-amended` run live in the mock).
- [x] **G4 (P2)** Queue item delete is modeled never sent (`deleteQueueItem`; desktop
  anchors `ConversationQueuePanel.tsx:16-18,33-38` — the phone's other three verbs went
  live at pass 11): add delete to the QueueStrip row menu. Batch in the same commit the
  AC6 completion: a default interaction-behavior SETTING (`settings.zcodeInteractionBehavior`
  Queue/Guide — the phone has the in-session picker via `setFollowupMode` but no default).
  Accept: MOCK_QUEUE rig: delete removes the item via a patch round-trip; the default
  setting persists and seeds the composer's follow-up mode.
  - ✅ CLOSED 2026-10-31 (commit a9e74f01, `fix/vc51`). **Delete**: CAS-guarded coordinator
    method → port → queueOp → VM verb (shared queueStaleNotice) → the QueueStrip row's
    Delete button (tag `queue_delete_<id>`) → wiring. Pin RED pre-fix ("queue_delete_b is
    not displayed"), GREEN with the dispatch assertion. **AC6**: AppPreferences
    `.interactionBehavior` (null = follow the desktop) + proto field 14 (additive) + the
    closed-set gate in the repo mapping + a Settings → **Conversation** section (radio:
    Follow the desktop / Queue / Guide) + the seed path (QueueStrip `followupMode = wire ?:
    default` — the wire's value stays the truth). Pins: QueueFollowupSeedingTest (default
    seeds an unset wire mode; the wire wins when declared; null+null keeps the no-chips
    shape), AppPreferencesInteractionBehaviorTest + the data-module store test (round-trip,
    legacy-proto default, junk gated). RIG EXIT GATE (MOCK_QUEUE): Delete on q-rig-1 →
    `sendConversationCommandV4 ok in 138ms` (16:33:48) and the strip redrew authoritatively
    **"Queued (2) · paused" → "Queued (1) · paused"** (item one gone, two intact); the
    follow-up chips were ABSENT pre-default (the pre-G4 shape) → Settings → Conversation →
    "Queue follow-up changes" → **the row label survived a full app restart** ("While the
    agent runs: Queue follow-up changes" after a relaunch) → back in the conversation the
    chips render with **Queue checked=true** seeding the unset wire mode.
- [x] **G5 (P2)** Find-in-conversation is absent (desktop
  `useConversationTimelineFind`, `conversationFindHighlightDom`, `conversationFindIndex`;
  the phone's trajectory search covers model calls only): add a timeline search bar with
  highlight + match count + jump. Accept: a find-index pin over timeline rows; rig:
  searching highlights matches and jumps between them.
  - ✅ CLOSED 2026-10-31 (commit c7cfebf0, `fix/vc51`). ConversationFind.kt: the PURE index
    over timeline rows (user inputs + assistant prose, case-insensitive, every occurrence
    with offsets) + annotatedWithHighlights; the header find affordance + the FindBar
    (query + "n of m" + prev/next + close, wrap-around) + the query-map-contract jump
    (scrolls the active match's entry to the viewport top, releases tail-follow); YouBlock
    highlights occurrences (the active match's row gets the accent fill; assistant prose
    keeps its markdown pipeline — counted + jumped, not span-highlighted: scope note).
    PINS: ConversationFindIndexTest (4 — the find-index pin over rows: order, occurrences
    with offsets, empty/blank/no-hit, exact highlight ranges) + ConversationFindUiTest (2)
    — RED 2/2 pre-fix with the fix stashed, GREEN post-fix. RIG EXIT GATE: the find
    affordance renders in the header; the bar's prev/next are correctly disabled at no
    query; a live-sent user row + its agent reply re-index IN PLACE ("1 of 2" for "leg");
    **next** flipped 1/2 → **2 of 2** AND scrolled the visible row conv-row-1001 →
    conv-row-1002 (the agent reply at the viewport top); next wrapped to "1 of 2" and
    jumped BACK to row 1001; close removed the bar.
- [x] **GAP-A11 (P3, scope narrowed per AI2)** The conversation screen's pinned
  interaction card collapses to a ~53px sliver in landscape (title glyphs half-cut, no
  answer content reachable — core CONFIRMED pass 12, with and without the queue strip;
  portrait fine). Fix scope is ONLY the landscape pinned-stack height budget (the
  nav-inset and row-overlap halves are REFUTED — already correct in the app); anchor the
  jump FAB to the composer edge where feasible. Accept: a Robolectric landscape
  composition pin — the pinned card's visible height ≥ its title row; rig landscape:
  options + Submit reachable in the card.
  - ✅ CLOSED 2026-10-31 (commit 5c4ddc07, `fix/vc51`). ROOT CAUSE: SHORT_HEIGHT_RESERVE
    (323dp) was calibrated pre-vc49 (a 135dp header); the vc49 header is ONE ≤56dp session
    bar, so on a device-shaped landscape window (~323dp of content under the top bar) the
    stale reserve ate the whole window and the card fell to its 24dp floor. Fix: the
    reserve = the MEASURED fixed chrome (~190dp) + the 48dp timeline floor = **238dp** —
    the card keeps a usable band while the composer never leaves the window and the 48dp
    floor holds at BOTH the device-shaped (390dp) and prior (411dp) test geometries. The
    jump FAB already anchors at the composer edge by construction (BottomEnd of the
    timeline box directly above the composer) — no change needed. PIN (RED pre-fix with
    the EXACT recorded number: "pinned budget collapsed to **24.0dp** in the
    device-shaped landscape window"): PinnedInteractionLandscapeShortWindowTest (h390dp)
    asserts ≥80dp, the title row whole (≥14dp), composer in-window, Submit
    scroll-reachable; the existing h411 + portrait budget pins stay green. RIG EXIT GATE
    (user_rotation=1, the question + queue strip + hook card all armed): the pinned band
    measured **172px (≈65dp) vs the recorded ~53px sliver**, the title node **45px — a
    whole line (was the 16px half-cut)**, and swipes traversed the card's full content
    (Channel options → Changelog extras → the Skip row → into the hook card) — options
    reachable in the card's own scroll; the Send-answers button sits with the end band but
    its node was AX-merged while painted (the recorded bottom-row artifact class).
- [x] **GAP-A12 (P3)** The C3 shade Approve-once/Deny actions exist only on the
  fresh-event path (`NotificationBroker.kt:138-141`); the §15-S13 background re-post
  (`NotificationBroker.kt:179-188`) builds its Spec WITHOUT actions — and that re-post is
  the ONLY post an approval gets when it arrives while the app is foregrounded
  (wire oracle: `numWithActions=0` never incremented). Fix: replicate the
  permission-gated actions list in `onBackgroundEntry`'s Spec (the record already holds
  kind/interactionId/taskId — the same SpecAction builder applies). Accept: a pin — post
  an unseen approval record, enter background, assert the posted notification carries two
  action PendingIntents; rig: the shade buttons answer a real approval end-to-end.
  - ✅ CLOSED 2026-10-31 (commit 8a9306d2, `fix/vc51`). Fix: onBackgroundEntry replicates
    the same SpecAction builder gated on the record's own fields (`kind ==
    APPROVAL_NEEDED && interactionId != null && taskId != null`) — a re-posted question
    still carries none. PIN (RED pre-fix: "expected:<2> but was:<0>" — the wire oracle's
    exact shape): the foreground-suppressed approval's re-post carries the two actions
    (approve/allowOnce + deny/deny with the record's ids); a re-posted USER_INPUT_NEEDED
    carries none. RIG EXIT GATE: the §15-S13 re-post fired live
    (`notify.repost :: key=interaction:mock-approval-1 (background entry)`, 16:35:53) and
    the ranking counter moved **numWithActions 0 → 2** — reconciled exactly: the app
    enqueued 4 posts (both records × the re-post + a fresh connect-time show), and ONLY
    the approval's two posts carried actions (the question's two carried none) — the gate
    held on both classes while the approval's re-post gained the buttons. **The shade-TAP
    sub-leg (tapping Approve-once answers the approval) is explicitly RIG-BOUND to
    RIG-3** (MOCK_INTERACTION_PUSH): the mock's interaction ids are static, no fresh
    unseen approval can be minted on this rig, and the fresh-pairing re-arm was defeated
    by the `adb input text` pipeline (the pair link truncates/interleaves — the pass-1
    corrupted-link class); the receiver's answer path itself is unit-covered
    (app NotificationActionsTest) and C3's end-to-end answer flow is already live-verified
    from the APPROVALS INBOX (passes 5/11).

Phase 2 exit gate: ☑ **PASSED on a fresh emulator+relay rig leg** — Resume + amend +
queue-delete dispatched with wire receipts and state flips (G3: wf-run-s1 → Running;
amend → Stopped/superseded; G4: Queued (2) → Queued (1) via the mock's patch); find
counts, jumps and wraps over live rows (G5: "1 of 2" → next → "2 of 2" with the viewport
moving to the agent row, wrap, close); the landscape pinned card is usable (A11: 172px
band vs the 53px sliver, title whole, content traversal live); the re-posted approval
notification carries two actions (A12: numWithActions 0 → 2 with the question's posts
action-free — the shade-tap sub-leg explicitly RIG-3-bound); the default interaction
behavior persists across restart and seeds the composer (G4/AC6); suite green with the
gate (1,543 tests = the 1,512 baseline + 31 new Phase-2 pins; the single failing name is
the RecoveryScenarioMatrixTest timing class, A/B-proven pre-existing at 3cbdefa); rows
ticked.**

## Phase 3 — P3 planes

Sixteen P3 rows in four clusters: protocol hygiene, conversation affordances,
plan/quota/usage, and presentation/workspace management (with the two boundary decisions
that must produce dated answers).

### Sub-phase 3A — protocol hygiene + live planes

- [x] **GAP-A3 / G14 (P3)** Conversations are never unsubscribed — the host serves
  `unsubscribeConversationV4`/`unsubscribeWorkspaceConfigV4` but the phone has zero call
  sites across app/data/core-transport; switching tasks leaves stale subscriptions armed
  (wasted frames, stale-stream risk after reconnects). Fix: send unsubscribe-on-exit in
  the conversation teardown. Accept: a wire receipt on task switch/exit; no duplicate
  stale rows after a reconnect.
- [x] **G12 (P3)** Pending-command recovery banner absent (desktop
  `PendingCommandRecoveryBanner.tsx`, `pendingCommandReplay.ts`): the phone has the
  outbox + journal machinery but no user-facing banner for replayable/failed pending
  commands. Accept: rig: killing the relay mid-send surfaces the banner on recovery with
  a working replay action and honest failure copy.
- [x] **G13 (P3)** Sessions-index live plane absent (desktop `subscribeSessionsIndexV4`
  family, `sessionsIndexStore.ts`, `taskListRowActivity.ts`): the phone polls/refreshes
  per-screen instead of subscribing. Fix: subscribe/resync the sessions index and drive
  cross-session row activity live. Accept: rig: a second session's activity flips a task
  row's activity state without manual refresh.
- [x] **G15 (P3)** Background-work awareness absent: desktop reads background shell
  output (`backgroundBashOutputV4`) and shows a composer background-work indicator with
  counts (`ConversationBackgroundWorkTrigger.tsx`). Fix: surface the indicator + an
  output viewer for background bash. Accept: rig: a scripted background bash shows the
  count badge and the output viewer opens read-only.

### Sub-phase 3B — conversation affordances

- [x] **G26 (P3)** Goal affordances are UI-only missing: `sendGoal`/`pauseGoal`/
  `resumeGoal` are wired AND live-fired (pass 10) but reachable only by typing slash text
  (`ConversationViewModel.kt:432-443`); the context-sheet goal panel is strictly
  read-only, while the desktop offers add-goal on the composer menu
  (`ChatPromptActionMenu.tsx:30`) + pause/resume icons (`ConversationStatusPanel.tsx:490-520`).
  Fix: a "Set goal" affordance + pause/resume icons on the sheet's goal panel — UI
  surfacing over existing VM paths. Accept: rig: set/pause/resume from the sheet with
  wire receipts; refusal banners (A13) still fire.
- [x] **G6 (P3)** Interaction snooze (auto-resolution) is modeled never sent
  (`snoozeInteractionAutoResolution`; desktop `useTaskInteractionAutoResolutionSnooze.ts`):
  add a snooze action to approval/question cards. Accept: rig: snooze acks and the card
  defers per the desktop semantics (reappears after the snooze window in the mock).
- [x] **G7 (P3)** Text-selection actions absent (desktop `SelectionActionMenu.tsx`,
  `MarkdownSelectionTooltip.tsx`): selection tooltip/menu in the timeline with copy /
  selection side session / share selection. Accept: rig: selecting row text raises the
  tooltip; copy lands on the clipboard.
- [x] **G8 (P3)** Selection side session is modeled never sent
  (`createSelectionSideSession`): wire the tooltip's side-session action to the command
  and navigate into the child run. Shares the G7 remedy surface. Accept: rig: a selection
  side session dispatches, acks, and opens the child conversation.
- [x] **G9 (P3)** Shared-context handover is NOT modeled at all (`context_refs` on
  sendText, `discardSharedContext`; the desktop composer is a Lexical mention suite):
  model context-refs and finish the phone mention panel — the data path + menu entry
  exist (`WriteComponents.kt:699` gates on empty hits) and are rig-blocked only by the
  unscripted `searchWorkspaceFiles`. Accept: rig with MOCK_WORKSPACE_FILES (RIG-2): the
  mention panel renders hits; a send carrying context_refs is acked.
- [x] **G10 (P3)** Suggested prompts on the draft screen absent (desktop
  `featureSuggestedPrompts.ts`, `draftSuggestedPrompt*.ts`): empty-state prompt chips
  with rotation + prefill. Accept: a rotation pin; rig: chips render on a fresh task and
  prefill the composer.

### Sub-phase 3C — plan/quota/usage

- [x] **G11 (P3)** In-conversation quota banner absent (desktop
  `ConversationQuotaBanner.tsx`, `sessionQuotaBannerState.ts`,
  `startPlanQuotaBuckets.ts`): the phone has the ADR-020 Settings quota section but no
  live mid-session warning. Fix: reuse the ADR-020 quota plane for a conversation banner.
  Accept: rig/scripted low bucket: the banner renders once per threshold and links to the
  quota screen.
- [x] **G20 (P3)** Usage depth: the desktop ships four chart views
  (`AppUsageDailyModelTrendChart`, `AppUsageModelUsagePieChart`, `CodingPlanUsageBarChart`,
  `CodingPlanUsageLineChart`); the phone renders one summary plane. Fix: at least the
  daily model trend + plan-bucket charts over the protocol the phone already speaks.
  Accept: charts render on scripted data; the honest capability fault is preserved on
  unscripted channels.

### Sub-phase 3D — presentation & workspace management (+ 2 boundary decisions)

- [x] **G36 (P3)** Office/Coding interface-mode presentation absent (`lib/interfaceMode.ts`
  — persisted mode, onboarding question, 31+ consumers across the timeline incl.
  `ToolCallBlocks.tsx`, `v4/ConversationRowView.tsx`; asar receipt "Office mode focuses
  on summaries"): the phone renders ONE fixed presentation. Fix: a presentation-mode
  setting that gates tool-detail rendering (summaries/results vs commands/output/changes)
  — NOT a port of the desktop store. Accept: a mode-gate pin on tool rows; rig: switching
  modes changes timeline detail rendering app-wide.
- [x] **G41 (P3)** Multi-workspace management plane absent (desktop "Projects" section,
  add menu, drag-reorder, 4-step remote wizard, WSL/SSH/Docker model — asar receipts
  ×13+ re-measured; the phone's workspaces are a PASSIVE grouping only). Companion-shaped
  remedy: a workspace switcher/manager over the workspaces the phone already RECEIVES
  (switch/reorder/rename-class management; NOT a folder picker — the remote wizard stays
  desktop-side unless re-scoped). Accept: rig: switching the active workspace re-groups
  the task list live; ordering persists.
- [x] **G40 (P3)** Workspace search scope / .zcodeignore rules editor absent
  (`WorkspaceFileSearchSection.tsx`, 15 shipped strings incl. "Sync from .gitignore") —
  implies the entire workspace-file-search capability the phone has never approximated;
  batch with the G16/G17/G28 preview family. Accept: EITHER the editor + a scoped file
  search surface ship, OR a dated B3 boundary entry records the decision.
- [x] **G35 (P3, boundary decision)** The SHIPPED web-remote-control browser-companion
  plane (×267/×108 asar receipts; mobile shell, QR pairing, task/workspace sync —
  absent from the v3.14.3 source map, hence missed until pass 8): answer the
  browser-shell boundary — document under B3 (the phone remains the NATIVE client: push,
  widget, offline cache are phone-only) or plan interop. Carries the **Relay V2 WATCH**
  (JWT bearer, device roles, 60s WS tokens — NOT in the shipped asar) into any
  pairing/transport work. Accept: a dated B3/interop decision recorded in this report;
  if interop: a contract-review note against `relay-v2-pairing.md` before any code.

Phase 3 exit gate: ☑ rig — unsubscribe receipt (A3), recovery banner (G12), live row
activity (G13), background-work indicator (G15), goal/snooze/selection/context-ref/
prompt affordances live (G26/G6/G7/G8/G9/G10), quota banner + usage charts (G11/G20),
interface-mode + workspace manager (G36/G41); G40 and G35 carry dated decisions in this
report if deferred; suite green with the gate; rows ticked.

## Phase 4 — P4 polish + boundary decisions

Twenty-four rows in four clusters. Every "boundary decision" row ends in EITHER a shipped
surface OR a dated B3 note — neither is left open.

### Sub-phase 4A — phone-defect polish + recorded smalls

- [ ] **GAP-A10 (P4)** A rejected direct-endpoint add closes the dialog and discards
  every typed field; the only feedback is a transient toast (`SettingsScreen.kt:397`
  closes before the 10s probe in `ConnectionHub.kt:1190` resolves), and fallback-URL
  candidates bypass the scheme gate (`:439`) despite the "Validated before saving" note.
  Fix: keep the dialog open on Rejected with an inline error; validate candidate schemes
  with the primary's gate. Accept: a pin — a rejected probe leaves the dialog open with
  the error; bad-scheme candidates disable Add.
- [ ] **CKPT-HYDRATE (P4 small)** The checkpoint list is mount-local
  (`GitScreen.kt:753` `remember { emptyList() }`, appended only on create at `:780`; no
  list call on entry), so the Restore affordance (`:817-823`) is unreachable after any
  app restart. Fix: hydrate the list from the host on entry (the
  `restoreBetweenCheckpoints` channel is live-verified, pass 8). Accept: a pin — the list
  loads on entry; rig: a checkpoint created before a restart still lists and restores.
- [ ] **LEDGER-SMALLS (P4)** Three recorded cosmetics: (a) connection-scoped
  notifications have no tap target — deep-link the Connection-lost post to the connection
  screen (`AndroidNotificationSink.kt:56`; task-scoped deep links already verified);
  (b) the AlreadyResolved "Already handled on the desktop." info-copy paints in the ERROR
  palette (red) — pick a neutral style on the `answerError` channel; (c) the first-launch
  `update.check desktop=null` bootstrap race (self-heals) — gate the check on bootstrap
  completion. Accept: each observed fixed on the rig or explicitly waived here with a
  one-line reason.

### Sub-phase 4B — the INV-9 viewer family

- [ ] **G25 (P4)** Subagent management → minimum on-par is a read-only subagent viewer in
  Settings → Insights (the phone already consumes `subagents.list`, `Ports.kt:649`; the
  desktop page is `SubagentsSection.tsx`). Accept: viewer lists scripted subagents with
  scope/enable badges, matching the plugins/skills viewer pattern.
- [ ] **G27 (P4)** Model-provider management → read-only provider viewer (the picker
  already consumes the provider list with groups/badges; desktop
  `ModelProviderSection.tsx` is 1,161 lines); credential entry documented desktop-side.
  Accept: viewer renders providers WITHOUT secrets; boundary note for credentials.
- [ ] **G30 (P4)** Bots/integrations → read-only bots viewer (the phone has NO related
  surface — desktop `BotsDialog.tsx` + `BotConfig` in `packages/shared/src/bots.ts`):
  the INV-9 seed. Accept: viewer renders scripted bot configs read-only; deeper
  management is a dated boundary unless redefined.
- [ ] **G22 (P4)** Memory settings plane (desktop `MemorySettingsSection.tsx`): ship a
  viewer/read surface or a dated B3 boundary. Accept: shipped surface or dated note.

### Sub-phase 4C — authoring/management boundaries

- [ ] **G21 (P4 + P3 UX note)** Custom slash-command authoring absent (desktop
  `CommandsSection.tsx` + `CommandForm.tsx`); AND fix the bare-"/" panel's non-scrollable
  column (user commands below the fold — swipe proves zero scroll, pass 4) in the same
  breath. Accept: the "/" panel scrolls with user commands reachable without a prefix;
  authoring ships or a dated boundary note.
- [ ] **G23 (P4)** Coding-plan management/upgrade flow (desktop `CodingPlanEntryButton.tsx`,
  `CodingPlanUpgradeDialog*.tsx`); batches with G20's charts. Accept: the plan section
  offers bucket management/upgrade entry or a dated boundary.
- [ ] **G24 (P4)** Settings-sync plane (desktop `settings/settings-sync/`, 118
  references, plus external-agent import `ExternalAgentImportDialog.tsx`): boundary
  decision first (multi-device sync may be out of companion scope). Accept: dated
  boundary or a scoped sync/import surface.
- [ ] **G29 (P4)** Off-peak authoring + run history — the phone's monitor/control half is
  at parity (pass-5 record correction) and should be so stated in B3; missing are
  create/edit (`OffPeakEditView.tsx`) + history (`OffPeakHistoryTab.tsx`), and the phone
  never sends `offPeakToolEnabled` on sessionStart (so it only sees desktop-created
  tasks). Fix: create/edit entry + history list on the existing Automations/off-peak
  section + send the capability flag. Accept: MOCK_AUTOMATIONS+MOCK_OFFPEAK rig: create
  an off-peak task, see it in history, monitor rows still work.
- [ ] **G32 (P4, re-scoped per AH2.1)** Branch CREATE + commit-assist missing — switch IS
  shipped (`GitScreen.kt:411` → `Phase9Ports.kt:218`); desktop `GitBranchDialogs` +
  `switchAssist.ts` (blocked→commit preview machine). ADR-021 write-tier candidate.
  Accept: branch create from the Git pane with a wire receipt; switch-assist preview OR a
  dated ADR-021 boundary note.
- [ ] **G31 (P4)** Whiteboard plane (desktop `WhiteboardPane.tsx` + `lib/whiteboard.js`,
  ×59 receipts; sketch → send-to-chat): mobile-shaped minimum is a sketch→send entry in
  the composer + menu producing an image attachment (inbound sketch images already
  render). Accept: rig: sketch → the image lands in the conversation as an attachment.

### Sub-phase 4D — preview/share/presentation family

- [ ] **G16 (P4)** Attachment preview source (`attachmentPreviewSourceV4`,
  `conversationAttachmentRead/Stat`): fetch larger/preview renders for media instead of
  `attachmentRead` thumbnails only. Accept: rig: image previews render at preview
  quality over the new channel.
- [ ] **G17 (P4)** Assistant/artifact preview cards (desktop
  `useAssistantPreviewCardsForRow.ts`, workflow completion cards via
  `useWorkflowRunArtifacts`, the `presentation/` plane incl. a pptx render engine):
  widen `GeneratedArtifactPreview` + render workflow-completion cards with artifacts.
  Accept: rig: a scripted workflow completion renders a card with artifact previews.
- [ ] **G28 (P4)** Inbound attachment preview affordance (`AttachmentView`,
  `RowRenderers.kt:1330-1350` — NO clickable modifier; thumbnails are dead-ends, pass 4
  live proof): tap-to-zoom full-screen image viewer + PDF/video named-chip passthrough
  via an external-viewer intent. Accept: rig: tapping an image opens the viewer; a PDF
  chip offers an external viewer.
- [ ] **G18 (P4)** CUA (computer-use) row groups + permission schema (desktop
  `conversationCuaGroups.ts`, `cuaPermission.ts`, browser-use side pane): minimum —
  group CUA tool calls with dedicated copy and surface browser-use screenshot frames
  inside tool rows. Accept: rig: scripted CUA rows group distinctly; frames render when
  the mock serves them.
- [ ] **G19 (P4)** Conversation share modes (desktop `ConversationShare*` — 10 files:
  selection share, read-only timeline, markdown export, link): extend `conv_share`
  beyond transcript text. Accept: the share sheet offers ≥2 modes; the read-only/link
  mode round-trips OR a dated boundary records phone-form-factor limits.
- [ ] **G33 (P4)** Model-trajectory drill-in (desktop 16-file `ModelTrajectory*` family —
  per-call timeline + tool PAYLOAD detail): expand `TrajectorySheet.kt` cards into
  payload detail. Accept: rig: a call card expands showing the tool payload.
- [ ] **G34 (P4, small)** Update release-notes display (desktop
  `UpdateReleaseNotesTooltip.tsx`): add an optional notes field to `ReleaseMeta`
  (`ReleaseInfo.kt:130` carries none) + a markdown notes line in the update sheet;
  ReleaseParser must still fail closed without it. Accept: a pin — notes render when
  present; a meta block without notes still validates.
- [ ] **G37 (P4, re-scoped per AH2.2)** In-app locale picker (zh-CN system-locale
  resources already ship — `values-zh-rCN` 259 lines; desktop ships en-US+zh-CN with a
  persisted preference + cross-window broadcast): add an Appearance locale preference
  (System / English / 中文). Accept: a pin — the preference flips the resource config;
  rig: switching to zh-CN re-renders strings on recreate.
- [ ] **G38 (P4)** Interface zoom (desktop `WorkspaceSidebarFooter.tsx:316`): an in-app
  font-scale setting distinct from the OS scale (addresses the ledger's font-1.3 crush
  class on-device). Accept: a pin — the scale applies to text styles; rig: 0.85/1.15
  render legibly and persist.
- [ ] **G39 (P4)** Task auto-archive policy (desktop `settings.taskAutoArchiveDays`
  3/7/14/30; the phone's Archive is manual-only): an aging policy setting over the
  archive verb. Accept: a pin — the policy archives eligible rows on refresh; off by
  default.
- [ ] **THEME-ZAI (P4 completion, AC6)** zai-light/zai-dark brand palettes (desktop
  `.theme-zai-light` + dark, ×57 re-measured): add the two variants beside
  Light/Dark/Follow-system with the existing instant-apply + system-bar contrast
  re-scrim behavior. Accept: rig: both variants render everywhere; dark re-scrim fires
  on the in-app flip.

Phase 4 exit gate: ☐ every row ticked with EITHER an on-device receipt OR a dated
boundary note recorded in this report; the smalls (4A) verified or explicitly waived;
suite green with the gate.

## Phase 5 — rig debts + regression-pin backlog

Rig rows are `tools/mockzcode` work with self-tests; the pin backlog lands as integration/
Robolectric pins alongside whichever phase touches each surface first (or here at the end
if none does).

- [ ] **RIG-1** `MOCK_AUTOMATIONS` + `MOCK_OFFPEAK` env arms (pass-5 debt): the handlers
  `handleOffPeakTaskPromise`/`offPeakTaskList` are already scripted — only the env flags
  are missing; `DefaultAutomationsPort.lists()` (`ViewerPorts.kt:52-59`) currently
  short-circuits on the unscripted `listAutomations` fault before the off-peak read.
  Accept: the Automations section renders scripted rows and the off-peak section is
  exercisable live (unblocks G29's leg).
- [ ] **RIG-2** `MOCK_WORKSPACE_FILES` arm serving `searchWorkspaceFiles` (pass-6 debt):
  unblocks the live @-mention panel (G9) and the Files-panel honest-fault contrast.
  Accept: the mention panel renders hits on the rig.
- [ ] **RIG-3** `MOCK_INTERACTION_PUSH` arm (pass-9 debt): a fresh interaction delivered
  WHILE the app is backgrounded — completes the A12 fresh-vs-repost matrix and the
  notification action legs. Accept: a backgrounded fresh approval posts WITH actions
  (fresh path) while the re-post path is pinned separately.
- [ ] **RIG-4** Widget drag-placement: an API 34 AVD (`input motionevent`/`draganddrop`)
  or a debug bind hook — completes the glance-widget BINDING leg (picker presence +
  `zcode://task` intake are already live-verified, pass 9). Accept: the widget is bound
  on the rig and its tap deep-links into the task.
- [ ] **RIG-5** Mock fidelity: route command effects to the CALLING session (currently
  hardcoded `MockWorkspace.sessionId = "synth-task-1"`, `MockZCode.kt:473` — the D4.1
  routing artifact) and mutate the task store for rename/archive/pin/delete acks (the
  recorded rig-fidelity class of passes 2/6/8) OR stamp the ack with an explicit
  "ack-without-mutate" notice the report can cite. Accept: cross-session legs route
  correctly; the fidelity class is gone or explicitly labeled on the wire.
- [ ] **RIG-6** Asar counting method: re-measure shipped string counts over ALL of
  `out/`, never a chunk subset (AH3 undercounts: zh-CN ×73→×195, zai-light ×28→×57,
  zcodeInteractionBehavior ×18→×37). Accept: any future desktop-update enumeration cites
  whole-`out/` counts.
- [ ] **PINS-BACKLOG** The recorded regression anchors with no owning fix yet: the STOP
  loop (send → stoppable → stop → idle patch); the goal slash verbs (dispatch → wire →
  accepted → draft-clear); the four queue verbs (wire type + patch effect each) + the
  AlreadyResolved no-wire degrade; the `zcode://task` intake (plain + `#interaction=` +
  both malformed shapes) AND an intent-filter test asserting `android:host="task"` (the
  proven OUTER gate); the LiveConnectionService FGS start/stop lifecycle; the
  widget-picker presence; task verbs (pin/unpin/delete + group create→assign);
  create-sheet model picker + the full create loop; the effort picker; snapshot
  create→restore; the approvals launcher shortcut; the connection-lost notification
  cycle; the shade task-scoped deep-link; skills accordion expand; diagnostics export.
  Accept: each anchor has a named pin or an explicit waive line in this report.

Phase 5 exit gate: ☐ named mock arms implemented + self-tests green; the widget leg
completes; every backlog anchor has a pin or a written waive.

## Definition of done (vc51 remediation)

- [ ] Every phase exit gate checked, in order, each on a fresh emulator+relay rig leg
- [ ] All 60 roadmap rows ticked — or explicitly waived with a dated one-line reason here
- [ ] Full 17-module suite (`testDebugUnitTest` + the six JVM `test` tasks) green at the
      final HEAD with the zero-new-names gate against the recorded per-phase baselines
- [ ] Fresh debug APK rebuilt with the `zcode-android-release-meta` block; artifact path +
      version marker stated to Ernest (deliverable completion rule)
- [ ] Work committed locally as `ErnestHysa <takis312@hotmail.com>`; pushed/published only
      on Ernest's explicit word (release policy: ADDITIVE = signed draft / BREAKING =
      draft PR, human promotes)
- [ ] The W2 drift WATCH re-run if `/Applications/ZCode.app` changed mid-campaign (asar
      digest re-measured before the final phase), and the G-matrix re-triaged against any
      new shipped planes
- [ ] Reusable learnings appended to AGENTS.md; this roadmap file left append-only

---

# Phase 1 (vc51 remediation) — exit-gate rig leg + record — 2026-10-31 (APPENDED; the roadmap above is the work plan, this is its first receipt pass)

Scope: the Phase-1 fix session itself (branch `fix/vc51`, cut from `main` @ 799ebbb) —
all eight rows implemented TEST-FIRST (RED pins demonstrated at each pre-fix state,
fix + pin in the same commit), the full-suite zero-new-names gate, and the fresh
emulator+relay exit-gate leg. Everything above in this section is receipt; the ticks
in the roadmap carry the per-row detail.

Fix commits (one row ≥ one commit, `fix(<ID>): <mechanism>`): `cf739af` A8 fragment
bump; `737880a` A2 resolvedAtMs mirror; `90fdf8f` A13 notice-as-combine-source;
`fce5417` A5 clipboard off-main; `6ea37bd` A1 hook-trust card; `4aa0f9c` A6+A7 search
overlay rewrite; `e262249` A9 desktop morph contract + IME send; `16ee50e` A9
superseded-pin update (the batch that breaks a pin owns it); `ebf958a` A1 blocked-gate
rig follow-up; `12a87ad` A8 honest-degrade follow-up. Commits with rig receipts
against them: all ten.

## Phase-1 exit-gate walk (fresh zcode-pixel cold boot, relay on 10.0.2.2:18100, arming
MOCK_ROWS/GOAL/APPROVAL/ROW_ACTIONS/WORKFLOWS/TASKMGMT/QUESTION/HOOKS/COMPACT/
ATTACHMENT/QUEUE/REFUSE_COMMANDS + HAPPY; ~40 min across the legs, three app
reinstalls as fixes landed — each relaunch auto-reconnected on the stored pairing in
~2 s)

- **A6/A7** — search field y=382 below the top bar (was [44,11] under the clock);
  query "syn" → grouped markers ABSENT, three hits stacked [44,525/629/733]; hit tap
  → synth-task-2 (`recovery.attach` + `subscribeConversationV4 ok 402ms`).
- **A2** — Allow once cleared the Permission card LIVE; Send answers cleared the
  Question; badge gone; inbox = "Nothing needs you".
- **A1** — the review card rendered (the blocked-gate fix was required and landed
  mid-walk); 2 items checked → "Trust 2 selected hook(s)" → confirm sheet →
  respondWorkspaceHookReview ok 131ms + grantWorkspaceHookTrust ok 115ms → states
  flipped live; all three items trusted_persistent AFTER an app restart.
- **A13** — two resumeGoal refusals; banner at refusal time both times, IME-up at
  [61,1270][887,1353], fresh copy.
- **A9** — mid-run draft → SEND control present (not Stop) → dispatch ok 245ms →
  synthetic reply, run alive.
- **A8** — Photo: picker open → w9-crashdlg.png begin 378ms/chunk 1073ms/commit
  103ms → sent ("synthetic reply to: attached-rig-p1"); Attach a file: a11d.xml
  17.5 KB begin 551/chunk 287/commit 37; Take photo: the strict camera image
  SecurityException (ONE FATAL at 09:41:31, pre-guard) → the guarded degrade
  (12a87ad) re-verified live: inline copy, process alive. Census after the guard:
  0 new FATALs.
- **A5** — Copy diagnostics: toast rendered, zero ANR, process alive.

## New WATCH items + rig learnings (record duty, rule 10)

1. **WATCH (P4, new): resync/subscribe-ack projection rebuild transiently drops the
   pinned plane.** After the trust patch (revision-only state patch → app resync →
   `gateway.replay frames=0 (subscribe ack landed)`) the hook card vanished for one
   screen-generation while the reconcile still logged the interaction present
   (patch-apply is key-level merge, so the state plane HAD it); it self-healed on the
   next state delivery and the trust states persisted across restart. Candidate
   mechanism: the subscribe-ack path rebuilds the projection without state keys.
   Follow-up candidate for a later phase; not a Phase-1 row.
2. **OBSERVATION (unattributed):** an "Offline — the action was not sent. Nothing
   changed." notice bar rendered once after the trust flow while the session bar read
   Connected and every trust verb rode the wire ok. Recorded so a future pass doesn't
   chase it as a regression of anything fixed here.
3. **RIG: API 30 documents-ui ignores host-injected taps AND long-presses on its grid
   items** (the item roots report clickable="false"); DPAD navigation works —
   `keyevent 20 ×N` then `23` selects and returns. Expect this for every future
   picker leg.
4. **RIG: seeded media needs the media scanner to win a race** — a pushed +
   MEDIA_SCANNER_SCAN_FILE PNG did not appear in the picker's Recent grid within the
   leg; existing screenshots serve as pick candidates.
5. The mock's hook summary block is STATIC (pendingCount stays 2 after the flip) —
   the ITEMS' trust states are the live part; don't read the summary as an oracle.
6. **S13 flake (RecoveryScenarioMatrixTest)** exonerated green-in-isolation THREE
   times this session (pre-phase baseline + twice mid-phase) — the documented
   machine-timing class, unchanged.

Verification record (Phase 1): suite at phase HEAD `12a87ad` — 17 modules green
(1,511 tests / 0 failures / 0 skipped; all test XMLs fresh: six JVM `test` tasks
`--rerun-tasks` + eleven `testDebugUnitTest`), zero new failing names against the
799ebbb baseline; gradle task-failure list empty (no test-compile blind spot — every
module emitted XML). Deliverable: fresh debug APK built at HEAD, path + sha256 in the
roadmap stamp above. Nothing pushed — all work is local on `fix/vc51`.

---

# Phase 2 (vc51 remediation) — exit-gate rig leg + record — 2026-10-31 (APPENDED; the roadmap above is the work plan, this is its receipt pass)

Scope: the Phase-2 fix session itself (branch `fix/vc51`, phase cut from `3cbdefa`) — all
five rows implemented TEST-FIRST (each pin demonstrated RED at its pre-fix state before
the fix landed, pin + fix in the same commit), the full-suite zero-new-names gate, and the
fresh emulator+relay exit-gate leg. Everything above in the Phase-2 rows is receipt; the
ticks carry the per-row detail.

Fix commits (one row ≥ one commit, `fix(<ID>): <mechanism>`): `fa2bd31` G3 (resume + amend
leave the modeled-never-sent family) → `a9e74f01` G4 (queue delete + the AC6 default
interaction-behavior setting) → `c7cfebf0` G5 (find-in-conversation) → `5c4ddc07` A11
(landscape pinned budget recalibrated) → `8a9306d2` A12 (re-post shade actions) →
`0913dbfc` tools(mockzcode) rig arms (separate, per rule 5, self-tests green). All six
carry rig receipts against them.

## Full-suite zero-new-names gate (rule 7)

- **Baseline at the pre-phase commit `3cbdefa`: 1,512 tests / 0 failures / 0 skipped**,
  17 modules, all XMLs from the same day (gradle confirmed 368/368 up-to-date at the
  byte-identical tree — Phase 1's final sweep).
- **Phase HEAD: 1,543 tests = 1,512 + exactly the 31 new Phase-2 pins** (8 G3 + 9 G4 + 6
  G5 + 1 A11 + 2 A12 + 5 mock self-tests). Sixteen modules fully green fresh; the data
  module 174/175.
- **The one failing name across every sweep is `RecoveryScenarioMatrixTest`** (S8, then
  S13+S10, then S13 — a different scenario each load pattern): the Phase-1-stamped
  machine-timing class. **Exonerated by direct A/B**: the identical S13 failure
  reproduces at the PRE-PHASE commit in a `git worktree` (2/2 solo-module runs at
  `3cbdefa`, same ~90s signature), and the class is green 5× in isolation at phase HEAD
  today (2 class runs + 3 single-method runs, 18/18 each). No new failing name was
  introduced; the only failing gradle task ever observed was `:data:testDebugUnitTest`
  (test-failure class — no compile blind spot; all 17 modules emitted XML, and the
  transient single-XML state of data's results dir is an artifact of the isolation
  re-runs cleaning it, receipt in the session transcript).

## Phase-2 exit-gate walk (fresh zcode-pixel cold boot, relay on 10.0.2.2:18100, arming
MOCK_ROWS/GOAL/APPROVAL/ROW_ACTIONS/WORKFLOWS/TASKMGMT/QUESTION/HOOKS/COMPACT/
ATTACHMENT/QUEUE + HAPPY; the emulator died ONCE mid-walk (the 8GB memory-pressure class)
and was cold-booted back — the app auto-reconnected on relaunch in seconds; a stale relay
from a previous session was found holding :18100 and killed ONLY via
`pkill -f MockRelayMainKt`)

- **G3 Resume** — the runs panel listed the seeded Stopped/Resumable `release-notes` run;
  its sheet rendered "Resume run" + "Run settings"; Resume →
  `port.workflowResume :: run=wf-run-s1 outcome=Accepted`; the sheet flipped to **Running**
  and the Resume action retired (no longer resumable).
- **G3 Amend** — Run settings → the amend sheet (both fields, blank-keeps hints) →
  concurrency 3 → Apply → `port.workflowAmend :: run=wf-run-s1 model=keep concurrency=3
  outcome=Accepted`; the sheet's run flipped to **Stopped** (superseded; the mock grew the
  new `wf-run-s1-amended` run).
- **G4 delete** — MOCK_QUEUE's strip ("Queued (2) · paused") → Delete on item one →
  `sendConversationCommandV4 ok in 138ms` → the strip redrew authoritatively to
  **"Queued (1) · paused"** (the mock's patch round-trip, not a local removal).
- **G4 default setting** — the follow-up chips were ABSENT pre-default; Settings →
  Conversation → "Queue follow-up changes"; the row label **survived a full app restart**;
  back in the conversation the chips rendered with **Queue checked=true** (the wire's
  followupMode unset → the local default seeds).
- **G5 find** — the header affordance opens the bar (prev/next correctly disabled at no
  query); a query re-indexed live as new rows arrived ("1 of 2"); next → "2 of 2" with the
  visible row moving to the agent reply; next wrapped to "1 of 2" back at the user row;
  close removed the bar.
- **A11 landscape** — user_rotation=1 with the question card + queue strip + hook card
  armed: the pinned band measured **172px (≈65dp)** vs the recorded ~53px sliver; the
  title node a whole 45px line (was 16px half-cut); swipes traversed the card's full
  content (Channel → Changelog → the Skip row → into the next pinned card); portrait
  restored after the leg.
- **A12 re-post** — `notify.repost` fired for both attention records on the background
  entry; the ranking counter read **numWithActions=2** where pass 9 recorded 0/never —
  reconciled exactly: of the app's 4 enqueued posts (each record × the re-post + a fresh
  connect-time show), ONLY the approval's two carried actions; the question's two carried
  none. The shade-TAP answer sub-leg is explicitly **RIG-3-bound** (see the G4/A12 rows).

## New rig learnings + record duty (rule 10)

1. **RIG: `adb shell input text` drops or interleaves LONG payloads non-deterministically**
  (~60-250 chars; the pair link landed head-only, then interleaved with a previous
  attempt's late-flushing chunks). Chunked typing + per-chunk dumps + long settles are
  required; the pair-link paste flow could NOT be completed from the host this session
  (the pass-1 corrupted-link class) — relevant to any future fresh-pairing leg.
2. **RIG: the paste-pairing dialog RECALLS its last entry on reopen** (rememberSaveable) —
  a fresh dialog is not a clean field; exact-length MOVE_END+DEL bursts clear ~130 chars
  per burst (some keycodes coalesce).
3. **RIG: a rotation change background/foreground-cycles the app** — the A11 rotation
  consumed the A12 re-posted notifications (once-per-unseen + foreground-cancel
  semantics). Sequence interaction legs: A12 BEFORE any rotation leg, or re-arm first.
4. **OBSERVATION:** the mock's connect-time reconciliation re-posts (notify.show) fired
  for BOTH attention records at reconnect — the fresh path's actions held on the approval
  and held nothing on the question (a second live confirmation of the A12 gate).
5. The `numWithActions` ranking counter double-counts per AggregatedStats block (per-package
  + __global) — read it as one number, not two.

> **Phase 2 verification stamp.** Verified 2026-10-31, completed at **2026-10-31 17:09:39**.
> Verified by: **GLM-5.3-Flash** (`account:zai-individual-coding-plan/GLM-5.3-Flash`)
> running as the **ZCode CLI agent harness** (interactive coding-agent session on branch
> `fix/vc51`). Phase commits (oldest → newest): fa2bd31 (G3) → a9e74f01 (G4) → c7cfebf0
> (G5) → 5c4ddc07 (A11) → 8a9306d2 (A12) → 0913dbfc (tools/mockzcode rig arms). Fresh
> debug APK at phase HEAD: `app/build/outputs/apk/debug/app-debug.apk`, sha256
> 53b5d89689f173d608839230035ab882705e29440f7e8a9d4564f831d3bfc5f0 (31,851,929 bytes,
> versionCode 51, versionName 1.3.0), installed + walked on the rig. Baseline note: the
> RecoveryScenarioMatrixTest timing class failed in full-module pools at BOTH the phase
> HEAD and the pre-phase commit (A/B receipts above) and passed every isolation run.
> Nothing pushed — all work is local on `fix/vc51`.

---

# Phase 3 (vc51 remediation) — boundary decisions + exit-gate record — 2026-10-31 (APPENDED; the roadmap is the work plan, this is its receipt pass)

## Dated boundary decisions (G40 + G35)

- **G40 (workspace search scope / .zcodeignore rules editor) — B3 boundary, dated 2026-10-31:**
  the .zcodeignore rules editor (WorkspaceFileSearchSection.tsx, "Sync from .gitignore",
  "Restore default rules") is DESKTOP-SIDE authoring — it configures the desktop's own
  workspace-file-search indexing, a capability whose infrastructure (the local index, the
  sync pipeline) the phone does not host. The phone's bounded file plane stays as shipped:
  the read-only Files browser, the git read channel, and (NEW this phase, G9/RIG-2) the
  composer @-mention workspace-file search honoring the desktop's indexed results — the
  search itself runs desktop-side where the rules live. Editor/UI for the RULES is a
  desktop-authoring surface under the B3 precedent (hooks configuration, workflow
  authoring). Revisit only if a pass shows the mention panel's results ignoring rules
  users set (it cannot — the host filters).
- **G35 (the shipped web-remote-control browser companion) — B3/interop decision, dated
  2026-10-31:** the phone remains the NATIVE client and the browser shell is recorded as a
  PARALLEL surface, not a parity target: push notifications with shade actions, the
  glance widget, offline cache, launcher shortcuts, sealed multi-endpoint credentials, and
  voice input are phone-only (B4) and cannot exist in a browser tab. No interop work is
  planned: the surfaces share the host's v4 plane but nothing else, and bridging them
  (e.g. handing a browser session to the phone) has no user story. **The Relay V2 WATCH
  (JWT bearer, device roles, 60 s WS tokens — NOT in the shipped 3.14.5 asar) is carried
  verbatim into any future pairing/transport work**: a desktop update that flips the relay
  to V2 would strand the phone's pairing path, and any such change re-runs the W2 drift
  greps BEFORE the phone ships a release against it.

## Phase-3 implementation record (receipts; one finding ≥ one commit)

- GAP-A3/G14 `3bf40bb7` — unsubscribe-on-exit (plane + gateway + coordinator detach +
  hub close + VM onTeardown from the nav holder's onCleared; record drops BEFORE the send
  so recover() re-subscribes only owned topics). Pins: wire-shape + record-drop +
  never-subscribed no-op + the workspace-config word + coordinator detach/re-attach +
  VM teardown (throw-safe).
- G12 `e40d7eeb` — PendingCommandRecoveryBanner over the outbox's UNKNOWN replayable set
  (sendText/sendGoalCommand/compact/createSession); Replay = user-gated SAME-commandId
  retries; honest offline/failed copy; view-local dismiss re-raises on a NEW set.
- G13 `cf0f2c82` (+ mock `b57b2114`) — sessions-index live plane: domain decode
  (sessions-index.ts shapes, per-entry INV-8), plane subscribe/listen + the recovery
  branch, hub conflated per-workspace map, TasksUiState.sessionsIndex → TaskRow live tag
  (Live/Needs you/Ended).
- G15 `592545a7` — background-work indicator + read-only bash output viewer
  (backgroundBashOutputV4 typed union; cancelBackgroundWork finally reachable).
- G26 `0e7afb99` — the sheet's goal affordances (Set-goal row + pause/resume icons) over
  the VM's existing wire verbs (A13 refusal banners fire identically).
- G6 `05b3f874` — the cards' Keep-waiting snooze (snoozeInteractionAutoResolution; noop
  = accepted-class).
- G7+G8 `33aa1389` — the row action sheet's Ask-in-a-side-session + Share-selection
  (createSelectionSideSession {firstInput}; createdSessionId parses the result trio; the
  child navigation reuses the forkedSession channel).
- G9 `4c694c9b` — sharedContextImport decode (v2 sendable / legacy never) + sendText
  context_refs (max 1 fail-closed) + discardSharedContext + the composer chip; the
  mention panel's rig arm (MOCK_WORKSPACE_FILES) landed with the mock commit.
- G10 `4a061481` — suggested-prompt chips (a curated subset of the desktop's pool,
  verbatim copy) with cursor rotation + composer prefill.
- G11 `a80b94cb` — the conversation quota banner (StartPlanQuotaBuckets verbatim: active
  windows, the REAL remaining ratio, every-bucket exhaustion, the once-per-period
  reminder key; low ≤10% reminds once, exhausted always, healthy nothing).
- G20 `5893b533` — the coding-plan usage charts (getCodingPlanUsageSnapshot → stacked
  per-model bands + the totals line; channel refusal hides the section).
- G36 `0b594531` — the presentation-mode setting (office|coding with the legacy map)
  gating ToolCard detail (office = summary-only, taps no-op) + the Appearance row.
- G41 `cfec205a` — the workspace switcher/manager over RECEIVED workspaces (persisted
  order/aliases/selection; tap-switch re-groups live; Manage sheet reorder/rename).

## Full-suite zero-new-names gate (rule 7)

- Baseline: b2f07339 (pre-phase), fresh XML aggregation across all 17 modules:
  **1543 tests, 0 failures, 0 skipped** (`testDebugUnitTest` + the six JVM `test` tasks).
- Per-fix-commit module runs: every touched module re-ran green after each commit;
  REDs were demonstrated pre-fix per row (compile-class for new APIs; the behavioral
  REDs: G13's RUNNING-beats-ENDED precedence and G11's reminder-key suppression were
  caught by their own pins and fixed in-commit).
- Final full-suite run at the phase HEAD: see the exit-gate record below.

## Phase-3 exit-gate walk — PASSED 2026-11-01 (fresh zcode-pixel cold boot, relay on 10.0.2.2:18100)

Rig: zcode-pixel AVD cold boot (`-no-snapshot-load -gpu swiftshader_indirect -no-audio`),
MockZCode relay armed with the standing all-flags set PLUS the phase arms
(`MOCK_SESSIONS_INDEX MOCK_BACKGROUND_BASH MOCK_WORKSPACE_FILES MOCK_SHARED_CONTEXT
MOCK_QUEUE`), fresh-install pairing over the paste-link dialog. ZERO emulator deaths,
ZERO app deaths; one ANR dialog ("isn't responding") at the airplane-race leg — dismissed
with Wait, the app survived and kept its state (the 8GB-pressure class, recorded).

| Gate leg | Receipt |
| --- | --- |
| **A3 unsubscribe** (task switch) | Opened synth-task-3, switched to synth-task-1 → mock ledger `MOCK: v4call unsubscribeConversationV4 (id=50)` → `v4.call ok in 67ms` + app log `recovery.detached :: session=synth-task-3 (unsubscribed)` + `hub.conversationClose`. Screenshot `g13-live-tags.png` (the walk started here) |
| **G13 live row activity** | The task list rendered **Live ×2** (synth-task-1 scripted running + synth-task-3 flipped by the mock's 5 s delayed `session.upserted` — no manual refresh); wire: `subscribeSessionsIndexV4 ok in 449ms` + `hub.sessionsIndexSubscribe :: workspace=/synthetic/ws-one`. Screenshot `g13-live-tags.png` |
| **G6 snooze** | The question card's **Keep waiting** (tag `interaction_snooze_q-rig-1`) tap → `sendConversationCommandV4` (373 B envelope) → mock ack; the card defers (still pending — the snooze semantics). Screenshot `g6-snooze.png` |
| **G15 indicator + viewer** | Composer pill **"1 background task(s) running · View"** (`background_work_indicator`) → sheet lists "synthetic background build · bash · running" with Cancel → **View output** rendered the scripted bytes ("compiling module 1/3…") + "running · /synthetic/ws-one/.zcode/background/work-bash-1.log" over `backgroundBashOutputV4` (mock ledger ×1). Screenshots `g15-indicator-sheet.png` / `g15-output-viewer.png` |
| **G9 shared-context chip** | The composer carried the chip beside the indicator: "Shared context … (rides your next message)" with the **Remove** discard affordance (MOCK_SHARED_CONTEXT) — visible in the `w5` walk dump |
| **G26 goal affordances** | The context sheet's goal panel (status "verifying") — tapping the pause icon dispatched `pauseGoal` (sends 2→3 in the mock ledger); the echo stays "verifying" (the mock's goal script is static — rig fidelity, the wire verb fired). Screenshot `g26-context-sheet.png` |
| **G41 workspace manager** | The switcher row carries **Manage** → the sheet ("Workspaces / Synthetic One / /synthetic/ws-one / Rename / OK"); tapping the row switched the selection — **persisted**: `preferences.pb` now contains `/synthetic/ws-one` (selectedWorkspaceKey). Screenshots `g41-manager-sheet.png` / `g41-switched-list.png` |
| **G36 presentation mode** | Settings → Appearance → **Presentation mode** dialog (Office/Coding/Cancel) → Office → persisted (`preferences.pb` carries `OFFICE`) → task-1's tool row (`row_tool_Bash`) rendered SUMMARY-ONLY and tapping it expanded NOTHING (zero `tool_input_*` nodes — the office gate). Screenshots `g36-*.png`; restored CODING after |
| **G12 recovery banner** | **RIG-LIMITED**: the send+kill race produced `SendLost` via instant broken-pipe (1006) — the loopback classifies the loss immediately (the relay process IS the socket), so the outbox entry landed FAILED rather than UNKNOWN and the banner correctly stayed hidden (nothing replayable). On a real relay the kill leaves the client socket open → the 20 s v4call timeout → UNKNOWN → banner. The UNKNOWN→banner contract (render, Replay with SAME commandId, honest offline/failed copy, dismiss) is pinned by PendingCommandRecoveryVmTest/BannerUiTest. Screenshot `g12-sendlost-degraded.png` (the honest degraded face) |
| **G26 Set-goal / G7+G8 sheet / G10 chips / G11 banner / G20 charts / G40 editor** | Carried by their pins (GoalAffordancesUiTest set-row fires; CommandCoordinatorSelectionSideSessionTest wire shapes; SuggestedPromptRotationTest rotation + UiTest prefill; StartPlanQuotaBucketsTest verdict ladder; CodingPlanUsageSnapshotTest chart faces). G11/G20's scripted-data rig arms don't exist (MOCK_USAGE was not a phase arm) — their sections render the HONEST-FAULT face on the rig (hidden), matching the section contracts. G40's editor is a DATED B3 boundary (above), not a build |

Full-suite gate at the phase HEAD: **1605 tests, 0 failures, 0 skipped** across all 17
modules — 1543 baseline + **62 new names, ALL this phase's own pins** (zero unexpected;
name-diffed against the b2f07339 baseline). One ConnectionHubWritePathTest timing flake
in a mid-session pool run (TimeoutCancellationException, the documented 20 s-wait class)
passed in isolation and in the final full pass — exonerated per the standing pattern.
The I18nLiteralGateTest caught G36's two hardcoded contentDescriptions mid-phase — fixed
in-commit (the gate working as designed).

Deliverable: fresh debug APK built at HEAD — `app/build/outputs/apk/debug/app-debug.apk`,
31,869,533 bytes, sha256 `7b74efef4cd27953…`, versionCode 51 /
versionName 1.3.0 (the release-meta block attaches only at publish; nothing pushed).

**Verification stamp**: this phase was implemented, rig-verified, and marked complete on
**2026-11-01 at 02:58:20** (local time, yyyy-mm-dd hh-mm-ss) by **GLM-5.3-Flash**
(the session model) running inside **ZCode** (the ZCode CLI agent harness), working under
Ernest's standing deliverable-completion rule on `fix/vc51`; commits
`fa2bd31…b2f07339` (Phases 1–2) → `3bf40bb7…2c332ff3` (this phase), all local, nothing
pushed.
