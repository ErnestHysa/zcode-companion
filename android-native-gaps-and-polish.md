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

---

# PART H — deeper round, pass 8: the desktop's UI surface + Android platform integration (appended 2026-10-13)

Parts A–G diffed the desktop's **facades** (569 methods), **channels**, and **wire fields**.
This pass diffs the desktop's **user-facing surface** — the layer none of those see — via the
renderer's i18n message catalog (`renderer/assets/IntlProvider-*.js`, the complete UI string
table: ~14k keys, `settings.*` alone is 3,565), cross-checked against the main bundle and
chunk inventory. Desktop is still 3.14.5 (CFBundle 3.14.5.7961 — no drift). Second leg: an
Android platform-integration sweep (manifest, widget, theme, search, gestures). Live rig pass
skipped this round — every claim below is code- or bundle-verified; the rig recipe (E7/G4)
stands for live-verifying H1.1/H1.3 later.

## H1. Desktop UI-surface inventory — new parity gaps (new, verified)

1. **Command Center + task search — the desktop has an in-app search/palette tier; the phone
   has none.** `commandCenter.*` (20) + `quickPick.*` (57): one omnibox with scopes
   **commands / conversations / files**, history, recent tasks + recent changes; `taskSearch.*`
   (9): dedicated task search whose results carry per-task **search snippets** — carried on the
   tasks projection itself (`visibleLimit` / `search=` / `workspaces=` parameters and
   `searchSnippetsByTaskKey` observed in `styles-Qlp0Bew7.js`), not a one-shot RPC. Phone
   verified zero-search: `TaskListScreen.kt` has no search UI (grep: 0 hits), no palette
   anywhere. **Consequence:** if the phone's tasks-topic accepts the same projection params,
   task search is pure UI; `queryConversationCommandsV4` (C2) already covers the commands
   scope. Investigate the tasks-topic param surface → "Command-Center-lite".
2. **The desktop ships its own phone client — a browser one.** `webRemoteControl.*` (208):
   `mobileHome` / `mobileShell` (chat title, reconnecting) / `mobileQr` + `qrAlt` /
   **`refreshQr` with a confirm dialog** / task status / workspace switching /
   `statusTag.phone` / `invalidMobileConnection`. Two consequences: (a) the QR-regeneration
   affordance E1 wished for **already exists on the desktop** (in the web-remote section) —
   the pairing flow just doesn't offer it; (b) A2's differentiation note sharpens: the native
   app's competitor for "control ZCode away from the desk" is **ZCode's own web mobile
   client**, not just Telegram bots. Positioning, not code.
3. **Markdown rendering parity is a bigger cliff than B2.15.** Desktop bundle carries
   `markdownTable.*` (copy-markdown, **download-CSV**, preview pane, scroll modes), `codeBlock.*`
   (line-wrap toggle + **mermaid diagram rendering with zoom controls**), **KaTeX fonts**
   (`KaTeX_Main-Italic-*.ttf` — math), and a **Shiki grammar + theme set** (hundreds of
   grammar chunks; `andromeeda` is a Shiki theme). The phone's bounded parser
   (`MarkdownParser.kt`, ADR-015) handles paragraphs/headings/fences/lists/quotes only: no
   highlighting, **no tables** (pipe rows become prose), no nested lists (H3.3), no math, no
   mermaid, single-line blockquotes (each `> ` line = a separate block), no hr/strikethrough.
   For a coding agent's client the code-reading surface IS the product; minimum-set revisit:
   tables, nested lists, syntax highlighting, wrap toggle. Per-code-block copy + monospace
   already exist (RowRenderers.kt:414-422) — the gap is the parser scope, not affordances.
4. **The phone has zero user preferences; the desktop is preference-rich.** Phone-relevant
   desktop `settings.*` groups with no phone equivalent: **appearance** (themeMode
   dark/light/system with preview cards, font size, UI font size, interface zoom — the phone
   follows the system only, `isSystemInDarkTheme()` with no override), **locale picker**
   (`settings.locale`), **notification prefs** (sound; per-category — the phone has
   content-level + live window only), **message-stream display toggles**
   (`messageStreamShowReasoning/ShowTodos`, `toolGroupingChanges/Explore/Terminal`,
   `showLineNumbers`/`wrapLongLines`), **task auto-archive (+days)**,
   `askUserQuestionAutoResolution`. This generalizes B3's theme bullet into a **preferences
   workstream**. (Reasoning *rendering* exists on the phone — `ReasoningRow`,
   RowRenderers.kt:260-285, macOS stream-expanded-then-collapse parity — it's the *toggle*
   that's missing.)
5. **Onboarding exists desktop-side** (`onboarding.*` 124 + `occupationOnboarding.*` 86,
   incl. agents-file migration UI); the phone's first-run is pairing screens only (D7/E4).
   Candidate: a short post-pairing "what your phone can do" pass (approvals, usage,
   automations, live window) — the features exist, their discoverability doesn't.
6. **In-app feedback/tickets** (`feedback.*` 576 + `quickPick.command.myTickets`) — sharpens
   C1's "feedback channel open" row into a real feature (a phone feedback form over the same
   surface is the P3 candidate).
7. **Corrections/sharpenings to Part A2:** the "Claude Code import" is a **12-agent settings
   sync** (`settingsSync.agent.*`: claudeCode, codexCli, goose, openCode, kiroCli, codeBuddy,
   qode, qodeCn, augment, continue, openClaw, agents); and a **plugin store UI** exists
   (`onOpenPluginStore` in the header props; `resourceManager.category.builtinPlugin/
   communityPlugin`) — context for A1's plugin tiers, parity unchanged. `resourceManager.*`
   itself is the desktop's task manager (processes/CPU/memory/storage) — **out of phone
   scope**, documented so future passes don't chase it.

## H2. Android platform-integration sweep (new, verified)

1. **No per-app language** (`android:localeConfig` absent, no `locales_config.xml`) — on
   Android 13+ the system per-app language page offers nothing, despite the fully-maintained
   zh-CN catalog (C5). One XML + one manifest attribute.
2. **No app shortcuts** (no `shortcuts.xml`) — long-press on the icon offers nothing.
   Candidates: New task, Approvals (dynamic shortcut with count), Connection status.
3. **No share-target** (zero `ACTION_SEND` handling) — "share a URL / stack trace → new
   task" is a natural coding-companion flow the app can't participate in.
4. **No physical-keyboard handling** (zero `KeyEvent`/`onKeyDown`) — the desktop is
   shortcut-first (`settings.shortcuts` = 110 keys); Chromebook/keyboard users get nothing.
   Note-only, low priority.
5. **Pull-to-refresh exists ONLY on the task list** (single `PullToRefreshBox`,
   TaskListScreen.kt:168) — Files (static per C4), Git, and Usage have no refresh gesture.
   On Files it is the cheap fallback to the watcher gap.
6. **D1 erratum:** `network_security_config` exists **only in the debug and benchmark source
   sets**; release builds ship the platform default (cleartext blocked). That is *consistent*
   with the pairing policy (`PairingLinkParser.kt:92` rejects cleartext origins outside dev
   loopback — wss-only by design), so no defect — but D1's "network_security_config is
   present — the baseline is fine" overstates what release ships.
7. **B2.5 trigger correction:** the activity sets
   `configChanges="orientation|screenSize|keyboardHidden|screenLayout"` — **rotation does NOT
   recreate the activity**, so `remember(taskId)` loss fires on **uiMode / density / fontScale
   changes** instead (dark-mode toggle → conversation state dies). The backstack-scoped
   ViewModel fix stands; the repro is a theme flip, not a rotation.

## H3. New phone bugs (code-verified)

1. **FGS auto-timeout leaves the Settings switch lying (P2, stale-state class).** The live-
   connection switch and the live-window row's `enabled` read
   `LiveConnectionService.isRunning` — a plain companion `var`
   (LiveConnectionService.kt:129) — into `remember {}` snapshots (SettingsScreen.kt:738-739).
   Toggles keep it in sync manually (:804/:821/:825), but the service's own **30-minute
   auto-timeout** stops the FGS (:41-42) without any observable → after expiry the switch
   still shows ON and the row stays enabled until the screen is recreated. Undermines the
   d3f4425 gray-out invariant in exactly the timeout case. Fix: expose a StateFlow (or
   broadcast) and collect it.
2. **Approvals widget gaps (three, P2):** (a) the whole-widget tap opens `MainActivity` with
   **no extras/deep link** (PendingApprovalsWidget.kt:54-58,91) — with approvals pending you
   land on the task list, not the approvals surface; pass the oldest pending interaction's
   `zcode://task` link. (b) No `android:previewLayout` in the widget info → the Android 12+
   picker preview shows `glance_default_loading_layout` ("Loading…"), not the widget. (c)
   **Reboot seam:** `NotificationBrokerSource.current` is a static defaulting to empty until
   `PendingApprovalsWidgetUpdater.start()` runs in-process (:23-33) — a system-driven widget
   update after process death/reboot renders "**No pending approvals**" until the app next
   runs. The updater doc calls staleness a documented limitation, but reset-to-zero is the
   hostile direction; render the persisted last-count until fresh data arrives.
3. **Nested lists render as prose (bug-shaped slice of H1.3):** `"  - sub".startsWith("- ")`
   is false (MarkdownParser.kt:81) and the ORDERED regex anchors at line start (:86-114) —
   any indented sub-bullet falls to the paragraph branch and renders with literal `- `
   markers mid-sentence. LLM output nests constantly.

## H4. Strengths worth protecting (fairness)

- **Conversation auto-scroll is genuinely well-engineered** — derived `atBottom`,
  follow-bottom guard, the re-entry-with-cached-rows fix, and the viewport-taller-growing-
  message case are all handled deliberately (ConversationScreen.kt:385-441).
- Terminal honors reduced-motion in its scroll behavior (TerminalScreen.kt:77-78).
- The Privacy settings row is an intentional static statement ("No analytics. No
  tracking."), not a dead row (verified before flagging).
- `searchWorkspaceFiles` verified **zero app-side calls** — C7's @-mentions claim stands
  with upgraded evidence (the capability is on the port, unused).

## H5. Method & tooling notes (new)

- **The IntlProvider message catalog is the desktop-UI-surface diff method**: one file
  (`renderer/assets/IntlProvider-*.js`) holds every UI string; the namespace map
  (`settings.*`, `commandCenter.*`, …) is a complete, greppable feature inventory that
  complements the facade/channel diffs — and it should join D3's sensor proposal (fingerprint
  the namespace list per desktop release; a new namespace = new UI surface, exactly the drift
  class Parts B–H keep finding).
- **REA on the desktop renderer is heap-blocked on this machine:** MCP
  `analyze_javascript_application` timed out at its 30 s tool window on both the 5.8 MB main
  chunk and the 4 MB preload dir; the CLI run (correct flags: `--artifact-format`, `--format`
  is OUTPUT-only) completed but **every file exhausted the worker's 1 GB heap** in the
  semantic phase — honest degradation (per-file `resource_constraint`, empty graph, ~300k-token
  envelope withheld). Raising `--max-heap-mb` on an 8 GB Mac that OOM-kills emulators is not
  worth it; the string-level extraction stands in. (Confirms the AGENTS.md REA gotchas live.)
- Live rig pass deferred: no emulator was booted this round; H1.1/H1.3 are the first things
  to live-verify next session via the E7/G4 recipe.

## H6. Plan deltas

- **P0 additions:** H3.1 (FGS-switch observability — small, restores the d3f4425 invariant);
  H3.2a (widget deep link — one-liner).
- **P1 additions:** H2.1 localeConfig · H2.2 app shortcuts · H3.2b widget `previewLayout` ·
  H3.2c widget reboot seam (persisted last-count) — all cheap platform-native wins.
- **P2 additions:** **markdown minimum set** (tables, nested lists, syntax highlighting,
  wrap toggle — ADR-015 revisit, the phone side of H1.3) · Command-Center-lite (H1.1: task/
  conversation search over existing projections; first probe the tasks-topic `search=`
  param) · preferences phase 1 (theme override + notification sound, from H1.4) ·
  pull-to-refresh on Files/Git (H2.5).
- **P3 additions:** onboarding pass (H1.5) · feedback form (H1.6) · physical-keyboard note
  stands note-only.
- **Sensor delta:** add the IntlProvider namespace inventory to D3's facade-extractor leg.

---

# PART I — deeper round, pass 9: the conversation surface itself + viewer/usage/composer parity (appended 2026-10-14)

Method: the desktop's `chat.*` namespace (2,556 keys — the conversation UI's complete string
table) diffed against the phone's actual conversation feature set, and the phone's OWN protocol
layer (`core/protocol/.../V4Command.kt`) diffed against real send sites. Plus code audits of
the phone's file viewer, usage screen, and composer. Desktop unchanged (3.14.5). Live rig leg
infeasible this pass — ~56 MB free at start (3,575 pages × 16 KB; G4's OOM precedent) — so all
claims below are code/bundle-verified, and I1/I2 are the first items to live-verify next
session.

## I1. Nine conversation commands are decoded in the app's protocol layer and never sent by any UI (new, verified — completes E3's circuit)

`V4Command.kt` models the full desktop conversation-command vocabulary; a grep across every
app send site shows these have **zero senders**: `retryTurn`, `forkAssistant`,
`applyFileRewind`, `setAssistantFeedback` (schema enum confirms `like`/`dislike`),
`setAutoDrain`, `setFollowupMode`, `startSavedWorkflow`, `reorderQueueItem`, and
`createSelectionSideSession` (desktop-editor scoped, correctly unused). Combined with E3
(the wire's `canRetry`/`canFork`/`canRewindFiles`/`canEdit` row flags are also unused), the
circuit is now closed: **the wire says which rows allow it, and the protocol layer can already
send it — the missing work is exclusively UI** for Retry/Fork/Rewind/Like-Dislike. Two more
queue gaps in the same neighborhood: the phone queue has send-now + edit, but **reordering
(`reorderQueueItem`) and auto-drain (`setAutoDrain`) are unsent** — the desktop's queue does
both (schema confirms the `queue`/`guide` follow-up modes adjacent to the drain flag).
Goal control is asymmetric by documented decision: `resumeGoal` is wired via `/goal resume`,
while `/goal pause|clear|show` are **deliberately refused client-side**
(SlashCommandParser.kt:8-11, "never a silent prompt") — `pauseGoal` is wire-supported, so
unlocking it is a P3 candidate once goal state renders (D2).

## I2. Conversation-surface affordances the phone lacks (new, verified)

1. **Per-turn change summary card** (`chat.changeSummary.*`: "N files changed", review,
   **reapply**, **revert**, rewind, open-in-editor) — the phone renders flat tool rows; no
   aggregated per-turn change card. Derived from events the phone already receives. Pairs
   with A3.11 checkpoints.
2. **Session status strip** (`chat.statusPanel.*`: branch, changes/clean, agents, ended
   agents/workflows, environment, goal, commitPush) — live git branch + working-tree state
   inside the conversation header; the phone's git channel data never reaches its
   conversation screen.
3. **Session summary panel** (`chat.summaryPanel.*`: duration, goal iteration, running
   background tasks, open running subagent session, display modes).
4. **Context-compaction lifecycle** (`chat.contextCompaction.*`: started / completedAuto /
   failed / retry) — the phone's context meter shows nothing about compaction.
5. **Turn navigator** (`chat.turnNavigator.*`: jump between user queries) — small.
6. **Follow-up suggestions + turn steering** (`chat.followup.*`: send-now / add-to-queue;
   `chat.message.turnSteer.*`: guided/pending) — driven by `setFollowupMode` (I1), already in
   the phone's protocol layer.
7. **Tool-call presentation** (`chat.toolCall.*`, 1,218 keys; `changesGroup`/`executeGroup`
   keys + the H1.4 toolGrouping settings; `toolSlice`/`toolSnapshot`/`bodyPreview` load-more
   keys) — the desktop groups tool rows and lazy-loads long outputs; the phone renders every
   tool as a flat ungrouped full-output row (RowRenderers: zero grouping). Pairs with C7's
   slice methods.
8. **Generated-artifact preview cards** (`chat.previewCards.*`: audio/docx/html/markdown/
   pdf/pptx/text + open-external) — the phone renders none; pairs with C1 media-preview.
9. Desktop per-message keys also confirm fork (with no-checkpoint/unavailable states),
   like/dislike, collapse/expand, and relative dates (`time.yesterday`). **Fairness:** the
   phone's stop control exists and is gated on the authoritative `control.canStop`
   (WriteComponents.kt:508-516) — the correct trust-the-wire pattern.

## I3. The file viewer is a monospace slab (new, verified)

Desktop `codeViewer.*` = 142 keys, plus `showLineNumbers`/`wrapLongLines` settings and Shiki.
The phone's viewer (FilesScreen.kt:201-253) is paged 64 KB monospace slices with a **manual
"Load more" tap** (no scroll-end prefetch), no line numbers, no highlighting, no in-file
search — and **binary/image files render "binary file — use Download"** (:170): the file
browser cannot preview a PNG at all (distinct from A0.1's conversation-attachment gap).
Additive i18n findings: three more hardcoded English strings in the download path
("downloading…", "downloaded N bytes — share sheet opened", "download failed", :213-218),
and `downloadState.startsWith("binary")` is a string-keyed state machine (B2.16 class).

## I4. Usage analytics parity (new, verified)

The phone's usage screen has stat cards (total/peak/longest session/streaks), a heatmap, and
honest loading/error/empty states. The desktop adds a **per-model pie**, **daily/model trend
charts** (`AppUsageModelUsagePieChart` / `AppUsageDailyModelTrendChart` /
`CodingPlanUsageBar|LineChart` chunks; `settings.usage.modelChart/dailyChart/codingPlanRange`
keys) and **cache-hit-rate** + call counts. Modest delta — fold into the existing P2
usage-redesign item rather than a new workstream.

## I5. Composer attachments are picker-only (new, verified)

Photo picker (`PickVisualMedia.ImageOnly`) and `OpenDocument` are wired; **clipboard image
paste and camera capture are absent** (zero paste/camera code — the only clipboard use in the
app is copy-OUT). The desktop's `chat.attachments` (70 keys) includes paste/drag flows. On
Android, paste-image = observe the clipboard + offer a chip (the picker pipeline already
exists); camera = `TakePicture` contract. Cheap native wins for a chat-first app.

## I6. Strengths worth protecting (fairness)

- The conversation command layer is **protocol-complete**: `V4Command.kt` already models the
  desktop's full command surface, so E3/I1 are UI backlog, not protocol debt — the right
  architecture.
- Stop gating on `control.canStop` (not local guessing) is the correct pattern (I2.9).
- The `/goal pause` refusal is documented at the parse site with its rationale
  (SlashCommandParser.kt:8-11) — deliberate scope, visible to future maintainers.

## I7. Method + live-leg status (new)

- The `chat.*` namespace diff is the conversation-surface complement to H1's settings/surface
  diff; the `V4Command.kt`-vs-send-sites grep is the mechanical way to find "decoded but
  unusable" protocol surface. Both repeat per desktop release — candidates for the D3/H5
  sensor legs alongside the IntlProvider namespaces.
- Live rig: infeasible this pass (memory above). The E7/G4 recipe stands; the queue here is
  live-verifying I1's row actions and I2's grouping/status panels once machine memory allows.

## I8. Plan deltas

- **P1 additions:** the three I3 hardcoded strings join the i18n sweep list. Nothing else —
  I1/I2 are feature work and correctly P2+.
- **P2 additions (top of list):** **Retry/Fork/Like-Dislike row actions** (I1+E3: wire flags
  gate → protocol commands exist → UI only) · per-turn change summary card (I2.1) · session
  status strip (I2.2) · tool-row grouping + lazy output slices (I2.7) · paste-image/camera
  (I5) · file-viewer minimums: line numbers, scroll-end prefetch, image preview (I3).
- **P3 additions:** queue reorder + auto-drain (I1) · turn navigator (I2.5) · follow-up
  suggestions via `setFollowupMode` (I2.6) · compaction lifecycle notices (I2.4) ·
  `/goal pause` unlock (I1) · preview cards (I2.8) · summary panel (I2.3).
- No corrections to prior parts this pass; D2's goal-render gap gains its control-side
  counterpart (I1).

---

# PART J — deeper round, pass 10: the un-diffed desktop namespaces + measured theme + the verified-healthy ledger (appended 2026-10-14)

Method: the complete IntlProvider namespace table was re-enumerated (~90 namespaces) and the
namespaces Parts H/I had not diffed were read key-by-key against the phone screens —
`git`(308) + `gitGraph`(54) + `diff`(20), `automations`(174), `taskList`(140),
`workspaceSidebar`(44) + `workspace`(13), `mode`(47), `terminal`(20), `todo`/`planTool`/
`taskTimeline`, `sidebar.usage.plan`(51). Second leg: WCAG contrast ratios **computed** from
the shipped token values (39 fg/bg pairs, both themes). Third leg: the platform/security
surface H2 hypothesized about — manifest, backup rules, deep links, benchmark seeding,
biometric gate. Desktop unchanged (3.14.5). No live-rig leg this pass; the probes queued
below are the first live items.

## J1. The desktop git surface, re-diffed — the phone is closer than Part A implied; two read-only gaps + one write-spec enrichment (new, verified)

The phone's git panel already renders: branch + ↑ahead ↓behind + tracking + detached
(`GitScreen.kt:127-137`), the desktop's four sections in desktop order
(`unstagedSectionOrder`/`stagedSectionOrder`, :226-229), paginated commit history with
load-more (:178-212), and inline diffs through the §22 `DiffViewerContent` + `DiffCountBadge`
(:371 — the component is wired; a pass-10 early suspicion that it was dead code is
**retracted** — it is referenced by import, not by name). New findings:

1. **Last-turn diff section is missing and possibly one probe away.** The desktop has a
   `git.section.lastTurn` / `git.source.lastTurn(Turn)` view ("what did the agent just
   change") alongside staged/unstaged; the phone's port documents the verified source set as
   **only `"unstaged" | "staged"`** (`GitPort.kt:23`). If the desktop's `getChanges` accepts a
   last-turn sourceId (probe), the phone gains its highest-signal git view as one row — it
   pairs with I2.1's per-turn change card from the other direction.
2. **No tree view.** `git.tree.*` (search placeholder, folder toggle, empty-search states) —
   the desktop's change list is a navigable tree with search; the phone renders a flat list.
   Matters only on large repos. P3.
3. **Write-side spec enrichment for A3.17.** The desktop's ADR-gated git surface is fully
   enumerable now: commit dialog with AI **generate/regenerate**, include-unstaged toggle,
   identity-missing state, commit-and-push; push dialog with ahead/behind values, untracked
   description, error copy button; and a **branch switcher with a typed error taxonomy**
   (`branchAlreadyExists`, `branchInOtherWorktree`, `conflictsPresent`, `invalidBranchName`,
   `operationInProgress`, `targetBranchNotFound`, `trackedOverwrite`, `untrackedOverwrite`).
   The A3.17 ADR should spec against THIS vocabulary, not a generic "git mutations" line.
4. `gitGraph` (commit history with parents/merges/refs metrics) — the phone's flat history is
   functionally sufficient; graph rendering is desktop eye-candy. Note-only.

## J2. Automations: opening any non-minute schedule silently rewrites it as Daily 09:00 — and the desktop editor is ~5× richer (new, verified — sharpens B2.1)

- **The full corruption mechanism, now precise** (`AutomationsSection.kt:268-289`): the kind
  is initialized `if (unit == "minute") 0 else 1` — so **every** existing automation whose
  rule is not minute-interval (daily/weekly/monthly/yearly/once) opens the editor with
  **"Daily" preselected and `dailyTime` hardcoded "09:00"** (:271); a bare Save writes
  `ScheduleSpec(dailyHour=9, dailyMinute=0)` (:283-289). B2.1 named the daily-time reset and
  the model drop (`pickedModel` starts null, :273) — the kind *conversion* is the same root
  with a wider blast radius. Two more teeth in the Cron branch: a blank cron field **silently
  saves the default `"0 9 * * *"`** (:286), and the `valid` gate checks only title/prompt/
  interval — **cron syntax is never validated**, so `"abc"` saves clean.
- **The desktop's target shape** (`automations.*`, 174 keys): schedule kinds **once / daily /
  hourly / weekly / monthly-by-dates / monthly-by-weekday / yearly** plus a **custom repeat
  builder** (every N × minute/hour/day/week/month/year, ends option, weekday picker); a
  **schedule preview** line; a **maxRuns** field; lifecycle pause/resume; runs history with
  **pagination, trigger column, status filter, and open-session-from-run** (A3.2's history
  plus this UX); **create-via-chat** (the agent builds the automation from a prompt);
  templates; an **unsaved-changes guard**; a create-limit error; and a workflow tab sharing
  the page (`pageTab.automation|workflow`). The P2 automations redesign should be specced
  against this, with the editor refusing to open unsupported kinds for edit (or round-tripping
  them untouched) until the kind set exists.

## J3. Mode vocabulary is GLM-only on the phone; desktop sessions are multi-runtime (new, verified)

Desktop `mode.*` (47 keys) carries **per-runtime label/description pairs**: claude
(default/acceptEdits/dontAsk/bypassPermissions/plan/auto), codex (readOnly/agent/
agentFullAccess/fullAccess/auto), gemini (default/autoEdit/plan/yolo), glm
(default/build/edit/plan/yolo), opencode (build/plan) — and `taskList.newTask.claude|codex|
gemini|opencode` + `selectProvider` make multi-runtime tasks first-class desktop-side. The
phone pins the **glm four** in the port contract itself ("build|edit|plan|yolo only",
`ConversationPort.kt:170`) and maps exactly those in the chip/dialog (`Phase9Components.kt:
246-271`). Consequence: attached to a claude/codex-runtime task, the mode value decodes
through the fallback chain (wrong icon/label), and the switch dialog offers glm's vocabulary
for a runtime that doesn't define it. Fix direction is the pattern I2.9 already praised:
**drive the mode list from the wire `sessionConfig`** with per-runtime label tables embedded
the way the desktop does. Priority stays P2/P3 pending one probe — how often non-glm runtimes
appear on the phone-reachable desktop in practice.

## J4. Task-row affordances: per-task change stats are decoded and unrendered; the desktop row vocabulary is richer (new, verified — D2/E3 class)

- **`Task.TaskSummary(additions, deletions, files)`** (`CoreModels.kt:82`) has **zero render
  sites** (grep across `features/tasks`: none) — the desktop shows `taskList.changeStats`
  (+N −N on the row). Same decode-but-unused class as E3/D2; pure UI work.
- Desktop row tags with no phone counterpart: `attentionCount`, `permissionTag`,
  `userInputTag`, `cronTaskLabel`/`offPeakTaskLabel`, `workflowRun.liveCount`,
  `stopCountdown`, `markAsUnread` (the wire half is A3.8), `forkedUntitled`,
  `viewModelTrajectory`; `mobileActive` is desktop-side state — note-only.
- **Status-vocabulary probe:** the phone enum is idle/running/waiting/paused/completed/error
  with a tolerant → UNKNOWN parse (`CoreModels.kt:47-59`); the desktop tag set includes
  `ready`/`notReady`/`restoring`/`streaming`/`failed`. If those exact strings arrive on the
  wire the phone renders UNKNOWN rows — no crash (raw preserved), but verify the actual wire
  values before building on either list.
- Fairness note: relative-time labels exist and are used in four places
  (`relativeTimeLabel`) — B2.15's "never refresh" stands, but the utility is there.

## J5. The M3 scheme leaves `surfaceContainer*` unmapped — dialogs, menus, and sheets render the baseline palette, not the designed pair (new, mechanically confirmed)

`LightScheme`/`DarkScheme` (`Color.kt:139-189`) set `surface`/`surfaceVariant`/`inverse*` but
**no `surfaceContainer`, `surfaceContainerLowest/Low/High/Highest`, `surfaceDim`,
`surfaceBright`** — those roles fall back to M3's neutral baseline. Every `AlertDialog`,
dropdown, and (future) `ModalBottomSheet` — the app's dominant chrome per B3's
"dialog-everywhere" — paints M3 default grey inside the designed-slate screens: two palettes
visible in one flow (the B1 stacked-dialog experience is exactly this). Fix is one
role-mapping block per theme, and it lands automatically under B3's sheet migration (sheets
take `surfaceContainerLow`). `outline` and `outlineVariant` are the **same token**, so every
M3 divider renders at strong weight — give dividers their own softer variant.

## J6. Token contrast audit — computed, not eyeballed (new)

All 39 computed fg/bg pairs pass 4.5:1 with three exceptions worth owning:

| Token pair | Light | Dark | Verdict |
|---|---|---|---|
| `textMuted` on surface / bg / surface2 | 3.32–3.76 | 3.67–4.06 | large-text/non-text only; **1 usage** app-side today — a latent trap for the polish rounds |
| `statusWaiting` as text | 3.43 | 8.29 | light-mode WAITING chip labels (11sp, on the 16% tint ≈ similar) sit **below AA small-text**; the `StatusComponents` tone text path is the site |
| everything else (39 pairs) | ≥4.86 | ≥6.13 | pass, most by a wide margin — the designed pair is genuinely good |

Corrections/sharpenings to B3 (fairness): **dynamic color** absence is a *documented
decision* (`Color.kt` header: "dynamic color optional for chrome only; status tokens stay
fixed", Phase 11 ADR gate) — B3's bullet should be read as an ADR candidate, not drift. The
**compact type scale** is likewise `ui-ux/typography.md v2` spec ("operational screens claim
less vertical space; content wins") — the 11sp `labelSmall` is a spec'd choice and remains
the readability-floor candidate, but it is not an accident.

## J7. The terminal is read-only *and* copy-less (new, verified)

`TerminalScreen` contains **zero copy/selection affordance** (grep: no `SelectionContainer`,
no clipboard, no copy action) — a view-only terminal from which the user cannot copy an error
or a command output. The desktop has `terminal.contextMenu.copy/paste`. Paste stays
ADR-gated (A3.18), but copy-out is read-side: a `SelectionContainer` over the visible buffer
or a "copy visible output" action is a cheap native win. (`rememberReducedMotion` IS honored
here — the one Motion.kt consumer; the component is alive, H4 stands.)

## J8. Verified-healthy ledger (new — hypotheses that did NOT survive; future passes should not re-chase them)

- **Backups:** `allowBackup=false` **plus** `data_extraction_rules` excluding root `.` from
  BOTH cloud-backup and device-transfer — the conversation DB never leaves the device
  (manifest §12.4/§26.2-T6 comments match the XML).
- **Deep links:** strict parser (scheme/host/≤1 segment/512-char caps, `DeepLinks.kt`), the
  pending-link slot re-validated after pairing, `singleTask` re-entry — well designed.
- **Benchmark seeding** (`MainActivity.seedForBenchmark`): gated on
  `BuildConfig.BENCHMARK_SEEDING`, which is `false` by default and `true` only in the
  benchmark build type (verified in `app/build.gradle.kts:20,40`) — the exported-activity
  credential-seeding path is inert in release.
- **Platform currency:** targetSdk 36 / compileSdk 37 — current for late 2026 (exceeds the
  Play floor); `enableOnBackInvokedCallback` + `enableEdgeToEdge` are consistent with API 35+
  behavior; FGS `specialUse` carries the required subtype property; widget receiver and
  FileProvider both `exported=false`.
- **Biometric gate:** CryptoObject pattern with a dedicated auth-bound Keystore key, typed
  invalidation on re-enrollment (INV-14, "never a silent fallback"), and honest capability
  classes (`AVAILABLE`/`SECURE_LOCK_ONLY`/`NO_SECURE_LOCK`) with the androidx 1.1.0
  credential-fallback constraint documented at the site; E5's reduced-security consent is the
  correct UX for the `NO_SECURE_LOCK` class.
- **Small things already right:** the git panel's parity basics (J1), the workspace switcher
  + create-into-workspace (§18.2/§6.1), per-row text selection with a UI test
  (`RowCopySelectionUiTest`), tappable markdown links (FIELD fix 2026-10-12), and the §30
  reduced-motion flag consumed by the terminal.

## J9. Method + live-leg status (new)

- The namespace re-enumeration is now **complete** (~90 namespaces table in hand); the
  un-diffed set this pass was git/gitGraph/diff/terminal/taskList/workspaceSidebar/workspace/
  mode/automations/todo/planTool/taskTimeline/sidebar.usage.plan. Remaining un-diffed:
  `bots`(518), `conversationShare`(330), `workflows`(210), `feedback`(576), `login`/`ssh`/
  `remote` (desktop connection management), `whiteboard`, `browser`+`cuaPermission` — all
  either out of phone scope (A3/D5 families) or already covered (A2/A3.3/A3.16/H1.6); no new
  phone-relevant rows found skimming them. `sidebar.usage.plan`'s five-hour vs weekly window
  split is a one-probe row against `CodingPlanQuotaSection` (fold into the P2 usage redesign).
- Contrast ratios: computed from the literal token values with the WCAG 2.x relative-luminance
  formula; the table above is the whole audit, reproducible from `Color.kt` alone.
- Live rig: not run. First live probes queued: J1.1's last-turn `getChanges` sourceId,
  J4's status-vocabulary strings, and the I1/I2 items already queued.

## J10. Plan deltas

- **P0:** no new items — J2's conversion is B2.1's P0 already, but its regression pin must
  widen: seed a **weekly** automation → open the editor → bare Save → assert the schedule is
  unchanged (or an explicit "kind not editable phone-side" gate appears); the same pin for
  the blank-cron silent default.
- **P1 additions:** `surfaceContainer*` role mapping (J5 — one block per theme, rides along
  with any dialog/sheet work) · `outlineVariant` as a distinct softer divider token (J5) ·
  contrast tokens: darken `textMuted` one step or restrict it to large text; AA the WAITING
  chip label in light mode (J6) · terminal copy affordance (J7).
- **P2 additions:** automations editor target shape per J2 (8 kinds, maxRuns, preview,
  structured pickers, unsaved guard, runs status filter + pagination with open-session,
  cron validation) · task-row change stats from the decoded `TaskSummary` (J4) · per-runtime
  mode tables driven by wire sessionConfig (J3) · last-turn git section if the probe passes
  (J1.1).
- **P3 additions:** git tree view + search (J1.2) · plan/todo panel keys fold into D2/A3.12 ·
  usage plan-window split probe (J9) · A3.17's ADR spec against the branch-switcher error
  taxonomy (J1.3).
- **Sensor delta:** D3's facade extractor should also emit the **full namespace→count table**
  (this pass's method) so a desktop release that grows a namespace surfaces mechanically
  instead of by manual re-enumeration.

---

# PART K — deeper round, pass 11: the desktop's design language as the reference spec + the screens E–G never dumped, live (appended 2026-10-14)

Method: two legs no prior pass ran. First, the **desktop's design language itself** — `DESIGN.md`
(the written design system, 537 lines: type scale, radius nesting rules, spacing rhythm, elevation
discipline, motion principle, overlay specs) plus `packages/ui/src/styles.css` tokens — diffed
against the phone's theme as a *brand/spec* question, not an M3-conformance question; token
currency spot-verified in the shipped 3.14.5 asar (`text-ui-base` ×75, `zcode-stream-text-in`,
"Zai Light" ×24). Second, a **live rig leg** (first since G): emulator + RawWsServer mock, driving
the screens E–G never dumped — Files/Git/Terminal/Usage/Settings/Approvals — plus a NEW mock leg
scripting a rich-markdown assistant row and a pending approval. Desktop unchanged (CFBundle
re-checked: 3.14.5.7961 — no drift).

## K1. The desktop has a written design system; the phone implements a different brand (new, verified)

The desktop's UI is governed by `DESIGN.md` + token CSS, and the phone matches **none of its
structural vocabulary** — not because the phone is unstyled, but because it was built against
generic Material instead of this product's system:

1. **Brand color is a different hue.** Desktop `--color-brand` = **sky-400/500** (Tailwind sky
   family) with `--color-primary` = **neutral-950/50** — near-black primary buttons, sky accents.
   The phone's `accent` (mapped to M3 `primary`, `Color.kt:48/104/135`) = **`#3D5AFE` indigo** —
   a Material Indigo A400 the desktop never uses, with indigo filled buttons. Side by side these
   are two different products. Fix direction: retint the phone's accent family to the desktop's
   sky (or write the divergence down as a deliberate companion-brand decision — it currently
   exists as neither).
2. **No radius system.** The desktop's radius is a *nesting rule* (first rounded container
   `xl` → `lg` → `md` → `sm`, dialogs `2xl`, menus `lg`, controls step down by parent). The phone
   theme defines **no `Shapes(...)` at all** (grep: zero) — M3 defaults (4/8/12/16/28dp) apply —
   next to **9 literal `RoundedCornerShape`** call sites (3dp ×4, 4dp, 6dp ×2, 8dp, 10dp). Chips
   at 3dp inside 28dp dialogs: the incoherence J5 found in color, repeated in shape. One
   `Shapes(...)` block + a nesting rule lands most of it.
3. **No elevation system.** Desktop: "layer through background contrast, borders, and radius";
   overlays carry `border-popover-border` + `shadow-md`, toasts `shadow-lg`. Phone: **zero
   `.shadow(...)` anywhere**; `tonalElevation` ×3 (1dp/2dp); dialogs/menus are border-less tonal
   fills (which, per J5, also paint the wrong palette). The overlay language is tonal-only where
   the product language is border-led.
4. **Typography:** the desktop's `text-ui-*` scale (14px base, role table, 9px floor restricted
   to axis furniture) vs the phone's locally-spec'd compact scale (J6's fairness note) — two
   different design authorities. Whatever the phone scale should be, it should cite the desktop's
   as its parent (or the divergence should be written down next to it).

## K2. The desktop's motion vocabulary, extracted — the concrete target for B3's "zero motion" (new, verified)

B3 established the phone has no motion; the desktop's shipped CSS now supplies the **numbers** the
fix should target (all `styles.css`, reduced-motion-guarded at each site):

- **Conversation entrance**: `zcode-stream-text-in` 900ms `cubic-bezier(0.16, 1, 0.3, 1)` with a
  per-marker stagger (`--zcode-stream-animation-delay`) — the desktop's single most
  identity-defining animation: rows/streams *arrive*, they don't pop.
- Collapsible sections: 300ms ease-in-out. Draft-prompt waterfall: 260ms
  `cubic-bezier(0.22, 1, 0.36, 1)`. Reaction pop: 450ms. Alarm ring: 600ms. Markdown image
  loading: 1.8s shimmer.
- DESIGN.md's motion principle matches: "fast and low-drama; subtle fade, zoom, and directional
  slide; never springy in the workspace."

Phone re-census today (post-vc40 + field fixes): `AnimatedVisibility` **0**, `animateContentSize`
**0**, `animate*AsState` **0**, `Crossfade` **0**, `animateItem` **0**, haptics **0**,
`ModalBottomSheet` **0** — B3 stands unchanged. The B1/P1 motion workstream now has a target
vocabulary: NavHost shared-axis ≈ 300ms, row stream-in with stagger, `animateContentSize` on
collapsibles, haptic ticks on send/pick/copy.

## K3. The composer: five equal-weight icon buttons and no designed shell (new, verified)

`ComposerBar` (`WriteComponents.kt:603-700`): the input is a bare `OutlinedTextField` on a flat
`Surface`, flanked by up to **five same-weight 48dp icon buttons** — mic, add (photo picker),
attach-file, stop, send. The send is a plain `Icons.Filled.Send` icon distinguished only by being
last; there is no filled/primary send treatment, no designed input shell, no hierarchy among the
actions (the desktop's `ChatPromptEditor` is a *designed* object — the one approved `rounded-2xl`
shell, with attachments living inside it and segmented primary+overflow patterns elsewhere).
Also: the composer column stacks up to **six strips** above the input (routing hint, pending
sends, attachment strip, slash menu, voice state, send notice) with no collapse policy — B2.12's
top-of-screen pile has a bottom-of-screen twin. Fix direction (rides the B1/P2 sheet-and-shell
migration): a designed input shell, ONE primary send that morphs to Stop (keeping the
authoritative `control.canStop` gating), attach/attach-file/mic folded into a single add-menu,
and a stack budget for the strips.

## K4. The approvals card identifies the task by a raw session-id prefix — or a literal "?" (new, live-captured — P1)

With a real pending approval on the rig, the approvals card's context line renders
**"task ? · 4 min ago"**. Root: `ApprovalsScreen.kt:163` renders
`record.sessionId?.take(16) ?: "?"` — on a real desktop this shows a 16-char hex session prefix
where the user needs the **task name**, and a bare `?` when the record carries no session id.
The desktop's permission dialog names the task/conversation; the phone's highest-stakes surface
names a machine identifier. Fix: resolve the title from the task store by sessionId (the store is
already on the phone); keep `?` only as the last-resort fallback. (The Allow-once/Deny buttons
themselves measured well-spaced on this pass's dump — B2.3's narrow-screen flush/overflow remains
the separate, standing risk.)

## K5. Markdown ground truth — now live (new, live-captured; completes H3.3 and sharpens H1.3)

The mock gained a `demoAssistantMarkdown` leg (heading, inline code, link, nested list, pipe
table, fenced kotlin block, strikethrough) + a `MOCK_APPROVAL=1` env arming a pending approval
(mock self-tests green: `MockZCodeSelfTest`, `RawWsServerTest`). Live hierarchy dump of the
conversation:

- Top-level bullets render (• + item). **Nested sub-items render as ONE prose line with literal
  markers: `- nested sub-item A - nested sub-item B`** — H3.3's parser prediction,
  now on-device fact.
- **The entire 2×2 pipe table renders as one raw text block** (`| File | Status | | ---- |…`) —
  H1.3's "no tables", live-proven on the primary reading surface.
- **`~~temporarily~~` renders with literal tildes** — strikethrough joins the parser's missing
  set (trivial to add in the bounded parser).
- Fairness: the **fenced code block renders correctly** (a "CODE · kotlin" chip + code text with
  angle brackets and newlines preserved), h2/inline-code/link render — the parser's core paths
  work; the missing set is exactly tables, nesting, strikethrough.

## K6. Screen-by-screen live sweep — what E–G never dumped (new, live-captured)

- **Usage leaks raw fault codes into the UI**: the error state shows
  `fault.mock.unscripted:getAppUsageStats` verbatim above its Retry button, while the Automations
  section paraphrases ("unavailable on this connection", F5). One error-copy taxonomy, please —
  joins the C5/i18n workstream.
- **Duplicated page titles live on two more screens**: "Workspace files" over "Files", "Git" over
  "Git" (B2.18's rot, re-confirmed beyond the Files screen).
- **The approvals inbox entry is a 24dp icon-button with a 17px count digit** in the Tasks header
  (live-measured [827..893]×[72..138] with the digit inside) — sub-minimum target + low salience
  for the app's single most important destination (B3 touch-target class, new instance).
- **Unresolved bottom-geometry observation — ledger, NOT a finding:** on the ATD, BOTH bottom
  action rows (conversation composer, Tasks "+ New task") report flush-to-window-bottom with
  severe visible clipping (composer field 44px of ~154px, its buttons 22px of 132px). Taps still
  land — the send was wire-verified live (`sendConversationCommandV4 ok in 57ms`) — and both
  screens *do* declare `navigationBarsPadding()` (`TaskListScreen.kt:113`,
  `ConversationScreen.kt:112-113`), while the ATD window frame reports [0..2148] of the 2280
  display. Pixel capture stays dead (D7), so this cannot be adjudicated from hierarchies alone;
  recorded here so the pixel-image rig recipe (D7) checks this exact geometry FIRST.
- Fairness: the Terminal gate screen reads well (VIEW-ONLY chip + honest sensitive-surface
  explainer + Enable button); the Settings IA is clean (Endpoints / Insights / Plugins / Skills /
  MCP / Diagnostics with one-line explanations); the reduced-security consent re-verified live
  (E5 stands); Files/Git/Usage all carry honest error states with Retry; F5's "Automations are
  unavailable … while Connected" copy nit re-confirmed.

## K7. Verified-healthy ledger additions (new — do not re-chase)

- The **send path works end-to-end on the real wire** (composer → `sendConversationCommandV4` →
  mock ack → list/state flows), so composer clipping (above) is not a functional defect.
- Approval one-tap buttons present and adequately spaced on the card (K4 dump); manual pairing
  entry + consent + first-sync sequence re-verified live for the third time.
- The pairing link parser tolerates a raw `=` where `%3D` was encoded (typed-link entry path
  exercised live); `adb input text` note below is tooling, not an app defect.

## K8. Method + rig notes (new — for every future session)

- **Mock additions (committed with this part, additive, self-tests green):**
  `script.demoAssistantMarkdown` (one rich-markdown assistant row in the snapshot) and
  `MOCK_APPROVAL=1` (arms a pending-approval tool row). Any future fix round can regression-pin
  markdown nesting/tables and the approvals surface against the live rig.
- **adb driving gotchas:** `adb shell input text` runs through the device shell — an unquoted
  `&` background-truncates the URL (use device-side single quotes around the whole remote
  command); `?`/`=`/`%3D` type fine raw. The Files/Git/Terminal chips are separate ROUTES, not
  in-place tabs: press back before tapping the next chip. The approvals inbox is the header
  icon-button (content-desc "Approvals", top-right), not a list row. Fresh-AVD pairing: `pm
  clear` → manual entry → rewritten link (`10.0.2.2`) → consent → Connected worked first try.
- Desktop REA: still not directly usable on this machine (H5's heap verdict stands — not
  re-chased); the token/string extraction method used here is the standing substitute.

## K9. Plan deltas

- **P1 additions:** `Shapes(...)` definition + radius consolidation (K1.2 — rides any dialog/
  sheet work) · **approvals task-title resolution** (K4 — small, high-stakes) · error-copy
  taxonomy: stop leaking raw `fault.*` codes (K6, joins C5's i18n/error workstream).
- **P2 additions:** **brand-alignment one-pager**: sky-vs-indigo + near-black primary buttons —
  adopt the desktop's palette or document the divergence (K1.1; the single most visible
  "different product" signal) · composer shell + primary-send redesign per K3 (rides the B1/P2
  sheet migration) · motion target vocabulary per K2 (rides B3's motion workstream — the numbers
  are now on file).
- **Rig/pixel deltas:** the unresolved ATD bottom-geometry check (K6) is the FIRST item for
  D7's pixel-image rig; the markdown + approval mock legs (K8) are reusable for every fix
  round's regression pins.
- No P0 additions this pass; no prior findings retracted.

---

# PART L — deeper round, pass 12: the first pixel-true visual pass + the last un-diffed namespaces + the task-lifecycle gap (appended 2026-10-14)

Method: three legs no prior pass combined. First, **D7's pixel recipe finally executed** —
`system-images;android-30;google_apis;arm64-v8a` downloaded (the ATD image's capture pipeline was
the only blocker; on the new image `screencap` worked first try), AVD `zcode-pixel` (Pixel 5 shape,
1080×2340 @ 440dpi), the 10-13 vc40 debug build, and the RawWsServer mock (`MOCK_APPROVAL=1`,
markdown leg armed); pairing re-driven end-to-end on a fresh install and **15 screens captured as
PNGs and read as pixels** — the audit's first true visual ground truth. Second, the **last
un-diffed desktop namespaces** (~55 never read key-by-key; asar extraction, values decoded UTF-8 —
completing the J1 enumeration). Third, a code-pin leg for every live observation. Desktop
unchanged (3.14.5).

## L1. K6's bottom-geometry ledger item RESOLVES — there is no clipping on a normal image

Captured pixels of the composer (idle + keyboard-open), the Tasks "+ New task" FAB, and the
approvals card show **everything fully rendered and comfortably inside the safe area**: the
composer sits above the 3-button nav divider with visible clearance, and with the IME open the
whole strip lifts correctly above the keyboard (`adjustResize` + insets working — K6's suspected
`imePadding` gap does not exist). The ATD's "flush-to-window-bottom with severe visible clipping"
was an artifact of that image's window-frame reporting, not an app defect. Ledger entry closed;
D7's pixel rig (now just "the zcode-pixel AVD + screencap") is the standing visual tool. The
J2 automations pin and the F4 form-factor checks can now be pixel-verified in any fix round.

## L2. NEW P2 copy defect: the Terminal consent sentence is glued and half-hardcoded

Live pixels (Terminal gate, light theme): "…you can turn it off at any time.**your machine.** It
is view-only…" — two sentences fused. Root (`TerminalScreen.kt:178-181`):
`stringResource(R.string.terminal_consent_body) + "" + "your machine. It is view-only …"` — the
`+ ""` no-op spacer marks a botched edit, and worse, the entire second fragment is **hardcoded
English concatenated onto a localized string**, so no translation can ever carry it (a new,
worse instance of the C5 i18n class). The fragment itself is also broken English ("at any time.
your machine." — the lead-in "…streams a live shell view of the workspace on your machine" lost
its first half). Fix: one localized string, whole sentence.

## L3. NEW P1: the approvals inbox is approve-blind — the payload exists but never reaches the card

Live pixels: the inbox card reads "Permission — Bash / task ? · 5 min ago" with Allow-once/Deny —
**no command preview**. The user's single most consequential decision on this app is made blind,
while the desktop's permission dialog (and even its *notifications* — `notification.command` =
"命令：{command}", `notification.file` = "文件：{paths}") shows the actual command/files. The data
is already on the phone: `SupportRepositories.StoredInteraction` retains the full
`payload: JsonObject` (`SupportRepositories.kt:42`); only the projection drops it —
`ConversationPort.InteractionSummary` (`ConversationPort.kt:132-139`) carries
`toolName` but no preview field, and `ApprovalsScreen.kt:159` renders the tool name only. Fix
direction: add a bounded one-line preview (stringified `input` truncated to ~1 line, redacted per
the diagnostics rules) to the summary — P1 because it rides the K4 task-title fix on the same
card. (K4's `task ?` reconfirmed live a second time; same fix session.)

## L4. NEW P2/P3: Settings subtitle leaks the raw enum and loses its separators to AAPT

Live pixels: `Relay (QR pairing)· v3.14.3· connected: CONNECTED`. Two independent defects:
1. **Raw enum leak** (`SettingsScreen.kt:295`): `phase.name` is interpolated into
   `settings_connected_suffix`, printing the internal constant `CONNECTED` in all caps. Joins
   K6's fault-code leak in the error-copy/i18n taxonomy (new instance, trivial fix: a localized
   label per phase).
2. **AAPT leading-space trimming**: `settings_version_suffix`/`settings_connected_suffix`
   (`strings.xml:96-97`) begin with a literal space — Android's resource compiler trims leading
   whitespace in string resources, so the " · " separators render as "·" glued to the previous
   word. Classic aapt gotcha; fix is `\u0020` escapes (or build the subtitle with spacing in
   code).

## L5. NEW P3 visual findings, pixel-measured (K1's structural diagnosis, now seen)

- **The Idle status glyph is a minus sign.** `StatusComponents.kt:118` maps
  `IDLE/UNKNOWN → Icons.Filled.Remove` — the app's most common task state renders as a stray
  "—" before the status word (visible on every fresh Tasks list). A dot (filled/outlined) or
  clock glyph is the obvious substitution.
- **"+ New automation" is offered over a permanently-unavailable section.** The header button
  renders unconditionally (`AutomationsSection.kt:135-141`), including in the
  `lists?.error != null` branch (:146) that shows "Automations are unavailable on this
  connection" — on the mock rig (and any relay-only connection) the affordance invites a form
  whose Save cannot succeed. Hide or disable while the channel is unavailable (sharpens F5/J2).
- **Two chip families on one screen.** The conversation header shows outlined transparent chips
  ("mode: build", "zai/glm-5.1 · high") two rows above filled-white bordered chips
  ("Files/Git/Terminal") — K1.2's shape-system absence, now visible as two visual languages for
  the same concept on one screen.
- **Zero-elevation overlays, confirmed in pixels**: the "+ New task" FAB, the scroll-to-bottom
  FAB, and the dialogs all render as flat fills distinguished only by tint — against the
  background they read as inert labels, not raised interactive surfaces (K1.3's elevation
  diagnosis, seen). The task-row selection indicator is a 3px grey rail that reads as a
  scrollbar artifact rather than selection.
- **Keyboard word-suggestions pollute slash-command entry**: with the Gboard suggestion strip
  showing "he / her / here" above a `/he` command, the phone offers autocomplete in a context
  that is not prose. Minor; a `inputType`/`imeOptions` tweak or an explicit `KeyboardType` for
  the command state.

## L6. The last namespaces — five phone-shaped feature gaps (the J1 enumeration completes)

The ~55 namespaces H/I/J never read were read key-by-key. Phone-relevant finds:

1. **conversationShare (165) — the desktop can share a conversation; the phone cannot, and
   Android is the better sharing platform.** Permission tiers (private / link-viewer /
   link-editor with import-and-continue), link generation + copy, and a ~30-entry **typed error
   taxonomy** (running-turn, streaming-row, artifact-type/size/outside-workspace, rate-limit,
   safety-check-timeout…). Zero phone surface. The fix is not a port — it's the Android share
   sheet as the delivery mechanism for whatever the desktop's share service can produce.
2. **offPeak (89) — a whole product the phone doesn't know exists.** Idle-time task queue:
   templates carousel, queue position ("第 {position} 位"), pause/continue with re-queue rules,
   completed/failed notifications, "keep the computer awake" banner, and a tab that shares the
   automations page (`offPeak.tabs.scheduled|idle`). J4's `offPeakTaskLabel` note was the tip;
   the feature is a queued-compute product with its own status vocabulary
   (queued/paused/running/completed/failed/cancelled).
3. **Task lifecycle is create-only on the phone (P2).** `TasksPort` (`TasksPort.kt:39-44`) is
   refresh / retryConnection / repairPairing / observeWorkspaces / createTask — **no delete, no
   archive, no rename, no move**. The desktop ships delete AND archive with explicit
   confirmations (`confirmDialog.taskDelete*` "records cannot be recovered",
   `confirmDialog.taskArchive*` "can be restored later") plus user task **groups** with colors
   and drag-to-organize (`taskGroup.*`, 24 keys — colors, move-to-group, move-to-top, cron
   group). The phone's list only ever grows; "manage my tasks" is the gap, not any one button.
4. **workflows (90) — the saved-workflow hub.** Run with required args, revise-in-chat,
   promote-to-global, move-between-projects, delete-with-confirmation, last-run states
   (never/completed/errored/running/stopped), create-via-chat. The phone's I1 note
   (`startSavedWorkflow` decoded, unsent) is the wire half; there is no hub surface to run
   anything from.
5. **codeViewer (71) — the desktop file viewer is an order of magnitude richer than the phone's
   monospace slab (I3).** Markdown preview/source toggle, SVG preview/source, PDF paging with
   zoom, PPTX slide viewer with thumbnails, **element-level AI-edit and per-line comments that
   post back into the conversation**, media/image preview, wrap-lines toggle, binary detection,
   and a 256KB preview cap with honest oversize copy. Whatever the phone's viewer becomes, this
   is the target vocabulary.

## L7. The last namespaces — one-liners + three sharpeners for existing findings

- **appError (11): the desktop has an error-boundary product surface** — section-scoped failure
  ("this area broke; other areas keep working"), retry-this-area vs reload-app, stack captured
  to diagnostics. The phone has none: any composition crash takes the whole app (adjacent to
  E6's lint-discipline finding — this is the runtime half of robustness).
- **codeBlock (18): Mermaid diagrams** render as zoomable/pannable previews with copy — K5's
  phone-parser missing set grows a fourth member (tables, nesting, strikethrough, mermaid).
- **markdownTable (10):** desktop tables carry copy-as-Markdown, download-as-CSV, and an
  expanded scroll preview — more evidence the phone's raw-pipe rendering (K5) is the outlier.
- **notification (11):** desktop notifications carry payloads (command/files) and a
  plan-approval type — C3's "zero actions" gap is also a "zero content" gap.
- **sidebar (51):** in-app theme picker (light/dark/**system**) and **interface zoom**; the
  phone follows the system (code-verified this pass) and offers neither override.
- Desktop-only, no phone action indicated: settingsSync (imports config from 17 other agent
  CLIs), update/updateDialog/updateReady (auto-update UX; phone is store-driven),
  modelTrajectory (per-call trajectory viewer — P3 debugging parity at most), feedback center
  (288 — ticketed issue reporting; the phone has diagnostics export), bots (253 —
  chat-tool/webhook integrations), browser (41 — in-app browser pane), remote/ssh (83 —
  desktop-side connection wizards), startup (26 — boot states), resourceManager (65 — storage
  manager), startPlan/manualClaimPlan/occupationOnboarding/rewards/codingPlan/login (~170 —
  billing/plan/account surfaces), cuaPermission/subagentDirectory/forms (computer-use consent,
  subagent creation with validated forms), v4Pane/sidePane/titleBar/appHeader/desktopMenu/
  taskNav/welcome/logout (desktop chrome), directoryBrowser/workspaceFileTree/fileTree/
  fileActions (desktop file surface), tokenDebug/developerTools/debugInfo (dev surfaces),
  common (16 shared verbs — useful when the phone grows a shared component library).

## L8. Live-leg ledger + fairness

- **Held up under pixels:** the Settings IA (clean sections, one-line explanations), the
  reduced-security consent flow (third live re-verification), honest error states with Retry on
  Files/Git/Usage, the terminal VIEW-ONLY gate concept, conversation send path, inline-code/link
  rendering, the focused composer's indigo border, the enabled/disabled send transition.
- **New in the ledger (do not re-chase):** bottom-geometry clipping (L1 — resolved as
  ATD-artifact); voice input is REAL (`VoiceInput.kt:78` uses `RecognizerIntent.ACTION_RECOGNIZE_SPEECH`
  — the mic button is not a stub); notification channels exist and are managed
  (`NotificationChannels.ensure`, plus an update channel); task rows carry merged a11y
  descriptions ("Synthetic task, Idle, Synthetic One · 7 min ago") and standard ripples
  (`clickable` at TaskListScreen.kt:599); predictive back is enabled and `adjustResize` set.
- **Second error-leak instance live-captured** (dark mode, post-uimode-switch): the Usage error
  rendered "channel closed: no outbound sink attached" — a raw transport error string, distinct
  from K6's `fault.mock.unscripted:…`. The error-copy taxonomy (K9) needs to own at least these
  two families plus L4.1's enum.
- **C3 live-proof:** the pending approval fired a real heads-up notification ("ZCode needs your
  approval") with no actions and no payload — C3's finding, now on-camera.
- **K6's approvals-inbox touch target re-measured from the dump:** the bell button is 66×66px
  at 440dpi = **24×24dp** (Android minimum 48dp) — confirmed twice now.

## L9. Rig notes (for every future session)

- **Pixel rig recipe (replaces D7's "worth one round"):** one-time
  `sdkmanager "system-images;android-30;google_apis;arm64-v8a"` (~2.5GB; disk had 13GB — the
  ATD image stays for hierarchy-only passes) →
  `avdmanager create avd -n zcode-pixel -k … -d pixel_5` →
  `emulator -avd zcode-pixel -no-window -gpu swiftshader_indirect -memory 2048` →
  `adb exec-out screencap -p` works immediately (100KB+ PNGs vs the ATD's ~black frames).
- **The mock runs via `gradle :tools:mockzcode:mockRelayServer`** (the thin `mockzcode.jar` has
  no Main-Class and no fat deps — `java -jar` fails both ways). Env: `MOCK_PORT`,
  `MOCK_APPROVAL=1` (arms the approval row). Fresh-pair flow: `pm clear` → paste-link entry →
  consent → Connected; the mock link's `mid` passes validation as-is.
- **`adb shell input text` TRUNCATED a 170-char URL at ~42 chars** on this image (twice, at
  different cut points — looks length- and timing-dependent). Type long strings in ≤28-char
  segments in a loop; the earlier single-shot attempt produced a mid-`sid` truncation that the
  parser correctly rejected as BAD_SID (the security gate worked; the rig was lying).
- **Dialog buttons move when content reflows** (the pairing field grows multiline and pushes
  Continue down ~140px; dark dialogs lay out Close differently than light). Dump-then-tap every
  step; never reuse coordinates across screens or themes.
- `cmd uimode night yes|no` switches the app's theme live (system-follow confirmed — the app
  re-themes without restart); use it for dark-pass captures.

## L10. Plan deltas

- **P1 additions:** **approvals payload preview** (L3 — rides the K4 card fix; the store already
  retains the payload, so it is one projection field + one Text line) · regression pins for the
  approvals card: pending record with a `Bash` payload must render a one-line command preview
  and the task title (mock already arms this via `MOCK_APPROVAL=1`).
- **P2 additions:** Terminal consent string fix (L2 — un-glue + localize; one string resource) ·
  Settings subtitle fixes (L4 — localized phase labels + `\u0020` escapes) · task-lifecycle
  surface: delete/archive/rename with the desktop's confirmation copy (L6.3 — TasksPort gains
  the operations; ADR against `confirmDialog.taskDelete*/taskArchive*` vocabulary) ·
  "+ New automation" hidden/disabled while the channel is unavailable (L5).
- **P3 additions:** Idle glyph swap (L5 — one `when` arm) · chip-family unification and
  elevation/radius pass (L5 — rides K1/K9's Shapes work; the pixel evidence is on file) ·
  conversation share via the Android share sheet (L6.1 — needs the desktop share service probe
  first) · offPeak + workflows surfaces (L6.2/L6.4 — P3 pending the product question of whether
  the phone should host queued-compute and workflow hubs at all; the wire half of workflows is
  I1's already-decoded command) · error-boundary scaffold (L7 — section-scoped catch + retry,
  desktop appError vocabulary) · slash-input suggestion suppression (L5).
- **Ledger deltas:** bottom-geometry clipping resolved (L1); do NOT file "mic is a stub",
  "no notification channels", "no row a11y", or "no predictive back" — all four are false
  (L8).
- No P0 additions this pass; no prior findings retracted (K6's ATD clipping observation
  converts from unresolved-ledger to resolved-artifact, which is a retraction of the *symptom*,
  not of any finding).

---

# PART M — deeper round, pass 13: large screens/RTL/notification brand + the never-audited Settings viewers, live, dark, and landscape (appended 2026-10-14)

Method: first, drift re-checks — the desktop is **still 3.14.5** (CFBundle 3.14.5.7961; asar
mtime unchanged since Sep 30) and the app's Kotlin is **unchanged since d3f4425/vc40** (git log
over `app/core/data/domain/features`), so every prior finding stands as written. Second, the
**repo's own design package (`ui-ux/`) is now a stub** — README.md only; the ~18 files the
README indexes (design-system, color-system, typography, mockups/…) exist nowhere in the
workspace, including the `ui-ux/typography.md v2` J6 cited. Third, four legs no prior pass ran:
**large screens / multi-window / RTL** (manifest + code sweep, then live landscape + dark pixel
verification), **the never-audited Settings viewers** (Connection Center, MCP, Plugins, Skills —
populated live via three NEW scripted mock legs, this pass's commit), the **desktop-side
diff** of those same surfaces (`settings.mcp.*`, `settings.plugins.*`, `settings.skills.*` —
the namespaces J9 counted but never read), and **notification brand + cold-start numbers**.

## M1. The design package is a stub — the spec's authority is now one README (new, verified)

`ui-ux/` contains README.md (2026-10-07) and nothing else; the README's folder-structure section
promises 18 files + 15 mockups that no longer exist anywhere on disk. Two consequences:

1. **Process:** the "visual source of truth" is unenforceable — J6's typography citation and any
   future token dispute have nothing to check against. Restore the package (it exists in
   whatever ZIP it came from) or archive the README as historical and write the divergences
   down in-code. RowRenderers.kt:67 still cites "ui-ux component-library v2" in its KDoc — the
   code references a spec the repo no longer contains.
2. **Conformance (the checkable residue):** the README's design-language summary holds up well
   against the shipped app — **fairness/ledger**: YOU/AGENT overlines exist (RowRenderers.kt:90/
   153), the connection dot+label line sits under the app bar (TaskListScreen `ConnectionStatusLine`,
   48dp min-height, tappable), and the **tool blocks with internal status rails are real** — the
   landscape capture shows the Bash-needs-approval row with an amber left rail (first time the
   README's "agent-timeline grammar" claim is confirmed in pixels). The one README claim with
   **zero code**: the "small Z mark on pairing/connection" — no brand mark exists anywhere
   (grep: none; L's pairing pixels: none). Either add the mark or strike the claim.

## M2. Large screens have zero width discipline — and the app is multi-window-reachable (new, P1/P2)

- **Code:** zero `WindowSizeClass`, zero `BoxWithConstraints`, zero `widthIn(max = …)` /
  content max-width anywhere in the app's UI modules. Manifest has no `resizeableActivity`
  (defaults **true**) and no orientation locks — split-screen, DeX, and tablets are all
  reachable form factors the app does nothing for.
- **Live, landscape (dark, zcode-pixel):** the conversation screen renders prose, code blocks,
  and the composer at **full device width** — a 915dp line length (the readability ceiling is
  ~60–75dp×10); the composer's text field stretches ~75% of the screen with three icon buttons
  trailing (K3's shell problem amplified). F4's Tasks-header vertical-columns bug re-measured
  live a second time ("Tasks" 129×58px, "MockBook" 158×39px, "Connected" 159×39px — one-to-two
  characters per line) and it is **Tasks-header-specific**: the conversation header lays out
  fine in landscape, so the fix is the Tasks header's width/weight chain, not a global one.
- **Fix direction:** a `WindowWidthSizeClass` gate (or a single `BoxWithConstraints` content
  wrapper) capping conversation/reader content at ~600dp centered, a two-pane list-detail for
  the task list at expanded width (the desktop's task-list + conversation split is the model),
  and the F4 header fix. This is the concrete "Samsung/foldable would ship it" gap — a foldable
  opened flat today shows a phone UI stretched edge to edge.

## M3. RTL: the app is LTR-locked by declaration (new, note-level P3)

No `android:supportsRtl` in the manifest (defaults false) — the app cannot be mirrored even if
localized RTL. Today this is *coherent*: both shipped locales (en, zh-CN) are LTR, and B3's
"`Icons.Filled.Send` not auto-mirrored" is therefore moot in practice (it bites only if RTL ever
ships, at which point WriteComponents.kt:698 needs `Icons.AutoMirrored`). Record so a future
i18n pass doesn't discover the lock at runtime.

## M4. Notifications carry no brand — every icon is a platform stock glyph (new, P2)

The FGS "connected" notification uses `android.R.drawable.stat_notify_sync_noanim`
(LiveConnectionService.kt:85) and **every broker notification uses
`android.R.drawable.stat_notify_chat`** (AndroidNotificationSink.kt:28) — the approval
notification, the app's single most important push, renders with Android's generic chat-bubble
icon and looks like a message from a person, not a security decision. This is F6's
monochrome-launcher finding's twin in the shade: a small monochrome-aware app icon (or a
designed glyph set per channel) is the fix. Code-verified; the live pixel attempt (toggle FGS →
shade capture) aborted because the Compose switch wasn't hittable from the dump — the code
evidence is conclusive, the pixel is a nice-to-have for the fix round.

## M5. The Settings viewers + Connection Center, finally audited — live, populated, dark (new)

Never captured or diffed before (K6 dumped only their error states). The rig gained three
scripted mock legs (`script.mcpServerStatuses` / `script.listPlugins` /
`script.skillReferenceCatalog` in MockRelayMain — committed this pass, shapes mirror the domain
decoders, self-tests green), so all three viewers were captured **populated**, light and dark:

1. **The raw-fault leak is now the dominant error style: ×3 new screens** (K6's taxonomy
   family grows from 2 to 6+ live instances). McpViewerScreen.kt:106, PluginsViewerScreen.kt:111,
   SkillsViewerScreen.kt:106 all print `result?.error` verbatim — on the rig:
   `fault.mock.unscripted:listMcpServerStatuses` above a Retry button, on all three. Joins the
   C5/K9 error-copy workstream as its largest evidence block.
2. **"Last refreshed 1 min ago ago"** (Connection Center, pixel-confirmed): the template
   `conn_last_refreshed` = `Last refreshed %1$s ago` (strings.xml:27) receives
   `relativeTimeLabel(it)` output — which **already ends in "ago"** (ConnectionCenterScreen.kt:248-
   249). Fix: drop " ago" from the template or pass a bare duration. P2 copy, exact root.
3. **The AAPT whitespace family grows to three instances + a new code-literal sibling.**
   L4.2's leading-space trimming also bites `conn_desktop_version` (`" · desktop %1$s"`,
   strings.xml:15 → "MockBook· desktop 3.14.3", pixel-confirmed) — and the Settings endpoint
   row's active marker is a **hardcoded `"  •"` literal appended in code**
   (SettingsScreen.kt:287, `if (active) "  •" else ""`), rendering "MockBook  •": a glued,
   symbol-only active indicator with no label and no semantics — TalkBack reads a stray bullet.
   Fix family: `\u0020` escapes / build spacing in code / a real Badge with a contentDescription.
4. **Dark-mode contrast: the MCP literal greens/reds fail on dark (J6 table extension).**
   `statusColor`'s `Color(0xFF2E7D32)` / `Color(0xFFC62828)` (McpViewerScreen.kt:138,140) are
   light-theme-weight green/red 800s; on the dark scheme they compute ≈3.0–3.5:1 against the
   surface — below AA for their 12sp `labelMedium`, and visibly dim in the dark capture
   ("connected" nearly sinks into the background). J6's computed table covered theme tokens
   only; these literals are outside it. Same fix family: status roles, not literals.
5. **The Plugins viewer abuses a disabled AssistChip as a state badge**
   (PluginsViewerScreen.kt:192-206): `AssistChip(onClick = {}, enabled = false)` renders the
   ON/OFF state as a *disabled control* — 38%-alpha grey with a colored label — which reads as
   "can't tap" not "enabled", and is the B3 component-misuse class's clearest instance. A
   tonal `Badge`/`Surface+Text` is the primitive. Its diagnostics rows add `"⚠ ${diag.code} — …"`
   (⚠ glyph-as-icon + raw code, :141), and the row leaks a desktop filesystem path fragment
   (`rootPath.substringAfterLast('/')`, :183) as metadata.
6. **All three viewers + MCP: bare-text loading states (B3 family ×3 more), no pull-to-refresh,
   single-shot fetch** — the catalog can silently go stale for a whole session; H2.5's
   pull-to-refresh note gains three more screens, and C4's "static screen" class grows.
7. **The desktop-side diff of the same surfaces (settings.mcp.*/plugins.*/skills.*, extracted
   key-by-key):** the desktop **localizes the exact MCP statuses the phone renders verbatim**
   (`settings.mcp.status.connectedReason/connectingReason/disconnectedReason/errorReason/
   unknownReason` + a `toolCount` line + per-status reason *descriptions*) — so the phone's
   KDoc'd "closed enums display as-is, no invented states" principle has a parity target it is
   missing: the enum values are desktop display *words*, and zh users get English enums where
   the desktop shows Chinese. The desktop pages also carry (read-side, phone-relevant):
   MCP search + empty states + local-vs-plugin grouping + OAuth authorization states; **plugin
   detail pages with 6 component inventories** (agents/commands/hooks/lsp/mcp/skills), config
   with show/hide/clear-secret handling, `versionChanged`/`updateAvailable` states, installed-at/
   source; **skills grouped per agent** (`settings.skills.agent.common/glm/unknown`), detail
   metadata (slug/version/publishedAt), copy-to-common, diagnostics. The phone's flat
   name+description+enum rows are an order thinner on the READ side (the write half stays
   ADR-gated per fb03b80's ground truth). Also new: `chat.toolCall.mcp.*` (callDetails/
   copyResult/parameters/result/wrapLines) and `chat.toolCall.skill.*` (ran/running/args/
   noOutput) — the desktop presents MCP/skill tool invocations as first-class conversation
   blocks; the phone renders them as generic tool rows (I2.7's grouping family gains its
   MCP/skill specifics).
8. **Fairness:** the Connection Center's "Connection states" legend is genuinely good design —
   eight honest states, each with a one-line explanation, the active one marked by
   **dot + tint + label** (never color alone — the README rule kept); Skills is the **only
   searchable catalog** in the app (works, with a proper no-results state); the Skills search
   filters name+description+plugin correctly.

## M6. Quantitative facts (new)

- **Cold start:** 3.13s / 3.34s / 3.54s (`adb am start -W` TotalTime, three force-stopped
  starts on zcode-pixel) — with the standing caveats: swiftshader GPU and no baseline profile
  (F6). The number to beat, reproducible with the L9 recipe.
- **F6.3 precision note:** the APK **does** carry `META-INF/androidx.profileinstaller_
  profileinstaller.version` (profileinstaller arrives transitively via Compose) but embeds **no
  `assets/dexopt/baseline.prof`** — so the dependency is present while the measured profile is
  still unconsumed; F6's claim stands, with "wire the baselineprofile module's output" as the
  precise remedy. Debug APK 53,438,236 bytes (D7's figure re-measured).
- **Rig lesson (new):** the `mockRelayServer` task runs as the Gradle daemon's JavaExec child —
  **`gradle --stop` kills a running mock** (exit 137 mid-serve, diagnosed live). Order matters:
  stop daemons *before* starting the mock, never while it serves. Also: `uiautomator dump
  /dev/tty` output carries trailing junk after `</hierarchy>` — XML-parse only up to that
  marker (regex node-scanning silently skips nodes for the same reason); and the Compose
  Settings switches expose no Switch class/bounds to uiautomator, so pixel-driving the
  notification legs needs coordinate taps at current-layout moments (or Robolectric pins).

## M7. Plan deltas

- **P1 additions:** **large-screen minimums** (M2 — content max-width + WindowSizeClass gate;
  the F4 Tasks-header fix folds in) · **notification brand icons** (M4 — one monochrome-aware
  icon resource, per-channel tint allowed; rides any notification work).
- **P2 additions:** fault-leak cleanup on the three viewers (M5.1 — rides the K9 error-copy
  taxonomy) · double-ago template fix (M5.2 — one string) · AAPT whitespace family + the
  `"  •"` active marker → Badge (M5.3) · MCP status color roles for dark (M5.4) ·
  AssistChip-as-badge → Badge (M5.5) · localized MCP status labels + reasons from
  `settings.mcp.status.*Reason` as the parity target (M5.7 — folds into the viewers' zh pass).
- **P3 additions:** RTL decision note (M3 — supportsRtl stays off until RTL locales exist; fix
  the Send icon then) · viewer richness (plugin detail components/skills agent grouping — the
  read-side parity rows of M5.7) · restore the `ui-ux/` design package or archive the README
  (M1.1 — process, not code) · the Z brand mark on pairing (M1.2 — either add it or strike it).
- **Ledger deltas:** README headline grammar verified implemented (M1.2 — do not re-audit
  YOU/AGENT overlines, connection line, tool rails); profileinstaller nuance (M6); F4 confirmed
  Tasks-header-specific (M2 — the conversation header is innocent in landscape).
- No P0 additions this pass; no prior findings retracted. The FGS pixel leg (M4) is
  code-verified only — the fix round should pixel-verify the new icon from a Robolectric pin or
  a manual toggle instead of the dump-driven attempt.

