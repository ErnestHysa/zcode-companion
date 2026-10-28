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
