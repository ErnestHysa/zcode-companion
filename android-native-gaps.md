# Android-Native Gaps Report — macOS ZCode features not ported (findings only)

| | |
|---|---|
| **Date** | 2026-10-06 |
| **App under review** | 0.11.4-phase11 (vc36), commit `cac1bb3` |
| **macOS reference** | ZCode desktop 3.14.4 (running) + open-source clone `~/Desktop/DEVPROJECTS/zcode/repo` @ `29628c9` (packages/ui = the desktop renderer; packages/shared = wire schemas; packages/services = service adapters) |
| **Method** | Desktop renderer/service/source read line-by-line per feature; Android tree grepped per feature; wire schemas checked in `packages/shared/src/zcode-protocol-v4/` |
| **Status** | **FINDINGS ONLY — nothing implemented, nothing committed** |
| **Rule for "how it should be done"** | Cites the desktop's own mechanism + the architecture section that governs it; every new wire surface lands spec-first (`docs/protocol/` + fixtures, §41.2) |

---

## 0. Executive summary

The Android app ports the **conversation/control spine** of ZCode (pair → tasks → chat → approve → recover) but ships almost none of the desktop's **information surfaces** (context window, plan quotas, usage analytics) or **secondary command surfaces** (message edit, slash commands, automations, plugins). None of the big items require new transports — they are:

- **(A) data already arriving in frames we decode but discard** (context usage, slash commands, timestamps);
- **(B) channels verified present but never ported** (usage-stats, coding-plan-subscription, plugins, automations);
- **(C) pure UI work** (message copy/select, timestamps rendering, collapsible workspace groups, files/git/mode/model polish).

Per-feature class table at §10. Items in class (B) marked ⚠ overturn §13.3 classifications ("Never"/"Hidden") — each needs an ADR + §26 threat-model delta **before** implementation (§41.2/§41.6).

---

## 1. Context window usage ("xxxx/1M (80%)" + per-source breakdown)

**macOS:** `packages/ui/src/chat-input-toolbar/contextUsage.tsx` — an input-toolbar panel aggregating three sections: Context windows, Coding Plan usage, Start Plan balance. Data shape (`packages/shared/src/zcode-task-types-core.ts:890-911`):

```
ZCodeUsageUpdate { type: "usage_update", size, used, cost?, cache?, breakdown? }
breakdown: ZCodeContextUsageBreakdownItem[]   // per-source estimate: messages/system/tools/skills/MCP…
```

The desktop adapter exposes it on the task snapshot runtime (`zcodeTaskServiceAdapter.ts:1573-1575, 5258-5269` — `contextUsageFromRuntime(snapshot.runtime.contextUsage) ?? contextUsageFromProjection(...)`, incl. `breakdown` passthrough at :5269).

**Android:** the v4 conversation snapshot is decoded in `data/session/ConversationGateway.kt` → `SessionProjectionState.fromSnapshot` reads ONLY the state keys control/inputRouting/queue/pendingInteractions/backgroundWorks/config/revision (`domain/model/SessionState.kt:19-30`). `contextUsage` is not in the schema mapping, not in `SessionProjectionState`, not rendered anywhere (grep: zero hits outside protocol constants).

**Gap class:** (A) — likely already arriving inside every snapshot; a new optional state key with per-key INV-8 tolerance.
**How:** add `contextUsage` to the §9 state-key inventory in `docs/protocol/v4-wire.md` (capture from a live snapshot first — one session with real traffic), extend `SessionProjectionState` + the key-level merge, render a panel in the conversation header (used/size + % + breakdown list). Protocol surface addition → fixture + §41.2 spec-first.

## 2. Coding-plan quotas: 5-hour limit %, tool-calls %, MCP %, resets

**macOS:** `packages/ui/src/CodingPlanUsageRemainingPanel.tsx` + `chat-input-toolbar/CodingPlanContextUsage.tsx` + a full quota-reset celebration flow (`components/coding-plan-quota-reset/*`, `lib/codingPlanQuotaResetUi.js`). Wire data: `packages/shared/src/usage-quota.ts` — `UsageQuotaSnapshot { level, limits: UsageQuotaLimit[] }` where each limit carries `type, period, usage, remaining, percentage, nextResetTime, usageDetails[]`. Delivered over the **`coding-plan-subscription`** channel (`channels.ts:116`) — a push subscription, not polling.

**Android:** absent entirely. §13.3 classifies `coding-plan-subscription` as **"Never"** (provider-credential adjacency was the concern) and `usage-stats` as P2/Hidden.

**Gap class:** (B) ⚠ **overturns a §13.3 "Never"** — needs ADR-020 + threat-model delta (what the channel exposes: quota buckets/percentages — no credentials in the payload per `usage-quota.ts`; the adjacency concern is resolvable but must be written down).
**How:** port the channel as R-subscribe (read-only), decode `UsageQuotaLimit[]`, render a quota panel (5-hour %, tool-calls %, MCP %, next-reset countdown). The "reset" button on macOS drives the same channel's reset flow — port read-only first, reset action second (it spends plan quota; treat as a destructive-adjacent action with confirm).

## 3. Usage dashboard / analytics (tokens, models, peak, streaks, per-model)

**macOS:** `packages/services/src/usage-stats/usageStatsService.ts` (`getSnapshot` via `usage/stats`, :117) → `UsageStatsSnapshot` (`packages/shared/src/usage-stats.ts:140-159`): `summary`, `daily[]` (gap-filled for trend charts), `heatmap`, `models[]` (per-model tokens), `tools[]`, `peakDailyTokens`, `favoriteModel`, `peakhour`, `streaks`, `source: "bigmodel-monitor" | "local"`. UI: the Settings dashboard.

**Android:** absent (`usage-stats` never ported; §13.3 "P2, hidden fallback").

**Gap class:** (B) — channel is §13.3-P2 (sanctioned to port eventually, R-classified read-only).
**How:** port `usage/stats` as a one-shot R call (it is request/response, not a push), decode `UsageStatsSnapshot`, add a Settings → Usage screen (summary cards, daily bars, per-model list, tools). Reuse `relativeTimeLabel`/`formatCompactTokenNumber`-style formatting (desktop: `lib/tokenNumberFormat.js`).

## 4. Automations / off-peak tasks

**macOS:** `automation/*` methods (verified §7.2: 5 methods) + the `off-peak-task` channel (`channels.ts:148` "闲时任务管理服务") + desktop-side storage in `~/.zcode/v2/tasks-index.sqlite` (§5.4).

**Android:** absent; §13.3 classifies `off-peak-task` P3 (R-class read-only view sanctioned), automation service never classified.

**Gap class:** (B) — P3 per §13.3; read-only list first (see automations + their state), trigger/edit later (write surface = new protocol actions → fixtures).
**How:** read-only list via `automation/*` reads in `docs/protocol/v4-wire.md` style; surface as a Tasks-tab section; scheduling/creation can stay desktop-only initially (matches §2.2 N7's spirit).

## 5. Plugins / plugin marketplace / skills / MCP management

**macOS:** `plugins`, `plugin-management`, `plugin-sync`, `skills`, `skill-sync`, `mcp-sync` channels (`channels.ts:126-131`); marketplace CDN (`cdn-zcode.z.ai`, §5.5); installer services under `packages/services/src/plugin-sync/`, `plugins/`.

**Android:** absent; §13.3 classifies all of these P3 (R-class viewers, hidden fallback).

**Gap class:** (B) — P3 viewers.
**How:** read-only viewers first: installed-plugins list + enable/disable state display, skills catalog (`skills/referenceCatalog` verified in §7.2), MCP servers list (`mcp/list`). Marketplace *install* actions are write surfaces — keep desktop-only for v1 (installing arbitrary plugins from a phone is also a §26 trust question worth a T-item).

## 6. Message edit (editUserQuery)

**macOS:** an explicit edit entry on user rows → `editUserQuery` command with in-place row replacement (`packages/ui/src/v4/ConversationRowView.tsx:253-255` "user 行的 edit 入口（editUserQuery command，用行内编辑文本替换该轮）", `ChatPromptEditor`).

**Android:** the command CONSTANT exists (`core/protocol/.../V4Command.kt:31`) and the retry classifier knows it (`CommandRetryClassifier.kt:39` — PRE_TRANSMIT_ONLY, row-targeted), but there is **no UI entry, no coordinator method, no row affordance**. `ROW_TARGETING_COMMANDS` require `baseLogEpoch` (verified §7.4.4) — the port's `casCurrency` already carries (revision, logEpoch).

**Gap class:** (C+A) — command plumbing half-exists; needs a coordinator method (`editUserQuery(sessionId, rowId, newText, baseLogEpoch)`) + a long-press/edit affordance on user rows + the §20.3-style state machine for the row replacement (the desktop rewrites the turn — expect an epoch/row churn event we must reconcile).
**How:** spec-first (`docs/protocol/v4-wire.md` §5 addition), then CommandCoordinator method, then UI (long-press user row → edit sheet → send). CAS retry class is already PRE_TRANSMIT_ONLY — match the desktop's edit UX (re-asks the turn; confirm dialog).

## 7. Copy / select / highlight messages

**macOS:** native text selection in the renderer.

**Android:** no `SelectionContainer` anywhere (grep: zero hits); rows are `Text` composables without selection or long-press copy.

**Gap class:** (C) — pure UI.
**How:** wrap the timeline body in `SelectionContainer` (per-row granularity is better for perf: wrap each assistant/user row), add a long-press → copy-row action (clipboard, `ClipData`), plus a copy affordance on code blocks (desktop has per-block copy). Mind §27: copying is user-initiated — no new leak surface.

## 8. Slash commands (/goal, /compact, …)

**macOS:** a full builtin catalog (`packages/shared/src/zcode-slash-command-help.ts`: /help, /login, /compact [instructions], /goal [pause|resume|clear|replace …], and more) PLUS per-workspace custom commands delivered **on the wire**: `workspace-config` topic → `workspaceConfigState.slashCommands[]` `{name, description, inputHint, source: builtin|custom}` (`workspace-config.ts:40-52`). The desktop preserves them for replayable clients explicitly (`zcodeTaskServiceAdapter.ts:1567-1569`: "agent 协议 snapshot 含有 `/compact` 等命令…必须保留，否则 replayable/legacy task restore 会把 UI 的 slashCommands 回填成空").

**Android:** absent — the workspace-config topic is not subscribed, `slashCommands` not decoded, the composer has no command affordance. (v4 `compact`/`goal` command plumbing exists in the verified command set but is not exposed.)

**Gap class:** (C+A) — subscribe the `workspace-config/<workspaceId>` topic (verified §7.4.2 topics list), decode `workspaceConfigState`, render a composer "/" menu fed by the catalog, and map /compact + /goal to the existing v4 commands (`compact`, `goalSession` — both in the verified set, §7.2). Custom commands: send as sendText with the slash text (desktop parity) or expose the command directly — verify which the bridge adapter expects (fixture).
**How:** spec-first for the workspace-config decode; composer "/" trigger + catalog list + args hint (`inputHint`).

## 9. Message timestamps

**macOS:** per-row time label derived read-only from `row.createdAt` (`ConversationRowView.tsx:1314, 1347, 1469` — "时间是 row.createdAt 的只读派生展示").

**Android:** rows carry `createdAt` (double epoch) on `TurnHeader.Base` and `createdAtSeq` on every row (`domain/model/ConversationRow.kt:26-37`) — **never rendered** (RowRenderers has zero time display).

**Gap class:** (C) — pure UI.
**How:** render compact timestamps on user/assistant rows (localized via the §31 `java.time` formatters; full time on tap or in the row meta line). Trivial next to the existing `relativeTimeLabel`.

## 10. Class table (everything, one view)

| # | Feature | macOS evidence | Android today | Class | ADR needed |
|---|---|---|---|---|---|
| 1 | Context window used/size/% | `contextUsage.tsx`; `zcode-task-types-core.ts:890-911` | absent (frames likely already carry it) | A | no (state-key addition, fixtures) |
| 2 | Context breakdown (messages/tools/MCP/…) | `contextUsage.breakdown` (same) | absent | A | no |
| 3 | Coding-plan quotas (5h %, tools %, MCP %) | `usage-quota.ts`; `channels.ts:116` | absent | B ⚠ | **YES — overturns §13.3 "Never"** |
| 4 | Quota resets (+reset flow) | `coding-plan-quota-reset/*` | absent | B ⚠ | YES (same ADR) |
| 5 | Usage analytics dashboard (tokens/peak/streaks/models) | `usage-stats.ts:140-159`; `usageStatsService.ts:117` | absent | B | no (§13.3-P2 sanctioned) |
| 6 | Automations / off-peak list | `automation/*` (§7.2); `channels.ts:148` | absent | B | no (read-only first) |
| 7 | Plugins + marketplace | `channels.ts:126-131`; plugin-sync services | absent | B | marketplace-install: YES (trust) |
| 8 | Skills catalog viewer | `skills/referenceCatalog` (§7.2) | absent | B | no |
| 9 | MCP servers viewer | `mcp/list` (§7.2) | absent | B | no |
| 10 | Message edit (editUserQuery) | `ConversationRowView.tsx:253-255` | constant-only (`V4Command.kt:31`) | C+A | no (fixtures + §20.3-style machine) |
| 11 | Copy / select / highlight | native | absent (no SelectionContainer) | C | no |
| 12 | Slash commands (/goal /compact + custom) | `workspace-config.ts:40-52`; `zcode-slash-command-help.ts` | absent (topic not subscribed) | C+A | no (fixtures) |
| 13 | Message timestamps | `ConversationRowView.tsx:1347,1469` | data present, not rendered | C | no |
| 14 | Collapsible workspace groups | `WorkspaceGroupedTasksSection.tsx` (desktop collapse state) | TaskListScreen groups are static | C | no |
| 15 | Files screen UI overhaul | desktop file trees/explorer | `features/files/ui/FilesScreen.kt` (basic list) | C | no |
| 16 | Git screen UI overhaul | desktop git panel | `features/git/ui/GitScreen.kt` (basic) | C | no |
| 17 | Mode selector UI | desktop mode affordances | `Phase9Components.kt` chips/dialog (functional, plain) | C | no |
| 18 | Model selector UI | desktop model picker | functional dialog, unstyled | C | no |

## 11. Items you named that need YOUR product decision (flagged, not actioned)

- **Coding-plan quotas/resets (§2 above)** — the architecture's §13.3 says "Never" for `coding-plan-subscription`. The payload (`usage-quota.ts`) is quota metadata, not credentials, so the classification looks over-conservative — but overturning it is an architecture change (ADR + threat-model walk), and I will not do that silently.
- **Marketplace installs from a phone** — trust surface (T-item): recommend viewer-only v1.
- **Anything requiring `window-controller`/`settings-sync` class channels** — architecture "Never" (desktop-only management, §2.2 N7): automations *editing* may bump against this; keep read-only until you rule.

## 12. Suggested sequencing (for your ruling — NOT started)

1. **Quick wins, class C:** timestamps, copy/select, collapsible groups, mode/model/file/git UI polish — no protocol risk, high visible value.
2. **Class A:** contextUsage + breakdown + slash commands (data mostly on the wire already; one spec-first pass each).
3. **Class B (ADR first):** usage-stats viewer → coding-plan quotas (read) → resets → automations list → plugins/skills/MCP viewers.
4. Phase 12 (release) can proceed independently — none of the above blocks signing/pipeline; but shipping BEFORE class A/C items means the field canary keeps reporting their absence as "gaps" rather than bugs.

## 13. Honest limits of this investigation

- The desktop **renderer** on this machine is the compiled bundle; UI behaviors were read from the open repo (`packages/ui`) at `29628c9` — a 3.14.4 desktop may render slightly newer variants (the mode-vocabulary check earlier this phase already proved 3.14.4 matches `29628c9` for modes).
- Quota/usage payloads are provider-specific (BigModel monitor vs local aggregation — `usage-stats.ts:154`); the Z.ai 5-hour semantics you described map to `UsageQuotaLimit{period, nextResetTime}` but exact Z.ai field values were not captured (needs one live `usage/stats` capture — same fixture discipline as §41.2).
- Nothing above has been implemented, committed, or spec'd — findings only, per instruction.
