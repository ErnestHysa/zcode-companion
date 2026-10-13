# Android-Native Gaps & Polish — combined report (desktop 3.14.5 vs app 1.1.0-vc40)

**Date:** 2026-10-13 · **Status:** findings only — nothing implemented.
**Provenance:** merged + condensed from two committed reports — round-2 feature gaps
(`android-native-gaps-2.md`, fb9dc44) and round-3 UI/UX audit (`android-native-uiux-audit.md`,
0c07d94). The full originals are in git history; this file is the working copy.
**Method:** shipped 3.14.5 bundle extraction + device-host facade diff (569 methods) vs the
app's actual calls; 3.14.3 source tree for readable desktop UI; full-file UI sweeps of every
screen (~170 findings); key claims spot-verified. Round 1 (`android-native-gaps.md`, all 23
items) shipped through 1.1.0-vc40 + d3f4425 — not repeated here.

---

# PART A — Feature gaps vs ZCode desktop 3.14.5

## A0. Headline

Round 1 closed the obvious holes (context meter, quotas, usage, automations CRUD, message edit,
copy/timestamps, viewers, model picker). The desktop has since moved substantially, and the
phone-reachable surface it exposes grew well beyond what the app calls. Three headline gaps:

1. **The phone sends images it can never see again.** Upload is fully wired
   (`attachmentBegin/Chunk/Commit/Abort/ReadV4`), but the conversation renders **no image rows at
   all** — `conversationAttachmentReadV4` / `conversationAttachmentStatV4` (on the shipped device
   facade, never called) are the read-back path, and the desktop previews media properly.
2. **An entire observation tier is unused: runs & trajectories.** Seven `conversationWorkflowRun*V4`
   methods (events, node results, artifacts, workspace), `listSavedWorkflows` + runs, automation
   **run history** (`listAutomationRuns`/`deleteAutomationRun`/`restartAutomation` — desktop has an
   Off-Peak history tab; the phone fires run-now into a void), `getModelTrajectory` +
   `readSessionMessages/Events/Debug`, `listSessionSubagents` (phone renders subagents as quiet
   meta text), `getTaskTokenUsage`.
3. **Task-management parity landed desktop-side and is missing on the phone:** archive
   (`archiveTask`/`listArchivedTasks`/`unarchiveTask`), pin (`setTaskPinned`/`listPinnedTasks`),
   rename, **custom task groups with colors** (`createTaskGroup`/`updateTaskGroupColor`/
   `listGroupedTaskViewStructure`), pause/resume (`pauseTask`/`resumeTask`),
   `branchTaskFromPrompt` (fork). The phone list has none of these actions.

Everything below is **phone-reachable per the shipped device facade** — no desktop change needed
for Tiers 1–2. Surface-method presence ≠ guaranteed routing; one live `v4Call` probe + golden
fixture per item (round-1 discipline) before building.

## A1. Corrections to round-1 conclusions

1. **Plugin writes may not be dead.** Round 1's "retired" verdict lives in
   **`pluginManagementService`** (desktop-Settings path). The **`zcodeAgentService`** the phone
   talks to separately carries `installPlugin`, `uninstallPlugin`, `updatePlugin`,
   `setPluginEnabled`, `configurePlugin`, `resetPluginConfig`, `validatePlugin`,
   `addPluginMarketplace`, `removePluginMarketplace`… — **all present in the shipped 3.14.5
   facade**, coexisting with `throwRetiredPluginManagement`. **Action:** one live
   `v4Call("setPluginEnabled", …)` probe settles it (enable/disable without any desktop change).
2. **Embedded slash catalog drifted:** 14 embedded vs **20** builtins in 3.14.5 (missing `/effort`,
   `/goal`, `/new`, `/plugins`, `/resume`, `/locale`). The live workspace-config topic is still
   broken for device connections (round-1 relay-adapter bug, re-confirmed in 3.14.5), so the
   embedded catalog is what users get — refresh it and re-embed as a build step, not a one-time
   capture.
3. **Stale doc:** `AutomationsPort.kt:8-10` still claims the write half "exists NOWHERE" — CRUD
   shipped 2026-10-12.

## A2. New on the desktop since the last audit (3.14.4→3.14.5, shipped-bundle verified)

- **Bot channels — desktop drivable from chat apps:** Telegram, WeChat, Feishu (reactions),
  Webhooks; bot CRUD/test/state surface + Bots settings UI. *Product implication:* "control ZCode
  away from the desk" is no longer the app's exclusive story — lean into native differentiation
  (biometric approvals, widget, FGS live window, streaming). Bot channels are also a
  **notification bridge** candidate for what the relay can't push.
- **Model Trajectory pane** (searchable step timeline w/ tool payloads) + `getModelTrajectory`.
- **Workspace checkpoints:** `createCheckpoint`/`diffCheckpoints`/`restoreBetweenCheckpoints`,
  `/rewind`, `/fork`, `branchTaskFromPrompt`, Checkpoints UI.
- **Task groups with colors**, archive/pin/rename, timeline section.
- **Assistant message feedback** (`setAssistantMessageFeedback`), **elicitation over the wire**
  (`respondElicitation`, transient interaction cards), **enterprise/team plans + payments**
  (Stripe/PayPal/ZaiPay — out of phone scope), **output styles**, **Claude Code import**
  (`scanImportableClaudeSessions`, `importClaudeSessions`, agents-file migration),
  `probeIntranet` (LAN discovery for direct endpoints), `getForceUpdateConfig`.

## A3. Gap tiers

**Tier 1 — high value, read-only, low risk**

| # | Item | Wire methods (all on shipped facade) | Phone today |
|---|---|---|---|
| 1 | Render conversation attachments (sent + desktop-side media) | `conversationAttachmentReadV4`, `conversationAttachmentStatV4`, `attachmentPreviewSourceV4` | sends only; zero image rendering |
| 2 | Automation run history | `listAutomationRuns`, `deleteAutomationRun`, `restartAutomation` | CRUD + run-now; no trail |
| 3 | Workflow runs viewer (read-only) + saved-workflow viewer | `conversationWorkflowRunsV4` + RunEvents/RunNodeResult/RunArtifacts/RunArtifactRead/RunArtifactData/RunWorkspace; `listSavedWorkflows`, `getSavedWorkflow`, `listSavedWorkflowRuns` | running workflows invisible |
| 4 | Reasoning-effort picker | `setThoughtLevel`; `/effort` | mode + model chips only |
| 5 | Subagent drill-in | `listSessionSubagents` | quiet meta rows |
| 6 | Per-task token usage | `getTaskTokenUsage` | session-level meter only |
| 7 | Catalog refresh + doc fixes | embed 20 commands; AutomationsPort comment | stale |

**Tier 2 — richer surfaces, mostly read-only**

| # | Item | Wire methods |
|---|---|---|
| 8 | Task actions: archive/pin/rename + archived viewer, pinned section | `archiveTask`, `unarchiveTask`, `listArchivedTasks`, `archiveStaleTasks`, `deleteArchivedTask(s)`, `setTaskPinned`, `setTaskUnread`, `listPinnedTasks`, `renameTask` |
| 9 | Custom task groups (named, colored, ordered) | `createTaskGroup`, `deleteTaskGroup`, `renameTaskGroup`, `updateTaskGroupColor`, `listGroupedTaskViewStructure`, `applyGroupedTaskViewOrder` |
| 10 | Pause / resume / continue task | `pauseTask`, `resumeTask`, `continueTask` |
| 11 | Checkpoints & rewind preview | `conversationFileRewindPreviewV4`; `gitCheckpointService`: create/diff/restore; `/rewind`, `/fork` (restore = ⚠) |
| 12 | Plans viewer | `conversationPlansV4` |
| 13 | Trajectory viewer v1 / conversation search | `getModelTrajectory`, `readSessionMessages`, `readSessionEvents`, `readSessionDebug` |
| 14 | Elicitation & transient cards | `respondElicitation`, create/update/deleteTransientInteractionCard |
| 15 | Assistant message feedback | `setAssistantMessageFeedback` |
| 16 | Import shared conversations | `importShare`, `publish` (publish = ⚠) |

**Tier 3 — write-adjacent, ADR-gated (INV-9 family)**

| # | Item | Notes |
|---|---|---|
| 17 | Git mutations incl. AI commit messages | `stage/unstage/discard/commit`, `generateCommitMessage`, `switchBranch`, `getBranchComparison` — killer feature, needs the ADR-020 treatment |
| 18 | Terminal input | phone is view-only by gate; same ADR family |
| 19 | Plugin writes | gated on the A1 probe; enable low-risk, install = trust surface |
| 20 | Hooks trust grant | `grantWorkspaceHookTrust` — a session can block on hook trust with no phone remedy |

**Not phone-reachable (documented desktop parity):** memory viewer (memoryService not on the
device facade), skills/plugins enable via desktop-local services (pending A1), shortcuts,
onboarding, CUA/browser panes.

## A4. UI/UX-relevant surface notes

Cross-endpoint deep links still hardcode `existsOnOtherEndpoint = false` (`ZCodeApp.kt:153-158`).
The auto-adaptation pipeline should add the builtin-slash-catalog count and the device-facade
method list as sensors — 3.14.4→3.14.5 added a whole bot+trajectory+checkpoint layer that T1
should flag as surface growth, not just ADDITIVE version movement.

---

# PART B — UI/UX polish audit (the "premium native" pass)

## B0. Headline

Bones are good: keyed LazyColumns, edge-to-edge on, designed light/dark tokens, honest state
rendering, predictive back declared. What fails the "Apple/Samsung would ship it" bar is six
**systemic** gaps, each multiplying across every screen:

1. **Zero motion.** No NavHost transitions, no `AnimatedVisibility`/`animateContentSize`/
   `Crossfade`/`animate*AsState` anywhere, no caret blink, **zero haptics in the app**.
   Everything pops, snaps, or teleports.
2. **Dialog-everywhere.** AlertDialogs stack on AlertDialogs (create→model picker, edit→confirm,
   row actions, queue edit). M3 `ModalBottomSheet` is used **nowhere**. The single biggest
   "doesn't feel modern" tell.
3. **Hand-rolled chrome.** One Scaffold in the whole app (pairing); 4 header variants, 2 badge
   systems, 2 divider systems, 5 loading patterns, 2/4/6/8/10/12/14/15/16dp spacing anarchy,
   `Surface+clickable` where `ListItem`/`Card(onClick)`/`SegmentedButton`/`BadgedBox`/
   `stickyHeader` exist.
4. **Theme gaps that read as "grey".** Compact type scale (18sp titles, 11sp labels — below the
   12sp readability floor), no dynamic color (Material You), unmapped `surfaceContainer*` roles
   (flat grey dialogs), literal ARGB leaks (MCP viewer `Color(0xFF2E7D32/0xFFC62828)`, Glance
   widget amber, terminal ANSI white-on-light), glyph-as-icon ×10, `Icons.Filled.Send` not
   auto-mirrored, and a 2014-era platform launch theme (no SplashScreen API → blank cold-start
   flash).
5. **Real bugs on the most-used surfaces** (B2) — data-loss class leads.
6. **~30 hardcoded English strings** breaking the localized app, plus sub-48dp touch targets and
   null contentDescriptions concentrated in Git/Files/rows.

## B1. The model picker — root cause (named by Ernest) + fix spec

**"Grey/dark skeleton":** from the new-task composer the chip opens `ComposerModelPickerDialog`
*on top of* `CreateTaskDialog` — two M3 `AlertDialog`s stacked (`TaskListScreen.kt:339-348`),
double scrim. While loading the body is a lone 18dp spinner in an empty grey box — and the
dialog's **title is literally "Close"** (`Phase9Components.kt:83`, `R.string.action_close`
copy-pasted into the title slot).

**"Tap doesn't highlight, but the bottom shows the name":** the conversation picker
(`Phase9Components.kt:277-371`) is a hidden **two-step** flow. Tapping a model only sets
`thoughtMenuFor` (:341); `selected` derives from the session's *current* config (:321) so **no
radio ever moves on tap**. The only acknowledgment is a "Thought level for \<model\>" row appended
at the **bottom** of the dialog (:348-365) — often below the fold. The composer picker
one-tap-picks+dismisses — two same-looking pickers, opposite behaviors.

**Fix spec:** ONE shared `ModelPickerSheet` (ModalBottomSheet): provider-grouped LazyColumn,
sticky headers, check icon (not radio) on the active model, **immediate pending highlight** from
local state — never the session echo; tap a model with >1 thought level → expand that row inline
(or a docked confirm bar "Use \<model\> · \<effort\>" with effort as a SegmentedButton); single
tap when one level; "Current" tag; search field; 48dp rows. Create-task stops stacking dialogs —
`CreateTaskDialog` becomes a bottom sheet reusing the same picker. Fix the title; delete the
`action_close` copy-paste.

## B2. Concrete bugs (fix-first)

| # | Bug | Where |
|---|---|---|
| 1 | **Editing an automation silently corrupts it:** daily time resets to `"09:00"` (hardcoded init ignores `scheduleRule.hour/minute`) and the configured model is dropped (`pickedModel` starts null) — saving rewrites the schedule with zero warning | `AutomationsSection.kt:269-273` |
| 2 | **Voice mic visually dead:** `voice.listening` is a plain getter (not Compose state) — tint/"Listening…" never recompose; `onFinal` appends to the draft captured at click time, discarding text typed while listening | `WriteComponents.kt:626,641,701-708`, `VoiceInput.kt:47-48` |
| 3 | **Approve/Deny flush + overflow:** dead `Spacer(0.dp)` + discarded `PaddingValues(4.dp)` between them; unweighted Row clips on narrow screens — highest-stakes surface | `ApprovalsScreen.kt:169-205` |
| 4 | **Multi-select questions render as toggle-off RadioButtons** with non-clickable ~20dp labels — on the blocking "agent needs your answer" card | `WriteComponents.kt:291-325,365-382` |
| 5 | **ConversationViewModel in `remember(taskId)`** — rotation/recreation loses state; should be backstack-scoped | `ZCodeApp.kt:374-378` |
| 6 | **switchMessage auto-dismiss launched in composition** — restarts on every recomposition, may never clear, vanishes abruptly | `SettingsScreen.kt:325-329` |
| 7 | **Git failures show false data:** `changes()` errors swallowed to empty → "no changes" is a lie; Retry only re-checks `summary()` | `GitScreen.kt:79-92,115-121` |
| 8 | **Terminal ANSI white-on-light** (`Color(0xFFFFFFFF)` palette entry on light surfaceVariant); literal 16-color ARGB table | `TerminalScreen.kt:229-234` |
| 9 | Unread badge = bare 16dp dot (no count/semantics) vs TaskList's numbered badge silently capped at "9" | `ConversationScreen.kt:545-553`, `TaskListScreen.kt:396` |
| 10 | Pairing shows two different hardcoded desktop versions (`"3.14.0"` and `"3.14.3"`) — should come from `CompatibilityGate` | `PairingScreens.kt:259,280` |
| 11 | Automations Run Now silent (result ignored, no busy/toast); double-tap double-fires delete/run | `AutomationsSection.kt:73-81,92-106,227-247` |
| 12 | Pinned strips stack unbounded in a non-scrollable column — can squeeze the timeline to zero | `ConversationScreen.kt:150-200` |
| 13 | Stale-state remembers: approval `confirmOption`/`refineText`/`selected` not keyed on `interactionId`; edit-dialog initial text unkeyed | `WriteComponents.kt:84-85,258-259`, `EditUserQueryDialog.kt:36` |
| 14 | Attachment bytes read on the main thread in the picker callback (ANR risk) | `WriteComponents.kt:809-819` |
| 15 | Markdown re-parse per streaming delta (jank on long replies); relative times never refresh | `RowRenderers.kt:444,583-585` |
| 16 | Files binary-download dialog keyed on a display string | `FilesScreen.kt:256` |
| 17 | Workspace collapse saves to disk synchronously on the UI thread per tap; first frame pays the read | `WorkspaceCollapse.kt:26,35` |
| 18 | Rot: double dividers, duplicated headers ("Workspace files" over "Files"), dead `save()` + two `workspaceKeyFor()`s, broken indentation, duplicate imports | various |

## B3. Systemic causes (fix once, lift everything)

- **Motion system absent** — nav transitions (`ZCodeApp.kt:200-204`), AnimatedContent on state
  swaps (pairing's 15 states, conversation body), `animateContentSize` on every expand/collapse,
  `animateItem` on list changes, FAB/notice/strip entrances, static streaming caret.
- **Zero haptics** — send/approve/pick ticks, long-press tick, copy feedback.
- **No Scaffold/TopAppBar discipline** — hand-rolled headers ×4 variants, duplicated stacked
  titles on Files/Git/Terminal routes, no scroll elevation, manual `statusBarsPadding` ×12.
- **Dialog-first flows** — model picker (B1), row-action menu (`RowRenderers.kt:116-146`), queue
  edit (`WriteComponents.kt:476-501`), edit→confirm double modal, workspace DropdownMenu inside
  dialog, unpair full-screen-vs-dialog inconsistency.
- **Theme** — type scale + letterSpacing (define `headline*` explicitly), dynamic color ADR
  (Material You on chrome roles, semantic status tokens fixed), `surfaceContainer*` mapping,
  ARGB→roles, glyph markers→icons.
- **Launch** — `androidx.core:core-splashscreen`, `Theme.SplashScreen` + DayNight post-splash
  theme, branded window background.
- **States** — five loading patterns (bare "Loading…" text, static skeleton+redundant text, mini
  spinner, state-swapped row text, uncancellable full-screen pairing spinner); text-only empty
  states; Toast ×5 instead of Snackbar; errors without retry or silently swallowed.
- **Touch targets & a11y** — 22–42dp targets (turn chip ~22dp, copy button 24dp, git rows ~36dp,
  files rows ~42dp, slash items ~40dp, context toggle ~28dp); null contentDescriptions; bare
  `clickable {}` without roles everywhere; hue-only states (heatmap, status dots, MCP);
  TalkBack-invisible hand-rolled radio menus.
- **Localization** — ~30 hardcoded English strings (pending-sends, queue "paused", routing hint,
  "writing…", turn "view", approvals error ×2, schedule lines, terminal/git/files statuses and
  headers, "read-only" ×2, hardcoded "Back" cDs, bilingual toast), fixed `MM/dd HH:mm`,
  locale-sensitive `String.format`, `== "RESET"` i18n-fragile confirm gate.
- **Structure rot** — duplicate imports, fully-qualified inline composables mixed with imports,
  dead code (`AutomationsSection.save()`), broken indentation blocks.

Full per-file findings (evidence base): in git history at `0c07d94`
(`android-native-uiux-audit.md` §8 appendix, ~170 line-referenced items).

---

# UNIFIED PLAN (for ruling — nothing started)

**P0 — bugs + corrections (ship first, small and testable):** B2 items 1–8, 10, 13, 16–18
(automation corruption, voice dead state, approvals flush, multi-select radios, ViewModel scope,
switchMessage timer, git false-empty, ANSI palette, version literals, stale remembers, string-keyed
dialog, sync I/O). Instrumented pins for B2.1/B2.2. Plus A1: the plugin probe (one live
`v4Call("setPluginEnabled")`) and the slash-catalog refresh — even if the ruling is "stay
read-only", the probe settles a wrong conclusion either way.

**P1 — foundation lift (mechanical, lifts every screen):** theme completion (standard M3 type
scale, letterSpacing, `surfaceContainer*`, dynamic color behind ADR, ARGB→roles, icons over
glyphs) · SplashScreen · nav transitions with predictive-back pops · Scaffold+TopAppBar
everywhere · one SectionHeader/EmptyState/ErrorState/LoadingSkeleton (shimmer, reduced-motion-
aware) · BadgedBox/HorizontalDivider/stickyHeader/`animateItem` unification · ModalBottomSheet
migration for pickers/menus (model picker per B1, row actions, queue edit) · haptic system ·
a11y pass (48dp minimums, roles, stateDescriptions) · extract the ~30 hardcoded strings ·
4/8/12/16 spacing tokens.

**P2 — screen redesigns + Tier-1 features:** model picker sheet (B1) · create-task sheet ·
automations section (SegmentedButton schedule kinds, TimePicker, inline validation — kills the
silent corruptions — per-row overflow menu, Switch enable, Run-Now feedback, collapsible) ·
approvals buttons + busy acknowledgment · pairing (reticle, torch, cancellable progress) ·
usage/quota (SegmentedButton ranges, animated LinearProgressIndicator incl. update download,
heatmap legend) · terminal readable palette + real toggles · files breadcrumbs + empty state ·
**conversation attachment rendering** (A3.1) · automation run history (A3.2) · effort picker
(A3.4) · subagent drill-in (A3.5) · per-task tokens (A3.6) · assistant feedback (A3.15).

**P3 — bigger surfaces + ADR-gated:** workflow runs viewer (A3.3) · task archive/pin/groups +
pause-resume (A3.8–10) · checkpoints/rewind preview + plans + trajectory/search + elicitation
(A3.11–14) · share import (A3.16) · then ADR-gated writes: git mutations with AI commit messages,
terminal input, plugin installs, hooks trust. Auto-adaptation sensors: slash-catalog count +
facade method list (A4).

---

# PART C — deeper round, pass 2 (appended 2026-10-13; findings only)

What this pass covered that Parts A/B did not: the **channel-level** protocol surface (vs the
facade method list), notification affordances, live-data flows, i18n depth, process-death
restoration, and a parity re-read of the desktop's composer panel. Every new claim was verified
in source or the extracted shipped bundle before being written down. Plan deltas are in C9.

## C1. Channel-level surface diff (new)

The phone today uses six channels: `zcode-agent` (RPC), `usage-stats`, `model-selection`,
`terminal`, `git`, `file`, `off-peak-task`. The shipped device-host bundle also registers these
channel strings the phone never touches:

| Channel | Status | Phone opportunity |
|---|---|---|
| `conversation-share` | unused | share import/viewing (pairs with A3.16) |
| `file-watcher` | unused | **live Files screen** — desktop watches the workspace; the phone's Files screen is static until manual reload (verified: zero file-watcher usage app-side) |
| `media-preview` | unused | rich previews for attachments/files (desktop's `IMediaPreviewService` exists precisely so large media doesn't fall back to `file.readMediaPreview`) — pairs with A3.1 |
| `commands` | unused | **custom commands in the slash menu** — see C2 |
| `hooks` | unused | hooks viewer + trust grant (pairs with A3.20) |
| `skills` | unused as a channel | phone fetches the catalog once via `getSkillReferenceCatalog` RPC; the channel may push catalog updates — probe |
| `coding-plan-subscription` | **contradicts the app's own ADR-020 note** | `V4Plane.kt:203` says "no quota push channel anywhere in the desktop" — but the string exists in the shipped bundle. Probe device routing; if routable, quota panels become push-live instead of the current poll cadence (ADR-020 delta) |
| `client-config` / `feedback` / `broadcast` | unused | investigate (may be desktop-scoped; `feedbackService` = user feedback submission — a phone feedback form is a candidate) |

## C2. Slash menu parity — three sections on desktop, one on phone (new, verified)

The desktop's slash panel (`slashCommandPanelSections.tsx`) renders **three sections**:
**commands** (builtins *and the user's own custom commands*), **skills** (filtered, with
loading/error states), and **subagents** (same). The phone's `SlashCommandMenu` renders one
section fed by the workspace-config wire catalog + the embedded builtin fallback. Verified:
`queryConversationCommandsV4` is never called app-side (zero hits), while the desktop serves it
(`zcodeAgentService.ts:5111`) — **a user's own custom `/commands` are invisible on the phone**.
Skills are already fetchable (the viewer uses the catalog); subagents are Tier-1 A3.5. Fix: one
sectioned menu (Commands / Skills / Agents) over data that already exists on the wire.

## C3. Notifications have zero actions (new, verified)

`core/notifications` contains no `addAction`/`Action.Builder` anywhere — the "approval required"
notification is tap-only (opens the task). The approvals inbox already answers interactions over
the wire, so the payload is sufficient for **inline Approve-once / Deny actions in the
notification shade** — pure phone-side work, and the single biggest "control from your phone"
upgrade available. Pair with a post-tap haptic and (P3) considering Android's conversation-style
presentation for task updates.

## C4. Live-data gaps (new)

- Files screen: static (C1 `file-watcher`); even a cheap 5s poll would feel alive vs today.
- Automation runs: no push; run-now results land nowhere (B2.11) — the runs history (A3.2) plus
  a poll-on-open covers it.

## C5. i18n is deeper than "30 hardcoded strings" (new, verified)

`values-zh-rCN` is maintained and **fully in sync (191/191 keys)** — so the i18n pipeline works,
and every hardcoded English string from Part B lands untranslated inside an otherwise-Chinese UI
(including the bilingual toast, which becomes actively embarrassing). Worse: the **entire error
taxonomy is English-in-code** — `ErrorMapping`'s `userMessage`s ("Desktop unreachable — wake the
Mac, relaunch ZCode", "Pairing expired — scan the QR again"…) are compiled literals in
`core/transport`, so the single most stress-timed copy in the app can never localize. Fix
direction: `AppError` carries a message **key + args** instead of prose; strings.xml per locale;
externalize the ~30 Part-B strings in the same pass. This upgrades the B3 i18n bullet into a
first-class P1 workstream.

## C6. State restoration & navigation (new, verified)

- **No `SavedStateHandle` anywhere**: composer drafts survive process death (Room-backed — good),
  but scroll position, file-viewer state, expanded diffs, and terminal pause/search all die.
- **No `BackHandler` anywhere**: the architecture doc's promised Phase-7 draft guard never
  landed — leaving a conversation with an **unsent draft and a running task** gives zero
  confirmation. (Predictive back is declared manifest-side; the guard is the missing half.)
- **No `setTaskDescription`**: the recents card shows a generic label instead of the task name —
  a one-line polish win for a multi-tasking user.
- Cold start can flash Pairing before the paired-gate reroutes (`startDestination` from a live
  flow, `ZCodeApp.kt:202`) — evaluate the gate before first frame.

## C7. New parity items (facade-verified)

- **Scratch workspace:** `createScratchWorkspace` is on the facade and the desktop has a
  dedicated dialog; the phone's create-task flow can't start one.
- **@-mentions:** the desktop composer's mention panel suggests workspace files (same
  workspace-file-search service); the phone already owns `searchWorkspaceFiles` on its port —
  an "@ to mention a file" chip in the composer is pure UI parity, **zero new wire surface**.
- **Context-budget strategy:** `getModelContextBudgetStrategy` — the phone's context meter % may
  drift from the desktop's actual budgeting; align the math.
- **Efficient tool drill-in:** `getTaskSnapshotToolCallsSlice` / `getTaskSnapshotWithEtag` can
  power "show full tool output" without loading whole rows (pairs with B2.15's re-parse jank).

## C8. Strengths worth protecting (new — fairness section)

The audit is not all deficits: `ErrorMapping`'s shape (category + user-facing message +
developer diagnostic + retryable class) is genuinely good design; both main lists key and type
their LazyColumns correctly; `imePadding` is correctly applied on the conversation screen; and
the zh catalog's 191/191 discipline proves the localization process works — the holes are the
hardcoded strings, not the pipeline. These patterns should be the template the polish rounds
conform to, not casualties of them.

## C9. Plan deltas (extends the UNIFIED PLAN above)

- **P0 additions:** the two new probes (quota-push channel routing — with an ADR-020 delta if
  positive; `skills` channel) · `BackHandler` draft guard (small, bug-class — the promised
  Phase-7 guard) · `setTaskDescription` one-liner.
- **P1 additions:** error-string externalization (`AppError` message keys + locale catalogs) as
  part of the i18n workstream · `SavedStateHandle`/rememberSaveable sweep for scroll/viewer/
  expanded/terminal state · paired-gate-before-first-frame.
- **P2 additions (top of the list):** **notification Approve/Deny actions** (C3 — highest-value
  phone-side upgrade found in this pass) · slash menu three sections (C2: custom commands +
  skills + subagents) · `file-watcher`-live Files screen (or poll fallback) · `media-preview`
  previews · @-mentions in composer · scratch-workspace creation · context-meter strategy parity.
- **P3 additions:** `conversation-share` channel import flow · `feedback` channel (in-app
  feedback form) · `client-config`/`broadcast` investigation.

---

# PART D — deeper round, pass 3 (appended 2026-10-13; findings only)

This pass went after what code-reading and surface-diffing still hadn't touched: privacy
exposure, the wire snapshot's *dropped* fields (round 1 checked only contextUsage), the
auto-adaptation sensors' blind spots, desktop picker detail parity, and a real device-boot
visual attempt. Every claim verified before writing. Supersedes C1's "broadcast — investigate"
row (resolved below).

## D1. Privacy UX: conversation content is exposed in recents and recordings (new, verified)

No `FLAG_SECURE` and no `setRecentsScreenshotEnabled` anywhere — the app switcher shows the
conversation verbatim (prompts, code, file paths), and any screen recording captures it. For a
companion that gates itself behind biometrics, this is the missing half of the privacy story:
**recents blurring** (`setRecentsScreenshotEnabled(false)` on sensitive screens) plus an optional
"block screenshots" toggle (FLAG_SECURE) in Settings → Privacy. (`network_security_config` is
present — the baseline is fine; the gap is screen exposure specifically.)

## D2. Wire-carried fields the phone decodes but never renders (new, verified)

The desktop reads four `snapshot.runtime`/snapshot fields the phone's `SessionProjectionState`
does not decode — all arrive on the wire, so all are pure decode+UI work (class A):

| Field | Desktop use | Phone today |
|---|---|---|
| `goalVerifications` + `goalVerificationTimeline` | pass/fail criteria timeline for `/goal` sessions | phone sends/resumes goals but renders **no verification state at all** |
| `activeTurnKind` | turn-kind-aware progress ("running workflow…" vs "generating") | generic "generating" label |
| `todoGroups` | session todo projection for restore display | no todo state rendered (TodoWrite shows only as a generic tool row) |

The goal-verification timeline is the standout: on a goal session the desktop shows which
criteria passed/failed; the phone shows nothing — invisible mid-flight progress on its own
flagship control flow.

## D3. The auto-adaptation sensors are blind to the exact drift class Parts B/C found (new, verified)

`tools/drift/` fingerprints `channels.ts`, the slash catalog, and the relay surface
(`consumed-surfaces.json` / `surface-inventory.json`) — but has **zero coverage of the
device-host facade method list** (`facade: 0`, `host/index: 0` in the inventory). So a desktop
release adding `getModelTrajectory`, bot channels, or checkpoint methods — the entire PART B/C
finding class — classifies as *nothing* today. Fix is mechanical: a third extractor leg
(`extract-facade-surface.mjs`) that fingerprints facade method names from the shipped bundle
(the PART B extraction recipe *is* the implementation), + `consumed-surfaces.json` entries for
the methods the app actually calls. Without this, "auto-adapt" adapts to version numbers, not
surfaces.

## D4. Desktop model-picker detail parity (new, verified — feeds the B1 spec)

The desktop's `ModelConfigSelect` carries **badge labels** on models and provider groups
(`badgeLabel`/`labelBadge`); the phone picker renders plain labels only. Fold into the B1
redesign: badges + provider grouping + search + current-tag.

## D5. Service classifications closing C1's "investigate" rows (new, verified)

- **`broadcast`** = desktop-internal inter-window broadcast + claim tokens (renderer ↔ host ↔
  main coordination). **Correctly out of phone scope** — remove from any future consideration.
- **`clientScenes`** = `IClientScenesService.list()` returning scene configs — desktop UI
  scenes; low phone relevance. `client-config` / `feedback` remain genuinely open.

## D6. Streaming pipeline verdict (new — precision + fairness)

`coalesceForUi` (33 ms frame-aligned, first-value-immediate, conflated) **is wired**
(`ConversationViewModel.kt:11` — an earlier suspicion it was dead code is retracted), and
streaming deltas are transient by design (never persisted). Consequence for B2.15: the
markdown re-parse runs per **coalesced frame**, not per network delta — the finding stands but
is softer than written; long-message re-parse cost is ~30 Hz × O(message), still worth the
chunk-memoization fix, no longer an emergency.

## D7. Visual ground-truth pass: attempted, infrastructure-blocked (new — documented)

The debug APK builds (53 MB), installs, and launches clean on the `zcode-atd` AVD (API 30);
uiautomator confirms the first-run content renders ("Control ZCode from your phone" / Scan QR /
Paste pairing link). But `screencap` returns a uniform black frame on this ATD image in **both**
swiftshader and host-GPU modes — the system Settings app captures identically black while
SurfaceFlinger reports 66 live layers, i.e. a capture-pipeline limitation of the ATD image, not
an app defect. **Recipe for the next visual round:** boot a pixel (non-ATD) system image or a
windowed emulator, then `adb exec-out screencap`. Worth one round: a pixel audit of the model
picker would show B1's grey-box symptom exactly as Ernest described it.

## D8. Plan deltas (extends the UNIFIED PLAN and C9)

- **P1 additions:** the facade-extractor sensor leg (D3) — small, closes the pipeline's blind
  spot so future desktop releases are sensed, not manually rediscovered.
- **P2 additions:** **goal-verification timeline** (D2 — high value, class A wire data) ·
  recents-blur + screenshot-block toggle (D1) · activeTurnKind-aware streaming label ·
  todo restore display · model-picker badges (D4, folded into B1).
- No P0 changes: nothing in this pass is a bug-class defect; the coalescer suspicion was
  retracted (D6).

---

# PART E — deeper round, pass 5: live-device ground truth (appended 2026-10-13)

This pass booted the **real app on a real (virtual) device**, paired it to the repo's own
`tools/mockzcode` relay over adb-driven UI, and audited live UI hierarchies — the layer neither
code reading nor bundle extraction can see. Pixel capture stays blocked on the ATD image (D7;
`screenrecord` also produces nothing), but **uiautomator hierarchy dumps are pixel-free ground
truth**: text, structure, and geometry of what actually rendered. Findings:

## E1. The pairing rig works — with one live-use observation (new, verified)

`gradle :tools:mockzcode:mockRelayServer` + "Paste pairing link" (link rewritten to
`10.0.2.2`) pairs the real app to the mock end-to-end. One real finding on the way: the mock's
pairing link **expired between passes** — the app showed "Pairing failed — the link was
rejected / Pairing expired — scan the QR again". That's the same UX a real user hits returning
to a stale QR/link; the error copy is honest and actionable (good), but the pairing-gate offers
no "generate a fresh link" affordance from the failure state — you must rescan from scratch
(desktop-side: the desktop can mint a fresh link instantly). Consider a "desktop: regenerate"
hint or auto-refresh of the QR on the desktop side.

## E2. Model picker "grey skeleton" — CONFIRMED LIVE on-device (new, verified)

The B1 root cause is no longer inference: with the create-task dialog open, tapping the model
chip stacks the picker dialog, and the live hierarchy shows a dialog whose **title node renders
the literal text "Close"** (a 34dp-tall title reading "Close"), a second "Close" TextButton
below, and an empty body between. Titleless contentless "Close"-titled grey box floating on
another dialog's scrim — that is precisely the "grey/dark skeleton on the outside and inside"
symptom, reproduced. The fix remains B1's sheet redesign; the title bug alone is a one-line P0.

## E3. Row capability flags are decoded and entirely unused (new, verified — P0-class)

`ConversationRow.Base.Actions` decodes **`canFork`, `canRetry`, `canRewindFiles`, `canEdit`**
from every wire row — and zero of the four are consumed anywhere (no hits outside the domain
model). Two consequences:

1. **Correctness:** the long-press action sheet gates Edit on `entityId` presence — the wire's
   own `canEdit` verdict is ignored, so the app can offer Edit on rows the desktop forbids (and
   would hide it on allowed rows lacking an entityId). The round-1 note "mirroring the desktop
   gate" was wrong. This is a small correctness bug, not just a missing feature.
2. **Dropped affordances:** when the desktop marks a row `canRetry` / `canFork` /
   `canRewindFiles`, the phone silently drops those actions. That hands the app Retry, and
   Fork/Rewind entry points (A3.11), for free — the wire already says which rows support them.

Fix: gate the action sheet on the flags (P0 correctness), then surface offered actions (P2).

## E4. Fresh-paired first impression: failure strip + empty state at once (new, observed)

Immediately after a successful pairing, the Tasks screen rendered **"Connection failed"** and
"No tasks yet" simultaneously (here mock-caused — the mock scripts no tasks plane — but the
*pattern* is real: any transient post-pairing handshake failure leaves the raw failure strip up
with no auto-retry pulse and no explanation linking it to the just-finished pairing). Worth a
P2 pass: after pairing succeeds, either suppress the transient failure strip until the first
refresh completes, or annotate it ("first sync failed — Retry"). Honest-machine-state design
intact; this is sequencing polish.

## E5. Fairness: the reduced-security consent is well-executed (new)

On a device with no screen lock, pairing shows an explicit "No screen lock detected → Continue
with reduced security / Cancel" gate — exactly the right security UX (informed consent, no
silent downgrade). Protect this pattern.

## E6. No Compose lint discipline — the polish will rot without it (new, verified)

`app/build.gradle.kts` has no `lint {}` block and no compose lint dependency. The bug classes
Part B found by hand — bare `clickable {}` without roles, sub-48dp targets, literal ARGB
colors, un-guarded early-returns before remember — are mechanically catchable with
`com.slack.lint.compose` + Compose's own checks wired into CI. Add it in P1 so each fix round
can't regress.

## E7. Tooling note for future visual rounds

ATD pixel capture is dead in all attempted modes (`screencap` exec-out/device-side,
`screenrecord`) while hierarchies stay fully readable — this pass's method (mock pairing +
uiautomator dumps) is the ATD-viable ground truth. For real pixels, D7's recipe stands (pixel
system image or windowed emulator). The mock rig + fresh-link discipline (E1) is reusable for
every future audit and fix round.

## E8. Plan deltas

- **P0 additions:** gate the row action sheet on `Base.Actions` (E3.1 — correctness: don't
  offer forbidden actions); the one-line picker-title fix is already covered by B1 but is now
  proven live (E2).
- **P1 additions:** `com.slack.lint.compose` + lint wiring (E6).
- **P2 additions:** surface Retry/Fork/Rewind when the wire offers them (E3.2, pairs with
  A3.11); post-pairing first-sync sequencing polish (E4); desktop-side QR/link regeneration
  hint (E1).

## E9. Close-out addendum: the live-channel blocker root-caused; what stands where (same day)

Pursuing E2 further exposed the rig's transport limit and one more app finding:

- **Root cause of every "Connection failed" on the rig:** the mock's WebSocket server is
  `mockwebserver3.MockWebServer` with `webSocketUpgrade`, and it **masks server→client frames**
  — an RFC 6455 §5.1 violation the app's OkHttp client correctly refuses (`close 1006:
  Server-sent frames must not be masked`, caught live in logcat: `model-selection.getView
  begin` → `failed: channel closed: abnormal close 1006`). Pairing succeeds (HTTP link flow);
  every subsequent channel call dies. This is a library quirk, not fixable from mock code — a
  live-channel rig needs a real WS server (the masked-frames fact also explains why the
  instrumented suites seed Room directly instead of relying on the live plane).
- **Mock extended anyway (prep for a proper rig):** `MockRelayMain` now serves a faithful
  two-provider `model-selection.getView` default (self-tests green) — previously it refused the
  call, which is its own trap for future E2E.
- **NEW verified finding — the composer picker has no empty state at all:** with options
  unavailable, `ComposerModelPickerDialog` renders `null → spinner; else → bare for-loop` — an
  empty list produces a **completely empty body** under the "Close" title (live dump shows
  exactly that, twice: before and after the mock served options). The conversation picker at
  least has the GAP-18 "unavailable / keep current" branch; the composer one IS the grey
  skeleton end-to-end: stacked dialog + "Close" title + empty body. Fold into B1's fix.
- **Honest scope of live verification:** the *populated* picker list, its row geometry, and the
  conversation-side two-step flow (tap → no highlight moves → bottom thought-level section,
  Phase9Components.kt:321/341/348-365; composer one-tap :105-108; rows ≈40dp by padding math)
  stand on **code evidence** — they are unreachable live until a mask-correct WS server
  replaces MockWebServer in the rig. The on-device confirmations this pass does carry: the
  "Close"-title dialog structure (E2), the empty-body rendering above, the getView call firing
  and failing at the transport layer, and the full pairing/consent/tasks flow (E1/E4/E5).

---

# PART F — deeper round, pass 6: rig fixed; new live bugs (appended 2026-10-13)

The E9 blocker was eliminated: the mock now runs on a hand-rolled RFC 6455-correct server
(`RawWsServer.kt`) instead of MockWebServer's masking upgrade. With the wire finally honest,
the rig completed its first **full end-to-end session ever** (pair → auth → bridge → Connected
→ create-task accepted → channel calls succeed), and live testing immediately surfaced findings
the code passes could not see.

## F1. The rig is fixed and now a reusable verification asset (new)

- `RawWsServer.kt` (mock module): HTTP/1.1 upgrade handshake, unmasked server frames, client
  frame unmasking, ping→pong, close, extended-length forms; `RawSocket` implements
  `okhttp3.WebSocket` so `MockZCode`'s send paths work unchanged (`startRaw(port)` +
  `rawRoute` mirroring the dispatcher's RELAY/DIRECT semantics, incl. the /api/server-info
  token gate).
- **Probe sequence proves the fix at the byte level:** masked pong (MASK-BIT=1, garbled) on
  MockWebServer → **MASK-BIT=0, clean payload** on RawWsServer.
- Regression pin `RawWsServerTest`: a REAL OkHttp client round-trips text + a 70,000-byte
  frame (8-byte extended length), plus the direct-mode 401/200 auth gate. Green.
- `MockRelayMain` now boots the raw server and serves the E9 model-selection default. The
  first honest cold start authed in <1 s (`AUTHENTICATING → CONNECTING → bridge.open →
  CONNECTED`), workspace bridge "Synthetic One" visible in the list.

## F2. NEW P0 live bug: Retry-from-FAILED cannot recover (new, live-reproduced)

Live logcat, Connection Center → "Retry connection":

```
hub.retry :: user retry from phase=FAILED (single-flight → reconnectNow)
hub.connect :: cold-start connect begin (kind=RELAY_ZAI)
hub.connectFailed :: IllegalStateException: connect() is the first-connection path; use recover() (state=FAILED)
```

The 1.1.0 fix covered Retry-from-**CONNECTED** only; Retry-from-**FAILED** routes to
`reconnectNow`, which calls the cold-start `connect()`, which refuses in FAILED state — the
identical crash class the release notes claim fixed. **User impact: after any connection
failure, the manual Retry button never recovers; only a force-stop/cold start does.**
(The rig can now reproduce this deterministically for the fix's regression test.)

## F3. Model picker: populated-list + one-tap pick verified live; geometry measured (new)

With `getView` scripted, the composer picker rendered its real grouped list on-device:
provider headers ("Z.ai", "OpenAI-compatible") + 4 models under the **"Close"-titled header
(88 px tall) — the E2 bug seen live a second time, now with content under it**. Live-measured:

- Row pitch ≈ 111 px ≈ **42 dp — under the 48 dp touch minimum** (audit estimate confirmed).
- One-tap pick flow works end-to-end: tap `glm-5.1` → sheet closes → chip reads `zai/glm-5.1`.
- The conversation-side two-step flow remains code-evidence (F5 blocks a live session).

## F4. Form factors are broken — new P1/P2 bugs, live-measured (new)

- **Landscape:** the header renders as **vertical one-character columns** — live geometry:
  "Tasks" h=129 w=58, "MockBook" h=158 w=39, "Connected" h=159 w=39, "+ New task" h=155 w=50,
  "+ New automation" h=256 w=50. Same narrow-column class as the field-documented vc32 chips
  bug (weight/`fillMaxWidth` interaction), now on the Tasks header + actions in landscape.
- **200% font scale:** everything scales except the **"+ New task" FAB label clips to 35 px**
  while "Refresh" scales to 92 px — violating the app's own §30 invariant ("200% font scale
  must not clip primary actions"; the typography doc claims "no fixed-height containers on
  text" — the FAB contradicts it).

## F5. Rig fidelity ledger + one copy nit (new)

- The mock scripts no automations RPC → the section renders "Automations are unavailable on
  this connection" **while Connected**. Copy nit: an errored channel reads as a
  capability statement; distinguish "desktop didn't answer" from "not supported".
- `createTask` is accepted (`outcome=Accepted`, bootstrap `tasks=1 dropped=0`) but the list
  projection stays "No tasks yet" — the Phase-6-era scripted task row no longer fully decodes
  into the current list projection. Mock-shape drift (real desktop task creation works — the
  phone-created kickoffs exist), so flagged as rig work, not an app bug: refresh the scripted
  row to the current Task decode, then the live conversation/two-step verification unlocks.

## F6. Release-config quick findings (new)

- **No monochrome layer** in the adaptive icon — Android 13+ themed icons unsupported.
- **`isMinifyEnabled = false`** in release — the 53 MB debug APK size carries into release;
  no R8 shrinking/optimization configured.
- **Baseline profiles are measured but never consumed**: the `baselineprofile` module exists
  (Startup/ConversationScroll benchmarks) yet `app` wires no `profileinstaller`/profile —
  the cold-start wins the benchmarks measure are left on the table.

## F7. Plan deltas

- **P0 additions:** Retry-from-FAILED recovery fix (F2 — now reproducible via the rig:
  AUTH_FAILED state + Retry → assert recover path, no IllegalStateException).
- **P1 additions:** landscape header/action layout fix (F4 — same weight/fill fix family as
  vc32, needs the rig or a Robolectric width-qualifier pin); FAB label 200%-scale clip (F4);
  monochrome icon layer, R8 enablement + baseline-profile consumption (F6).
- **P2 additions:** automations "unavailable vs errored" copy split (F5).
- **Rig backlog:** refresh the scripted task row to the current Task decode → unlocks live
  conversation + two-step picker verification (the last code-evidence item).

---

# PART G — deeper round, pass 7: the named symptom, live (appended 2026-10-13)

The F5 rig backlog is cleared — three stacked mock defects diagnosed and fixed — and with a
fully live session the **user-named model-picker behavior was reproduced and captured on
device, exactly as reported**. Mock fixes ship with this pass (self-tests green).

## G1. The F5 mystery solved — three stacked mock defects (new, fixed)

"bootstrapApplied tasks=1 workspaces=1" while Room stayed at zero was three bugs deep:

1. **The default bootstrap task row used the legacy `{id,title,status}` shape** — no
   `workspacePath`. The app's parser keys tasks by `workspaceIdentity ?: workspacePath`, gets
   `""`, and the `if (key.isBlank()) continue` guard silently skips the write. The log's
   "workspaces=1" counts the blank group — logged applied, zero persisted. (Diagnosed by
   pulling the app's Room DB over `run-as`: `SELECT COUNT(*) FROM tasks` = 0, and
   `task_workspace_meta` = 0 — the transaction never ran.)
2. **`MockTask` hardcoded Nov-2023 timestamps** — even an override row would be inserted and
   then **instantly deleted by the store's 7-day stale sweep**, which runs inside the same
   `replaceAll` transaction (`evictAndSweep(atMs=now)` vs `updatedAtMs=2023`). Timestamps are
   now recency-relative.
3. **The state block never emitted** (`emitStateKeys` defaults false) and no `sessionConfig`
   was scripted — so the conversation's mode/model chips (the §10.3 config echo plane) could
   not render at all. `MockRelayMain` now enables state keys, scripts a faithful
   `sessionConfig` (required keys: provider/model/thought + thoughtLevels + modelSelection),
   and serves the E9 model-selection default.

Live proof: Room row persists (`Synthetic task:idle @/synthetic/ws-one`), the list renders the
workspace group, and the conversation shows live chips + correctly formatted timestamps.

## G2. The named symptom — live-reproduced with checked-state evidence (new, on-device)

Full sequence on the live conversation screen, real hierarchy dumps:

1. Header chips render: `mode: build` · `zai/glm-5.1 · high`. Tap the model chip →
   **"Switch model"** dialog (proper title — the "Close"-title bug is composer-only).
2. Radios: `glm-5.1 checked=true` (current), all others false. Rows measure 154 px ≈ **59 dp**
   — the conversation picker is correctly sized (the 42 dp offender is composer-only; B1/F3
   scope sharpened).
3. **Tap `glm-5.1-air`** → dump: **`checked=true` stays on `glm-5.1`; the tapped row reads
   `checked=false`** — "when you click on a model it doesn't actually highlight/pick it",
   verified with the radio's own checked attribute. Simultaneously **"Thought level for
   glm-5.1-air" appears at the bottom of the dialog** with effort chips — "it changes at the
   bottom of the model picker saying the correct name", verified. Extra confusion captured:
   the effort chips show **`• high`** — a bullet marking the *old session's* level, which
   reads as if "high" were already selected for the new model.
4. Tap `medium` → dialog closes → chip updates to **`zai/glm-5.1-air · medium`**. The flow
   DOES complete — the defect is purely the missing intermediate acknowledgment (no pending
   highlight + the second step hidden below the fold + ambiguous bullet). **B1's fix spec
   (pending highlight + thought step attached to the tapped row) is now evidence-backed.**

## G3. Mode dialog verified live; one rig gap noted (new)

Tap `mode: build` → the 4-mode dialog renders with localized desktop parity titles ("Ask
before changes" / "Edit automatically" / "Plan mode" / "Run with fewer confirmations.") and
the radio on the current mode; tapping "Plan mode" closes the dialog and the app sends
`sendConversationCommandV4` (acked on the wire). The chip did not re-render — traced to the
mock: its `switchCollaborationMode` handler drops the `modelSelection` key when rebuilding
config, and its state patch appears to be lost after the resync the model switch triggered.
Rig fidelity item (the app sent and acked correctly); not an app defect.

## G4. Rig operations notes (new — for every future session)

- The mock is **single-session**: after restarting the mock you must also cold-restart the app
  (mid-session reconnects hang at `auth_ack timed out` — the mock's auth state machine doesn't
  re-arm). Restart both, always.
- `pkill -f MockRelayMain` does **not** match the gradle-worker process — stale mocks survive
  and hold :18100 (two listeners round-robin = intermittent auth flaps). Kill by port:
  `lsof -ti tcp:18100 | xargs kill -9`, then verify one listener and its start time.
- This 8 GB Mac hit ~28 MB free mid-pass and the emulator was OOM-killed: run `gradle --stop`
  + `pkill -f KotlinCompileDaemon` before long rig sessions.
- ATD pixel capture remains dead (D7); hierarchy dumps remain the ground truth.

## G5. Plan deltas

- No new app-side P0/P1 items — this pass *proved* existing ones (B1's picker fix now carries
  live checked-state evidence; F3's composer-only 42dp/“Close”-title scope sharpened).
- **Rig backlog update:** remaining item is only the `switchCollaborationMode` echo after
  resync (G3) — everything else on the F5 ledger is done and regression-pinned by
  `RawWsServerTest` + the mock self-tests.
