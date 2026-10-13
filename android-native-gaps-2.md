# Android-Native Gaps Round 2 — ZCode desktop 3.14.5 vs Companion 1.1.0 (vc40)

**Date:** 2026-10-13
**Scope:** full re-investigation of desktop-vs-phone feature gaps and UI/UX quality, after
round 1 (`android-native-gaps.md`, all 6 phases / 23 items shipped through 1.1.0-vc40 + d3f4425).
**Findings only — nothing implemented.**

---

## 0. Executive summary

Round 1 closed the conversation/control spine's obvious holes (context meter, quotas, usage,
automations CRUD, message edit, copy/timestamps, viewers, model picker). This round re-enumerated
the desktop side — the **shipped 3.14.5 bundle** (REA extraction + device-host facade diff) and the
**3.14.3 source tree** for readable UI — and found the desktop moved substantially since the last
audit, while the phone surface it exposes grew well beyond what the app actually calls.

Three headline gaps:

1. **The phone sends images it can never see again.** The attachment upload pipeline is fully
   wired, but the conversation renders no image rows at all — `conversationAttachmentReadV4` /
   `conversationAttachmentStatV4` (on the shipped device facade, never called by the app) are the
   read-back path, and the desktop previews media (`ChatMediaAttachmentPreviewDialog`,
   `previewPaneMediaContent`). This is the single most user-visible defect: attach a screenshot,
   and your own message history shows a filename chip, not the image.
2. **An entire observation tier is unused: runs & trajectories.** Seven `conversationWorkflowRun*V4`
   methods (events, node results, artifacts, workspace), `listSavedWorkflows` +
   `listSavedWorkflowRuns`, automation **run history** (`listAutomationRuns` / `deleteAutomationRun`
   / `restartAutomation` — the desktop has an Off-Peak history tab, the phone fires run-now into a
   void), `getModelTrajectory` + `readSessionMessages/Events/Debug`, and
   `listSessionSubagents` (phone renders subagent rows as quiet meta text only).
3. **Task-management parity landed desktop-side and is missing on the phone:** archive
   (`archiveTask`/`listArchivedTasks`/`unarchiveTask`), pin (`setTaskPinned`/`listPinnedTasks`),
   rename, **custom task groups with colors** (`createTaskGroup`/`updateTaskGroupColor`/
   `listGroupedTaskViewStructure`), pause/resume (`pauseTask`/`resumeTask`), and
   `branchTaskFromPrompt` (fork a task). The phone list has none of these actions.

Plus two **corrections to round-1 conclusions** (§2): the "plugins writes are retired" verdict is
only half-verified (the *other* plugin service is retired; the zcodeAgent surface still carries
`installPlugin`/`setPluginEnabled`/`configurePlugin` in the shipped build — one live probe settles
it), and the embedded slash catalog has **drifted** (14 embedded vs 20 builtins in 3.14.5; missing
`/effort`, `/goal`, `/new`, `/plugins`, `/resume`, `/locale`).

Everything in §4 is **phone-reachable per the shipped device facade** — no desktop-side change is
required for Tiers 1–2, which is what makes this round different from round 1's "needs desktop
change" tail.

---

## 1. Method & evidence

| Input | Version | What was taken from it |
|---|---|---|
| Shipped desktop | **3.14.5** (`/Applications/ZCode.app`, asar 312 MB) | device-host facade method inventory (569 async methods extracted from `out/host/index.js`), renderer feature strings, wire-method presence checks |
| Desktop source | `~/Desktop/DEVPROJECTS/zcode/repo` @ `29628c9` (**3.14.3**) | readable UI structure (`packages/ui/src`: 40+ top-level components, settings page registry, slash catalog), `zcodeAgent.ts` interface, `RemoteServiceAccess` channel map |
| Android app | this repo @ `d3f4425` (= released 1.1.0-vc40 + 2 settings fixes) | full screen/port/protocol inventory (independent Explore pass), plus targeted greps (image rendering, attachment reads) |
| REA | MCP + CLI (`rea-agents` 6.4.0) | asar integrity gate flagged the usual benign electron-builder unpacked-native re-sign (node-pty); full-graph CLI analysis was still parsing node_modules at report time — every claim below rests on the targeted extraction above, which is deterministic string/structure evidence from the shipped bundle |

Notes: the shipped `app-update.yml` points at `localhost:8081` (dev feed), so no 3.14.4/3.14.5
release notes exist to split the two; deltas are stated as "since 3.14.3 source". Surface method
presence in the facade ≠ guaranteed wire routing for every method — the round-1 discipline (one
live `v4Call` probe + golden fixture) applies to each item before build.

---

## 2. Corrections to round-1 conclusions

1. **Plugin writes may not be dead.** Round 1 concluded "plugins/setEnabled is RETIRED in the
   desktop itself (`throwRetiredPluginManagement`), so the read-only viewer IS parity." That
   retirement lives in **`pluginManagementService`** (the desktop-Settings management path). The
   **`zcodeAgentService`** — the surface the phone actually talks to — separately exposes
   `installPlugin`, `uninstallPlugin`, `updatePlugin`, `setPluginEnabled`, `configurePlugin`,
   `resetPluginConfig`, `validatePlugin`, `describePlugin`, `addPluginMarketplace`,
   `removePluginMarketplace`, `updatePluginMarketplace`, `restoreBuiltinPlugin`,
   `getPluginsOverview`, `cancelPluginOperation` — **all confirmed present in the shipped 3.14.5
   host facade** (`throwRetiredPluginManagement` also still present, in the same bundle, i.e. both
   paths coexist). Round 1 never probed the zcodeAgent path live. **Action:** one live
   `v4Call("setPluginEnabled", …)` probe against a test desktop decides whether the phone gets
   plugin enable/disable (and possibly install) without any desktop change.
2. **Embedded slash catalog drifted.** `builtin_slash_catalog.json` embeds 14 commands from the
   round-1 capture; the 3.14.3→3.14.5 builtin catalog has **20**. Missing from the phone fallback:
   `/effort` (switch reasoning effort — pairs with the missing `setThoughtLevel`), `/goal` (phone
   supports goal via wire but the menu doesn't list it), `/new`, `/resume`, `/plugins`, `/locale`.
   The live workspace-config topic is still broken for device connections (round-1 relay-adapter
   bug, re-confirmed present in 3.14.5), so the embedded catalog is what users actually get —
   refresh it and re-embed as a build step, not a one-time capture.
3. **Stale comment:** `features/tasks/.../AutomationsPort.kt:8-10` still claims the write half
   "exists NOWHERE" — CRUD shipped 2026-10-12. Doc-only fix.

---

## 3. What's new on the desktop since the last audit (3.14.4→3.14.5)

All verified as shipped-bundle strings/facade methods:

- **Bot channels — the desktop can now be driven from chat apps:** Telegram
  (`createTelegramBotProvider`), WeChat (`createWeixinBotProvider`/`createWeixinChannelRuntime`),
  Feishu (reactions: `addFeishuTypingReaction`…), generic Webhooks; bot CRUD/test/state surface
  (`createAgent`/`listBots`/`testBot`/`getBotStates`…`sendTyping`) and a Bots settings dialog.
  *Product implication:* "control ZCode away from the desk" is no longer the companion app's
  exclusive story. The app's differentiation is native UX (biometric-gated approvals, home-screen
  approvals widget, FGS live window, streaming conversation) — worth leaning into, and the bot
  channels are also a **notification bridge** candidate: a configured Telegram bot could carry
  task-finished/approval pings the relay can't push (§5.6).
- **Model Trajectory pane:** searchable, expandable step timeline with tool payloads
  (`ModelTrajectory*` component family in source; `getModelTrajectory` on the shipped facade).
- **Workspace checkpoints:** `createCheckpoint`/`deleteCheckpoint`/`diffCheckpoints`/
  `restoreBetweenCheckpoints` + `/rewind` and `/fork` (checkpoint-forked sessions,
  `branchTaskFromPrompt`), Checkpoints UI in the renderer.
- **Task management & grouping:** archive/pin/rename, task **groups with colors**, custom group
  view order, timeline tasks section (§4 Tier 2).
- **Assistant message feedback:** `setAssistantMessageFeedback` (👍/👎 on agent turns).
- **Transient interaction cards + MCP elicitation over the wire:** `respondElicitation`,
  `createTransientInteractionCard` family; desktop renders `ElicitationDialog`.
- **Enterprise/team plans & payments** (Stripe card bind, PayPal setup, ZaiPay, enterprise
  orders/quotes, start-plan snapshots) — billing surfaces; likely out of phone scope but the
  quota/entitlement views may want `getStartPlanSnapshot`.
- **Output styles** (`createOutputStyleService`, add/delete style), **Claude Code import**
  (`scanImportableClaudeSessions`, `importClaudeSessions`, agents-file migration), marketing touch,
  force-update config, `probeIntranet` (direct-endpoint discovery — relevant to the phone's
  direct-connection UX).

---

## 4. Phone-reachable surface the app doesn't use (the actionable core)

Class legend: **A** = data/path already proven on the wire (round-1 pattern), **B** = new channel
surface, needs probe+fixture but read-only, **C** = pure UI. ⚠ = write-adjacent, needs an
INV-9-family ADR like round 1's ADR-020.

### Tier 1 — high user value, read-only, low risk (recommend first)

| # | Item | Wire methods (all on shipped 3.14.5 facade) | Phone today | Class |
|---|---|---|---|---|
| 1 | **Render conversation attachments** (own sent images + desktop-side media) | `conversationAttachmentReadV4`, `conversationAttachmentStatV4`; preview via `attachmentPreviewSourceV4` | sends via `attachmentBegin/Chunk/CommitV4`; **zero image rendering** (no image row type, no bitmap decode in `features/conversation`) | A |
| 2 | **Automation run history** | `listAutomationRuns`, `deleteAutomationRun`, `restartAutomation` | CRUD + run-now; no feedback trail after run-now | B |
| 3 | **Workflow runs viewer (read-only)** | `conversationWorkflowRunsV4`, `…RunEventsV4`, `…RunNodeResultV4`, `…RunArtifactsV4`, `…RunArtifactReadV4`, `…RunArtifactDataV4`, `…RunWorkspaceV4`; saved-workflow viewer: `listSavedWorkflows`, `getSavedWorkflow`, `listSavedWorkflowRuns` | nothing — a running dynamic workflow is invisible on the phone | B |
| 4 | **Reasoning-effort picker** | `setThoughtLevel`; `/effort` | composer has mode + model chips, no effort | A |
| 5 | **Subagent drill-in** | `listSessionSubagents` | subagent rows are quiet meta text | A |
| 6 | **Per-task token usage** | `getTaskTokenUsage` | context meter is session-level only | A |
| 7 | Catalog + corrections | refresh embedded 14→20; `AutomationsPort` comment | stale | C |

### Tier 2 — richer surfaces, mostly read-only

| # | Item | Wire methods | Phone today | Class |
|---|---|---|---|---|
| 8 | **Task actions: archive / pin / rename** (+ archived viewer, pinned section, unread badge) | `archiveTask`, `unarchiveTask`, `listArchivedTasks`, `archiveStaleTasks`, `deleteArchivedTask(s)`, `setTaskPinned`, `setTaskUnread`, `listPinnedTasks`, `renameTask` | long-press has nothing; no archive/pin anywhere | B |
| 9 | **Custom task groups** (desktop groups tasks into named, colored groups with order) | `createTaskGroup`, `deleteTaskGroup`, `renameTaskGroup`, `updateTaskGroupColor`, `listGroupedTaskViewStructure`, `applyGroupedTaskViewOrder` | static per-workspace grouping only | B |
| 10 | **Pause / resume / continue task** | `pauseTask`, `resumeTask`, `continueTask` | stop only | B |
| 11 | **Checkpoints & rewind preview** | `conversationFileRewindPreviewV4`; `gitCheckpointService`: `createCheckpoint`, `diffCheckpoints`, `restoreBetweenCheckpoints`; `/rewind`, `/fork`, `branchTaskFromPrompt` | nothing; turn diffs only | B (restore ⚠) |
| 12 | **Plans viewer** | `conversationPlansV4` | nothing | B |
| 13 | **Trajectory viewer v1 / conversation search** | `getModelTrajectory`, `readSessionMessages`, `readSessionEvents`, `readSessionDebug` | no in-conversation search; no step timeline | B |
| 14 | **Elicitation & transient cards** | `respondElicitation`, `create/update/deleteTransientInteractionCard` | unknown handling — if a desktop MCP server elicits while the phone drives the session, the phone likely can't answer | B |
| 15 | **Assistant message feedback** | `setAssistantMessageFeedback` | nothing | A |
| 16 | **Import shared conversations** | `importShare`, `importShareInternal`, `publish`/`publishInternal` (desktop has ConversationShareMenu + permission picker) | nothing; a desktop-shared link can't be opened on the phone | B (publish ⚠) |

### Tier 3 — write-adjacent, decision-gated (INV-9 family ADRs)

| # | Item | Wire methods | Note |
|---|---|---|---|
| 17 | **Git mutations, incl. AI commit messages** | `stage`/`stagePaths`, `unstage`/`unstagePaths`, `discard`/`discardPaths`, `commit`, `generateCommitMessage`, `createBranchAndSwitch`, `switchBranch`, `getBranchComparison`, `getLocalBranches` | `generateCommitMessage` from a phone review of the turn diff is a killer feature; but INV-9 (phone never mutates the repo) was a founding architecture decision — needs the ADR-020 treatment |
| 18 | **Terminal input** | `ITerminalService.write` (channel already forwarded) | phone terminal is view-only by gate; input = same ADR family |
| 19 | **Plugin writes** | `setPluginEnabled` et al. (§2.1) | gated on the one live probe; if alive, enable/disable is low-risk, install is a trust surface (round-1 §11 ruling was viewer-only v1) |
| 20 | **Skill/command/hook/sync surfaces** | `getSkillReferenceCatalog` (have), plus commandsService/hooksService/skillSyncService/mcpSyncService/pluginSyncService channels (forwarded per `legacyRemoteWorkspaceRpcContract`) | hooks **trust grant** (`grantWorkspaceHookTrust`) is the phone-relevant one: a session can block on hook trust with no way to grant from the phone |

---

## 5. UI/UX quality improvements for what already exists

1. **Conversation:** add image rows (Tier 1.1); in-conversation search; raise the 2,000-char tool
   output cap with an expand route (desktop expands into a trajectory pane); artifact rows could
   open a lightweight preview (markdown/code/images client-side — desktop previews
   PDF/PPTX/DOCX/XLSX/media in `previewPane*`, which the phone's Files screen also lacks).
2. **Tasks list:** no swipe actions, no pinned/archived surfaces, static groups (Tier 2.8/2.9 fix
   the data side; the UI work is swipe-to-archive/pin, a pinned section, group editor with colors).
3. **Automations form:** desktop composes schedules from natural-language instructions
   (`AutomationInstructionsComposer`) and ships a template catalog
   (`automationTemplateCatalog`); the phone form is raw every-N/daily/cron fields. Port templates +
   "describe it and pick a suggestion" before falling back to cron.
4. **Files screen:** markdown/image preview client-side; keep 8 MiB download card for binaries.
5. **Settings parity gaps worth closing:** memory viewer is **not** phone-reachable (memoryService
   is not on the device facade — desktop-only, document it), computer-use/browser status rows
   (`cuaPermissionService` observation events exist), Claude-import status is desktop-only.
   Consider surfacing `probeIntranet` to make "Add direct endpoint" auto-discover desktops on the
   LAN instead of manual URL entry.
6. **Notifications:** still no push via relay (honest copy stays). Two honest bridges exist:
   the FGS live window (current) and — new — a user-configured bot channel (Telegram) as a
   push-adjacent sideband; flag as an option, it changes the "no push" copy.
7. **`ui-ux/` package is a stub:** the README's folder structure lists 20 design files + 15
   mockups that don't exist (only README.md remains). Either restore the package (it is the
   declared visual source of truth) or prune the README's references so implementers don't chase
   phantom specs.
8. **Cross-endpoint deep links** still hardcode `existsOnOtherEndpoint = false`
   (`app/.../nav/ZCodeApp.kt:153-158`) — deferred in round 1, unchanged.

---

## 6. Suggested sequencing (for your ruling — NOT started)

- **P1 (fast, visible, read-only):** §4 Tier 1 items 1–7. Item 1 (attachment rendering) is the
  single highest-value fix; items 2–6 are one probe + fixture + screen each by the round-1
  playbook. Include the §2.1 plugin probe (one `v4Call`) even if the ruling is "stay read-only" —
  it settles a wrong conclusion either way.
- **P2 (control parity):** §4 Tier 2 items 8–15, led by task archive/pin/rename + groups (most
  requested-class of any list action), automation history UI, checkpoints/rewind **preview**.
- **P3 (ADRs + big screens):** git mutations w/ AI commit messages, terminal input, plugin
  installs, share import, full trajectory viewer. Each needs its ADR/threat-model delta first.
- The auto-adaptation pipeline (§14 of round 1) should add the builtin-slash-catalog count and the
  device-facade method list as sensors — this round's findings were discoverable mechanically, and
  3.14.4→3.14.5 added a whole bot-channel + trajectory + checkpoint layer that T1 should have
  flagged as surface growth, not just ADDITIVE version movement.

---

## 7. Honest limits

- The device-facade inventory is method **presence**; some methods may be gated by capability
  flags or connection scope at runtime (round 1 hit exactly this with workspace-config subscribes).
  Probe-first remains mandatory.
- 3.14.4 vs 3.14.5 attribution is not possible (dev updater feed, no release notes); everything
  new is stated as "since 3.14.3 source".
- UI behaviors cited from the 3.14.3 source tree; shipped-bundle verification covered the named
  feature strings only (all confirmed present in 3.14.5 renderer assets).
- The full REA evidence envelope (formal application graph over the 312 MB asar) was still being
  produced at report time; nothing in this report depends on it — the extraction-based evidence is
  deterministic. The envelope lands at `/tmp/zcode-desktop-audit/rea-asi.json` if a formal
  Evidence-cited pass is wanted later.
