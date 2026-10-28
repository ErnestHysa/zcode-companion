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
