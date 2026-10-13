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
