# v4 Wire Layer — Phase 2 Extraction

Evidence class: `[VERIFIED]` = read directly from the named source on 2026-09-28.
Sources: reference clone `zcode/repo` @ `29628c9` (packages/shared/src/
zcode-protocol-v4/*.ts), read-only. Phase 1 live evidence referenced where it grounds
a schedule (call conventions, counters, acks — see docs/protocol/relay-control-plane.md).

## 1. Handshake (transport.ts) — `[VERIFIED]`

- `hello` (server→client): `{kind:"hello", protocolVersion:3, connectionId, clientMode:
  "desktop-continuous"|"web-remote-replayable", deliveryProfile:"continuous"|"replayable",
  serverTime, capabilities: HostCapabilities, auth:{userId?}}` — `.strict()`; a
  superRefine requires `deliveryProfile` to match `clientMode` (replayable clients
  ↔ "replayable"). `protocolVersion` is a LITERAL 3 → wire-version gate (§39.3).
- `clientHello` (client→server): `{kind:"clientHello", protocolVersion:3, clientId,
  clientKind: "desktop"|"web"|"mobileRemote"|"mobileApp"?, appVersion, capabilities?{
  workspaceHookReviewUi?, workflowRunDeltas?}}` — `.strict()`: a client MUST declare
  `workflowRunDeltas` only if the host's hello carried `workflowRunDeltas === true`
  (old hosts reject unknown keys and the whole handshake fails).
- `HostCapabilities`: required `nativeDialogs`, `localTerminal`, `binaryFrames`,
  `compression: "none"|"permessage-deflate"`; optional `workspaceHookReview`,
  `independentPlanState`, `workflowRunDeltas` — legacy hosts missing optional bits
  mean false; consumers must test `=== true`.
- **Phase 2 decision (Q6 fallback):** the app declares `clientKind: "mobileRemote"`
  (EndpointDescriptor.CLIENT_KIND_DEFAULT). `mobileApp` remains a future experiment.

## 2. Topic frames (wire.ts / wire-codec.ts) — `[VERIFIED]`

`TopicWireFrame` = physical envelope of one logical topic frame:

```json
{ "wireVersion": 3, "kind": "complete"|"fragment",
  "deliveryKind": "initial"|"online"|"recovery",
  "logicalFrameId": "…", "logicalFrameOrdinal": <int>,
  "topic": "conversation/<sessionId>" | "sessions-index/<wsId>" | "workspace-config/<wsId>",
  "subscriptionId": "…",
  // complete:
  "frame": { …topic payload… },
  // fragment:
  "fragmentIndex": 0, "fragmentCount": ≤1024, "logicalBytes": <int>,
  "checksum": {"algorithm":"crc32","value":"8-hex"}, "dataBase64": "…" }
```

- Encoding: JSON-encode the frame; if the complete wire fits the physical budget →
  one `complete`; else equal-size chunks (short tail) with the LOGICAL crc32 on every
  fragment (verified from `encodeTopicWireFrames`). Encoder does a binary search for
  the fragment byte budget and FAILS CLOSED if any fragment overshoots.
- Assembly: concat fragments in index order → total MUST equal `logicalBytes` → crc32
  over the assembled LOGICAL bytes must match. Bounds: ≤1024 fragments, ≤16 MiB
  logical, 32 concurrent assemblies, 32 MiB staged, 30 s timeout (PROTOCOL_V4_LIMITS).
- `deliveryKind` is the publisher's authoritative reservation marker; an unknown
  deliveryKind is a typed fault (never guessed).

## 3. Conversation topic payload (transport.ts `createTopicFrameSchema`) — `[VERIFIED]`

```json
{ "topic": "…", "subscriptionId": "…", "fromSeq": 0, "toSeq": 12, "sentAt": <ms>,
  "payload": {"kind":"snapshot","snapshot":{…}} | {"kind":"deltas","deltas":[…]} }
```

- Snapshot frames carry `fromSeq = 0`.
- Delta ops (delta.ts): `row.appended {row}`, `row.upserted {row}`,
  `row.removed {fromRowId}`, `row.delta {rowId, path, append}`, `state.updated {patch}`,
  plus `workflowRun.*` key-level ops (only when the host declared
  `workflowRunDeltas === true`). Unknown ops are typed `Unknown` and COUNTED (INV-8).
- Row kinds (rows.ts): `turnHeader, userInput, assistantText, reasoning, toolCall,
  artifact, subagent, hookInvocation, timelineMarker`; rows carry `rowId` (monotonic
  in session, never reused) + `kind`. Phase 2 decodes rows generically (raw + rowId +
  kind); typed row models are Phase 4 domain work (§16).

## 4. Subscribe / resync / rowsRange (transport.ts) — `[VERIFIED]`

- `v4/conversation/subscribe` → result `{ack:{subscriptionId, mode:"snapshot"|"resume",
  logEpoch}}`; the initial frame arrives as a POST-RESPONSE notification
  (`v4/conversation/frame`), never inline.
- `v4/conversation/resync` `{subscriptionId, base:{logEpoch,seq}|null, forceSnapshot?,
  topic, connectionId}` → same ack shape.
- `v4/conversation/rowsRange` `{sessionId, beforeRowId?, limit(1..200)}` →
  `{rows[rowId-ascending], atSeq, atRevision, atLogEpoch, hasMore}` — read-only,
  stateless, safe to re-send (protocol comment, verified).
- **Phone-facing call shapes (zcodeAgent.ts + Phase 1 live captures)**: the terminal
  RPC caller passes `{workspacePath, workspaceIdentity?, sessionId, visibility?}`
  (workspace-target form); the trusted host injects `topic`/`connectionId`/`clientMode`.

## 5. Command plane (command.ts) — `[VERIFIED]`

- Envelope: `{commandId (uuid v7, client-generated, retry-stable), clientId,
  sessionId|null, baseRevision?, baseLogEpoch?, type, payload, issuedAt}`.
- CAS sets (verbatim from v3.14.3):
  - `COMMANDS_REQUIRING_BASE_REVISION` = applyFileRewind, forkAssistant, editUserQuery,
    retryTurn, setAssistantFeedback, sendQueuedNow, editQueueItem, reorderQueueItem,
    deleteQueueItem, setAutoDrain, switchModelConfig, switchCollaborationMode,
    setFollowupMode, pauseGoal, resumeGoal.
  - `ROW_TARGETING_COMMANDS` = applyFileRewind, forkAssistant, editUserQuery,
    retryTurn, setAssistantFeedback.
  - **Discrepancy note:** architectural.md §7.4.4's "(e.g. `sendText`)" example is NOT
    in the v3.14.3 set — corrected in place with a `[CORRECTED 2026-09-28]` note.
- Ack: `{commandId, status: accepted|rejected|stale|duplicate|noop|failed,
  reasonCode?, message?, revisionAtDecision, result?}` — dedupe by commandId.

## 6. Flow control (transport.ts) — `[VERIFIED]`

`v4/connection/flow` `{connectionId, state: saturated|drained|closed}`. On the
desktop the method is host→CLI (trusted relay/facade only); how/if the production
relay pushes a flow signal to a terminal-role client is `[REQUIRES VALIDATION]` —
the transport implements the consumer side and treats an absent signal as a safe
no-op (MockZCode emits it; §28.4 consumer semantics are normative regardless).

## 7. Channel call convention (Phase 1 live finding, restated for Phase 2)

ProxyChannel Promise-call args travel wrapped in an ARRAY on the wire
(`RpcValue.Arr([...])`); a bare object arg arrives as `undefined`
(`fault.connection.handshakeRequired`). EventListen args stay raw. The transport's
`v4Call` wraps; the mock's `argAsObject` unwraps.

## 8. Port inventory (this phase)

| Layer | Kotlin file | Tests |
|---|---|---|
| v4 hello/capabilities/TopicWireFrame + assembler + codec | `core/protocol/.../v4/V4Wire.kt` | `V4WireTest` (9) |
| command envelope + CAS + uuidv7 + ack | `core/protocol/.../v4/V4Command.kt` | `V4CommandTest` (7) |
| topics/subscribe/rowsRange/frames/deltas/rows | `core/protocol/.../v4/V4Conversation.kt` | covered via V4WireTest + transport suites |
| suspend channel session (Promise/Cancel mapping) | `core/protocol/.../channel/ChannelSession.kt` | exercised via transport suites |
| transport interface/state machine/policy/mapping | `core/transport/*` | `ConnectionStateMachineTest` (11), `ErrorMappingTest` (7) |
| RelayTransport | `core/transport/.../RelayTransport.kt` | `RelayTransportTest` (7), `CorpusReplayTest` (5), `StormTest` (7) |
| MockZCode (§35.4) | `tools/mockzcode` | `MockZCodeSelfTest` (5) |

## 9. Session state keys (snapshot.ts / delta.ts) — `[VERIFIED 2026-09-30, Phase 7]`

Read directly from the reference clone @ `29628c9` (packages/shared/src/
zcode-protocol-v4/snapshot.ts + delta.ts) for the Phase 7 command/permission plane.

- `conversationSnapshotSchema` — B-side rows plus an A-side STATE BLOCK on the SAME
  object: `control`, `availability`, `inputRouting`, `meta`, `config`, `usage`,
  `queue`, `pendingInteractions[]`, `pendingCommands[]`, `backgroundWorks[]`,
  `subagents?`, `workflowRuns?`, `goal`, `plan`, and the currencies
  `revision: number` + `logEpoch` + `seq`.
- `state.updated {patch}` (delta.ts `statePatchSchema`) — KEY-LEVEL WHOLE
  REPLACEMENT ("键级整体替换（Object.assign），键集合封闭。键内绝不深合并").
  Optional keys: `revision?, control?, availability?, inputRouting?, meta?,
  config?, queue?, pendingInteractions?, pendingCommands?, backgroundWorks?…`.
  Unknown keys are stripped by old desktops (the container is intentionally NOT
  strict) — the client mirrors that tolerance (INV-8).
- Phase 7 consumes exactly these keys:
  - `control: {phase, sessionEnded, canStop, stopState: idle|stoppable|stopping,
    stopTargetKind, activeWorks[{kind, foregroundExecutionId?, startedAt}],
    lastError?, apiRetry?}` — drives the stop control (`stop` payload's
    `expectedForegroundExecutionId?` comes from `activeWorks`).
  - `queue: {items: QueueItem[], autoDrain, pauseReason?: stopped|manual|error}`;
    `QueueItem` = conversationInputIntent + `dispatch.state:
    queued|reserved|promoting` (display fields: `queueItemId`, `text`, `kind`).
    NOT persisted server-side ("CLI 进程死亡即丢") — never mirrored durably.
  - `pendingInteractions[]` — `Q11 RESOLVED: replayable snapshots DO include open
    interactions`: `{interactionId, kind: permission|userInput|workspaceHookReview,
    anchorRowId|null, createdAt, autoResolution?, payload}` where a permission
    payload is `{kind:"permission", toolCallId, toolName, summary, detail,
    freeText?, origin?, display?, fullAccessOption?, options:[{optionId, label,
    kind: allowOnce|allowAlways|deny|custom}]}` and a userInput payload is
    `{kind:"userInput", prompt, freeText, options?:[{optionId,label}],
    sensitive?, questions?:[{question, header, options:[{value,label,…}],
    multiSelect?}], …}`.
  - `inputRouting: {mode: startNow|enqueue|guide|reject|choice, reasonCode?}` —
    `choice` = held queue: the composer must offer `heldQueueDisposition`
    (`clearQueueAndSend` / `keepQueueAndSend`, verified sendText payload keys).
- Client decode policy (INV-8/INV-14 parity with the row plane): each KEY decodes
  independently; a malformed KNOWN key is dropped + COUNTED (state is auxiliary —
  it never corrupts the row projection and never blocks the frame); unknown keys
  are ignored; enum values outside the verified closed sets decode to tolerant
  `Unknown` cases, never guessed.
- Phase 7 command payloads (command.ts, verbatim): `sendText {text,
  attachments?, requestedDelivery?: startNow|queue|guide, heldQueueDisposition?,
  expectedHeldQueueItemIds?…}` (NOT CAS-guarded); `stop
  {expectedForegroundExecutionId?}`; `cancelBackgroundWork {workId}`;
  `sendQueuedNow {queueItemId}` / `editQueueItem {queueItemId, newText}`
  (CAS-guarded — verified set); `resolveInteraction {interactionId,
  answer:{optionId?, freeText?, action?, content?}}` — first-come-first-served,
  a late answer ACKs `noop (reasonCode=proto.alreadyResolved)`.

## 10. Phase 9 read surfaces (transport.ts / command.ts / session-config.ts) — `[VERIFIED 2026-10-03, Phase 9]`

Read directly from the reference clone @ `29628c9` for the Phase 9
diff/attachment/model planes. Channel-method names follow the established
`*V4` zcode-agent ProxyChannel spellings (§7 convention: promise args
array-wrapped, event-listen args raw).

### 10.1 `conversationFileChangesV4` → `v4/conversation/fileChanges`

Params (zcodeAgentService.ts:5445 spreads `{sessionId, target, baseRevision,
baseLogEpoch}` onto the verified `v4ConversationFileChangesParamsSchema`):
`{sessionId, target: {rowId: int≥0, entityId: string}, baseRevision: int≥0,
baseLogEpoch: string}` — the row target addresses a `turnHeader` row; the CAS
currencies come from the session snapshot state. Result
(`v4ConversationFileChangesResultSchema`, transport.ts:572):

```json
{ "files": <int>, "additions": <int>, "deletions": <int>, "state": "active"|"reverted"?,
  "items": [ { "path": "...", "additions": <int>, "deletions": <int>,
               "writeCount": <int>, "toolNames": ["Write", …],
               "patches": [ { "oldStart": <int>, "oldLines": <int>,
                              "newStart": <int>, "newLines": <int>,
                              "lines": [" context", "+added", "-removed", …] } ] } ] }
```

Diff hunks arrive PRE-PARSED (`readonlyDiffHunkSchema`) — unified-diff body
lines prefixed ` `/`+`/`-`; the client never parses patch TEXT on this plane
(the §22 unified-text parser is for the git channel's `getDiff.patch`).

### 10.2 Attachment transaction plane (`V4_METHODS.attachment*`, transport.ts:370-381)

Phone-facing channel methods `attachmentBeginV4` / `attachmentChunkV4` /
`attachmentCommitV4` / `attachmentAbortV4` / `attachmentReadV4`
(zcodeAgent.ts:790-799); the trusted host injects `connectionId` (the v4
hello's connectionId). Verified schemas (transport.ts:820-1010):

- begin params: `{connectionId, uploadId (≤128 chars, `[A-Za-z0-9][A-Za-z0-9._:-]*`),
  sessionId, fileName (1..255, no NUL/CR/LF), mime (RFC token/token),
  totalBytes (0..20 MiB = attachmentMaxBytes), totalChunks (0..64 =
  attachmentUploadMaxChunks), checksum ("sha256:" + 64 hex)}`; zero-byte upload
  ⇔ zero chunks (superRefine). Result: discriminated `{state:"staging",
  nextChunkIndex}` | `{state:"committed", nextChunkIndex, ref}` (idempotent
  resume of a fully-uploaded transaction).
- chunk params: `{connectionId, uploadId, sessionId, chunkIndex, dataBase64}`
  — decoded bytes ≤ 512 KiB (`attachmentChunkMaxBytes`, core.ts:85) enforced
  schema-side. Result `{uploadId, nextChunkIndex}`.
- commit/abort params: `{connectionId, uploadId, sessionId}`; commit result
  `{ref}`; abort result `{}`.
- read params: `{sessionId, ref, target?+attachmentIndex? (must be paired),
  offset, limit (1..512 KiB)}`; read result `{dataBase64, mediaType
  (image/*|video/*|application/pdf only), totalBytes (≤ attachmentPreviewMaxBytes),
  nextOffset: int|null}` — chunked assembly loops until `nextOffset == null`.
- `sendText` payload `attachments: AttachmentRef[]` where
  `attachmentRefSchema = {ref, fileName, mime, bytes, previewRef?}` (strict;
  content bytes never ride the command envelope).

### 10.3 `switchModelConfig` / `switchCollaborationMode` + the `config` state key

- `switchModelConfig {provider: string, model: string, thought: string}`
  (command.ts:208) — CAS-guarded (verified set, §5).
- `switchCollaborationMode {mode: "build"|"edit"|"plan"|"yolo"}` (command.ts:215)
  — CAS-guarded; `auto` is explicitly NOT user-switchable (schema comment).
- Snapshot `config` key = `sessionConfigStateSchema` (session-config.ts):
  `{modelSelection?: {providerId, modelId, options?:{reasoningLevel?}},
  provider, model, thought, thoughtLevels: string[] (current model's actual
  set), followupMode: "queue"|"guide", mode: string (default "build"),
  planEnabled?, permissionGrant?, planTransition?}`. Arrives on snapshots and
  `state.updated` patches (key-level whole replacement, §9) — the echo the
  switch commands reconcile against. The available-models LIST is the separate
  `model-selection` channel (`IModelSelectionService.getView()` →
  `{revision, providers: [{providerId, providerName, templateId, models:
  [{modelId, config}]}], preferredSelection?, …}` — provider/facades.ts:201);
  it is R-classified for the phone (§13.3: W = model switch only, via v4
  commands, never via provider-settings writes).

## 11. Phase 2 surfaces (usage / workspace-config / editUserQuery) — `[VERIFIED 2026-10-07, Phase 2]`

Read directly from the reference clone @ `29628c9` (paths cited per entry) for the
GAP-1/GAP-2/GAP-12/GAP-10 roadmap entries. Golden fixtures for all three surfaces
are GENERATED by committed generator code (`tools/capture/generate-phase2-fixtures.mjs`
→ `fixtures/protocol/phase2-wire-fixtures.json`; rule 5). Live-traffic confirmation
of the `usage` key's arrival rides the Phase-12 canary (the Phase-9/10 precedent:
"live channel authorization deferred to the Phase-12 canary") — the production-relay
capture `fixtures/relay/live-subscribe-16.fixture.json` proves the snapshot-frame
mechanism but its rpc-frame CONTENT was destroyed by sanitization, so it cannot
ground a payload shape; the reference schema can and does.

### 11.1 `usage` state key (snapshot.ts:188-205 `sessionUsageStateSchema`)

The v4 conversation snapshot carries a REQUIRED A-side `usage` key (snapshot.ts:490
`usage: sessionUsageStateSchema`); `statePatchSchema` carries
`usage: sessionUsageStateSchema.optional()` (delta.ts:48). Schema verbatim:

```json
"usage": {
  "contextWindow": {                       // nullable — null = no usable window data
    "usedTokens": <number>, "maxTokens": <number>,
    "autoCompactThresholdTokens": <number|null>,
    "cache"?: zcodeSessionContextCacheUsageSchema,   // legacy-types.ts:462, STRICT
    "breakdown"?: [ { "source": enum, "chars": int≥0 } ]  // item STRICT
  } | null,
  "cumulative": { "inputTokens": <number>, "outputTokens": <number>,
                  "cacheReadTokens": <number>, "cacheWriteTokens": <number> }
}
```

- Breakdown `source` enum (legacy-types.ts:475-483, closed):
  `system_prompt | meta_user_context | skills | tool_prompt | system_tool_schemas |
  mcp_tool_schemas | messages`. Breakdown semantics (schema comment, verbatim):
  "Agent 按来源估算的上下文字符量，用于 UI 展示比例，不作为 token 账本" — chars are
  per-source ESTIMATES for ratio display, never a token ledger.
- Conflation (snapshot.ts:188 comment): "值未变不下发" — the key is omitted from
  patches when the value is unchanged. ABSENCE IS NORMAL (INV-8): a snapshot or
  patch without `usage` keeps the previous projection value; a malformed KNOWN
  `usage` key drops THAT KEY + counts (the §9 decode policy — state is auxiliary).
- Desktop panel mapping (the renderable-usage gate): `getRenderableTaskUsage`
  (contextUsage.tsx:209-220) renders ONLY when `used > 0 && size > 0` (and finite)
  — "used=0 或非法值…不能渲染成可用的 context meter". Label format
  (contextUsage.tsx:104-116): `used/size (pct%)` with compact token numbers and a
  percent formatter (max 1 fraction digit). Breakdown segments (contextUsage.tsx:166-202):
  drop `chars <= 0`, aggregate per source, `percent = chars/totalChars`, sorted by
  chars DESC with a stable source-order tiebreak (messages=0, system_prompt=1,
  meta_user_context=2, skills=3, tool_prompt=4, system_tool_schemas=5,
  mcp_tool_schemas=6). The `cache` block is NOT part of the Phase-2 panel scope
  (roadmap acceptance pins used/size/% + breakdown only).

### 11.2 `workspace-config/<workspaceId>` topic (workspace-config.ts, whole file)

Workspace-level "living config catalog" topic (additive v4 topic; conflated
latest-wins like sessions-index — whole replacement, never a deep merge).

- Topic key: `workspace-config/<workspaceId>` where workspaceId = the §7.11
  workspace identity key (workspaceIdentity ?: workspacePath — the desktop's
  `resolveWorkspaceKey`). Topic payloads validate against
  `workspaceConfigTopicFrameSchema` (transport.ts:259-290 — the topic string MUST
  have a non-empty suffix).
- Snapshot payload (`workspaceConfigSnapshotSchema`):
  `{protocolVersion: 1, workspaceId: string, logEpoch: string (workspace-level
  config-log generation, independent of the session logEpoch), config:
  workspaceConfigStateSchema}`.
- Config state: `{configOptions: WorkspaceConfigOption[],
  slashCommands: WorkspaceSlashCommand[]}` — WHOLE replacement on every frame.
- Slash command catalog entry (`workspaceSlashCommandSchema`):
  `{name: string, description: string, inputHint?: string, source?:
  "builtin"|"custom"}` — `source` absent = legacy payload/capability unknown.
- Deltas: exactly ONE op, `config.updated {config}` (whole replacement; "配置目录
  是小体量整体替换态，不做字段级增量").
- Subscribe: the SAME `v4/conversation/subscribe` RPC with an explicit `topic`
  (the desktop adapter's own `subscribeWorkspaceConfigV4`,
  zcodeAgentService.ts:5562-5584, passes `topic` client-side; ack =
  `{ack: {subscriptionId, mode, logEpoch}}`, transport.ts:458-462). The initial
  frame rides the same post-response notification as conversation topics. Frame
  ROUTING is by topic prefix on the shared `v4/conversation/frame` notification
  (zcodeAgentService.ts:2061-2097 dispatches `workspace-config/` frames to the
  workspace-config emitter) — the phone's existing `onDynamicConversationFrame`
  listen already receives them; only the client's decode must branch.
- Desktop consume semantics to port: slashCommands land in a WORKSPACE-scoped
  bucket (zcodeSessionStoreWorkspaceSlice.ts:899-913 setSlashCommands — whole
  replacement per (workspacePath, workspaceIdentity)); the composer "/" menu lists
  exactly the wire catalog (useSlashCommands.ts — no builtin-only fallback when
  the topic never arrives; the desktop's catalog hydrate writes
  `prepareResult.slashCommands ?? []`, useDraftConfigControl.ts:384-390).
- Preserve-for-replay rule (zcodeTaskServiceAdapter.ts:1566-1569, verbatim
  comment): "agent 协议 snapshot 含有 `/compact` 等命令；task facade 投影时必须保留，
  否则 replayable/legacy task restore 会把 UI 的 slashCommands 回填成空" — a
  client that decodes the catalog must NOT clear it on session restore/re-attach;
  the catalog is replaced only by a NEWER workspace-config frame. (The desktop does
  clear it on NEW-DRAFT creation so a stale session's commands don't leak into a
  fresh draft — zcodeSessionStoreWorkspaceSlice.ts:620-623; the Android equivalent
  is endpoint/workspace-lifecycle, not per-session attach.)

### 11.3 Slash-command send semantics (desktop verbatim port)

`parseV4VisibleSlashCommand` (packages/ui/src/v4/slashCommands.ts) is the desktop's
send-path grammar; SessionPane.tsx:2440-2520 dispatches:

- `/compact` (alias `/compress`) → v4 command `compact` with `{}` — NOT
  CAS-guarded ("compact 是输入型维护命令…不走 CAS", command.ts:148-150); it QUEUES
  when inputRouting.mode ≠ startNow (FIFO input command).
- `/goal <objective>` and `/goal replace <objective>` → v4 command
  `sendGoalCommand {text: <objective>, displayText: <original text>, …submission}`,
  not CAS-guarded.
- `/goal resume` → v4 command `resumeGoal {}` — CAS-guarded (verified set §5).
- `/goal pause|clear|show` → NOT consumed (desktop logs 暂不支持; the text must not
  silently become a prompt). Empty `/goal` → warning, no send.
- `/plan <task>` → client-side shortcut: switch mode to `plan` +
  `planEnabled: true` on THIS submission, then send `<task>` as the sendText body
  (slashCommand nulled — "命令只负责配置 shortcut；后续必须走普通 sendText").
- ANY other `/name` (incl. custom commands) → consumed by NOTHING client-side:
  plain `sendText` with the raw slash text (the CLI's own command registry handles
  it — "同名 CLI 命令优先，不由 App 消费").

### 11.4 `editUserQuery` (command.ts:154-160 + :396-404; ConversationRowView.tsx)

- Payload: `{target: {rowId: int≥0, entityId: string}, newText: string,
  attachments?: AttachmentRef[], workspaceMode?: "preserve"|"rewind"}` —
  attachments OMITTED = preserve the canonical originals ("只有显式透传 []，CLI 才能
  区分'用户删除全部'与'调用方未修改附件'"); workspaceMode default `preserve` =
  branch switch only; `rewind` first restores the turn's files.
- CAS: BOTH currencies required (COMMANDS_REQUIRING_BASE_REVISION +
  ROW_TARGETING_COMMANDS, §5) — the desktop sends `current.revision` +
  `current.logEpoch` from the session projection (SessionPane.tsx:3055-3066).
- ACK `result` (command.ts:396-404): `{type: "editUserQuery", disposition:
  "rewind"|"fork"|"blocked", sessionId, reasonCode?, preview?}` — `fork` is
  legacy-ACK decode compatibility only (new edits never fork); `blocked` carries
  the file-rewind conflict preview (desktop renders a conflict dialog;
  ConversationRowView.tsx:1010-1019).
- Turn replacement (the §20.3-style reconciliation): the desktop rewrites the
  turn — the wire consequence is row churn in the SAME epoch (large rewinds clear
  rows: `row.removed {fromRowId}` "删除该行及之后所有（edit/retry 分支）",
  delta.ts:99-100) or an epoch move (a NEW logEpoch → the client's S9 epoch-mismatch
  escalation snapshots). The client FOLDS: `row.removed` truncates rowId ≥
  fromRowId (streaming buffers for those rows discarded), subsequent
  `row.appended` ops append the replacement turn. NO local optimistic row rewrite
  exists — the projection changes only when the authoritative ops arrive.
- Desktop affordance: edit entry on the user row gated on `row.entityId`
  (ConversationRowView.tsx:1295-1305 — `onEdit && row.entityId`), pre-filled
  inline editor, blank-text-with-no-attachments edits skipped
  (SessionPane.tsx:3048-3050). Android shape: long-press on the user row →
  action sheet (Copy / Edit) → edit sheet pre-filled with the row text → confirm
  (re-asks the turn) → coordinator send.

## 12. Phase 3 read-only viewer surfaces (usage / automations / plugins / skills / mcp) — `[VERIFIED 2026-10-07, Phase 3]`

Read directly from the reference clone @ `29628c9` (paths cited per entry) for the
GAP-5…GAP-9 roadmap entries. ALL FIVE surfaces are read-only R-class reads on
§13.3-sanctioned channels — INV-9 (the typed Kotlin surface exposes NO write
method for any of them: no automation create/update/delete/enable, no plugin
install/uninstall/enable, no skills setEnabled, no mcp mutations) and the §13.3
fallbacks (Hidden / feature-disabled) apply when a host refuses the call. Golden
fixtures are GENERATED by committed generator code (`tools/capture/
generate-phase3-fixtures.mjs` → `fixtures/protocol/phase3-wire-fixtures.json`;
rule 5). Channel-call convention = §7 (promise args array-wrapped); all surfaces
are one-shot request/response — read-only, stateless, safe to re-send (the
`v4/usage/stats` source comment says exactly that: "usage query（模式同
rows/range：只读、无状态、超时重发安全）", transport.ts:363-366).

### 12.1 `getAppUsageStats` → CLI `v4/usage/stats` (GAP-5)

- Phone channel method: `zcode-agent` → `getAppUsageStats`
  (zcodeAgent.ts:592 `getAppUsageStats(params): Promise<AppUsageSnapshot>`;
  the connection scope does NOT override it — passthrough to
  zcodeAgentService.ts:3650-3661, which forwards `{range, timeZone}` to the CLI
  as `V4_METHODS.usageStats = "v4/usage/stats"`). Params (transport.ts:773-777
  `v4UsageStatsParamsSchema`, strict): `{range: "all"|"7d"|"30d",
  timeZone?: string}`. The desktop UI passes the client's IANA zone
  (useUsageStats.ts:210-213 `Intl.DateTimeFormat().resolvedOptions().timeZone`);
  the phone passes `java.util.TimeZone.getDefault().id`.
- Result = `appUsageSnapshotSchema` (transport.ts:778-779 assigns it verbatim as
  `v4UsageStatsResultSchema`; schema lives in usage-stats.ts, NON-strict
  z.object — additive-field tolerant):
  `{range, generatedAt: number, timeZone: string, source: "agent-db" (literal),
  summary, heatmap, dailyModelUsage[], models[], tools[]}` where
  - `summary` = appUsageSummarySchema: `{totalTokens, inputTokens, outputTokens,
    reasoningTokens, cacheCreationTokens, cacheReadTokens, cacheHitRate,
    totalSessions, totalTurns, toolCallCount, toolErrorRate, modelErrorRate,
    avgTimeToFirstTokenMs: number|null, avgTurnDurationMs: number|null,
    activeDays, currentStreakDays, longestSessionMs, longestStreakDays,
    peakDayTokens, favoriteModel: {modelId: string|null, totalTokens, share}|null}`;
  - `heatmap` = `{startDate: string|null, endDate: string|null, maxTokens,
    weeks: [{weekIndex, days: [cell|null]}]}` with cell
    `{date, level: 0|1|2|3|4, totalTokens, turnCount, toolCallCount}`;
  - `dailyModelUsage[]` = `{date, models: [{modelId: string|null, totalTokens}]}` —
    the trend-chart series (gap-filling is a DESKTOP-side concern for the
    legacy monitor snapshot; the agent-db plane ships `dailyModelUsage` as-is
    and the client renders the series it receives);
  - `models[]` = `{modelId: string|null, totalTokens, inputTokens, outputTokens,
    requestCount, share}`; `tools[]` = `{toolName, callCount, errorCount,
    errorRate, avgDurationMs: number|null}`.
- Desktop UI to port (AppUsagePanel.tsx): lifetime summary strip (totalTokens /
  peakDayTokens / longestSession / currentStreak / longestStreak — lines
  138-156), heatmap when `weeks.length > 0` (:86), range tabs default "7d"
  (:39), daily-model trend + per-model usage, refresh; honest empty state when
  the snapshot is absent (:71-97). Formatting = `formatCompactTokenNumber`
  (tokenNumberFormat.ts — the GAP-1 `formatCompactTokenCount` port already in
  core:ui).

### 12.2 `listAutomations` + `off-peak-task` channel `list` (GAP-6)

- Automations: phone channel method `zcode-agent` → `listAutomations`
  (zcodeAgent.ts:677 `listAutomations(params: ZCodeAgentWorkspaceTarget):
  Promise<ZCodeAutomation[]>` — workspace-target form). The CLI-plane word
  `automation/list` (zcode-protocol/index.ts:3647) serves the SAME domain:
  params `{}` strict → `{automations: zcodeAutomationProtocolSchema[]}`
  (index.ts:3480-3484). The channel payload is the RICHER desktop type
  (automation-types.ts:65-113): the protocol face `{automationId, title,
  cronExpr, prompt, modelSelection?, mode?, targetTaskId?, enabled,
  lifecycleStatus: "active"|"completed"|"failed"|"paused", nextRunAt?,
  lastRunAt?, runCount, recurring, maxRuns?, scheduleRule?}` PLUS channel-only
  fields `{workspaceKey, workspacePath, workspaceIdentity?, locationKind:
  "local"|"remote", endAt?, scheduleEditedByUser?, dispatchStatus:
  "claimed"|"dispatched"|"failed_to_dispatch"|"skipped", dispatchAttempts,
  retryAt?, lastError?, createdAt, updatedAt}`. `scheduleRule` (strict,
  index.ts:3323-3337): `{unit: "minute"|"hourly"|"daily"|"weekly"|"monthly"|
  "yearly", interval: int>0, hour: 0-23, minute: 0-59, anchorAt: int,
  weekdays?: [0-6], monthDays?: [1-31], months?: [1-12], monthlyMode?:
  "date"|"weekday"}` — the schema comment pins WHY it must be displayed when
  present: cron cannot express real intervals like "every 50 hours".
- Off-peak: phone channel `off-peak-task` → `list` (offPeakTask.ts:41
  `list(): Promise<ZCodeOffPeakTask[]>`, NO args — Arr([]) like
  `model-selection.getView`). Result items are the desktop type
  (off-peak-types.ts:102-165); the protocol's minimal snapshot face
  (`zcodeOffPeakTaskSnapshotSchema`, index.ts:3521-3530 — "协议侧任务快照：…
  最小字段面。不暴露 serverTicketId") pins the display core `{offPeakTaskId,
  title, status: "queued"|"paused"|"running"|"completed"|"failed"|"cancelled",
  queuePosition?, sessionId?, createdAt}`. The host's own list projection for a
  workspace filters `task.workspaceKey === workspaceKey`, sorts createdAt DESC
  and caps at 20 (zcodeAgentService.ts:2666-2671) — the phone mirrors those
  three steps CLIENT-SIDE (the channel list is unfiltered). Write methods on
  this channel (createTask/cancelTask/pauseTask/continueTask/deleteTask/
  updateTask/deleteHistory) are Deferred scope — NOT in the Kotlin type.

### 12.3 `listPlugins` → CLI `plugins/list` (GAP-7)

- Phone channel method: `zcode-agent` → `listPlugins` (zcodeAgent.ts:608;
  pluginManagement.ts:48 routes to zcodeAgentService.listPlugins which forwards
  `{workspace: buildWorkspaceRef(params), configScope?}` to the CLI word
  `plugins/list`, zcodeAgentService.ts:3880-3883 — including the one retry on
  protocol-timeout so a stale agent recovers). Channel params:
  `ZCodeAgentPluginViewParams {workspacePath, workspaceIdentity?, configScope?:
  "user"|"workspace"}` (zcodeAgentPluginParams.ts:15-17).
- Result = `zcodePluginsListResultSchema` (index.ts:2521-2526, STRICT):
  `{plugins: zcodePluginInfoSchema[], diagnostics: zcodePluginDiagnosticSchema[]}`.
  `zcodePluginInfoSchema` (index.ts:2546-2560, STRICT — "镜像 @zcode/contracts
  的 PluginMetadata, 仅保留 UI 需要的可序列化字段"): `{id, name, description?,
  version?, enabled: boolean, source, marketplace, author?, authorUrl?,
  homepage?, skillCount?: int≥0, skillRootCount: int≥0, commandRootCount:
  int≥0, components?: [{kind: "agent"|"command"|"skill"|"hook"|"mcp", items:
  [{name, description?}]}], declaredMcpServerNames?: string[],
  hostMcpServerNames?: string[], mcpServerNames: string[], hookDetails?,
  rootPath, userConfig?, configuredOptions?, packageStatus?: "missing",
  rootSource?: "user"|"workspace", enabledSource?: "user"|"workspace",
  optionSources?}`. `diagnostics[]` = `{code, message, severity?:
  "warning"|"error", pluginId?}`.
- Viewer scope (roadmap Deferred gate): the Kotlin client type exposes
  `listPlugins` ONLY — install/uninstall/update/enable/configure/marketplace
  methods stay absent (INV-9; §11 trust T-item). Marketplace BROWSING is not
  ported (no already-ported R read carries it).

### 12.4 `getSkillReferenceCatalog` → CLI `skills/referenceCatalog` (GAP-8)

- Phone channel method: `zcode-agent` → `getSkillReferenceCatalog`
  (zcodeAgent.ts:617; zcodeAgentService.ts:3920-3929 forwards
  `{workspace, sessionId?}` to the CLI word `skills/referenceCatalog` via the
  workspace agent client). Channel params:
  `{workspacePath, workspaceIdentity?, sessionId?}`.
- Result = `zcodeSkillsReferenceCatalogResultSchema` (index.ts:2673-2680,
  STRICT): `{authority: "session"|"workspace", skills:
  zcodeSkillReferenceCatalogEntrySchema[]}` where an entry (index.ts:2652-2663,
  STRICT) = `{id, name, description, path, scope: "workspace"|"user"|"plugin",
  enabled: z.literal(true), pluginName?}` — the catalog is the CONVERSATION
  REFERENCE projection ("该协议只承载 Composer 的只读引用投影，不替代 Settings
  的 Skill 管理接口"), so `enabled` is ALWAYS true on this plane and a viewer
  must NOT render an enable/disable affordance from it. `path` is a desktop
  path — display-only metadata (INV-16).

### 12.5 `listMcpServerStatuses` → CLI `mcp/list` (GAP-9)

- Phone channel method: `zcode-agent` → `listMcpServerStatuses`
  (zcodeAgent.ts:607 `listMcpServerStatuses(params:
  ZCodeAgentListMcpServerStatusesParams): Promise<ZCodeMcpListResult>`;
  mcpSyncService.ts:24-31 comment: "原 UI 直调 zcodeAgentService 的 mcp/list")
  — the mcp/list word's host consumption point. Channel params:
  `{workspacePath, workspaceIdentity?, mcpServers?: ZCodeAgentMcpServer[],
  mode?: "connect"|"status"}`; the phone passes `mode: "status"` — the passive
  last-known-status read; `mode: "connect"` triggers REAL server connects /
  listTools (a viewer must not side-effect the desktop's MCP runtime on browse).
- Result = `zcodeMcpListResultSchema` (index.ts:719-724, STRICT):
  `{statuses: Record<serverName, zcodeMcpServerStatusSnapshotSchema>}` where a
  snapshot (index.ts:684-709, STRICT) = `{status:
  "connecting"|"connected"|"disabled"|"disconnected"|"failed"|"untrusted",
  transport: "stdio"|"http"|"sse", toolCount: int≥0, updatedAt: string,
  error?, failureKind?: mcpServerFailureKindSchema (19-value closed enum,
  index.ts:667-683), serverRequestId?, protocolEra?: "legacy"|"modern",
  authorization?: {type: "oauth_authorization_code", authorizationUrl,
  startedAt}}`. Status display renders these fields VERBATIM — no invented
  states (roadmap accept).

## 13. Phase 4 coding-plan quota plane (ADR-020) — `[VERIFIED 2026-10-07, Phase 4]`

Read directly from the reference clone @ `29628c9` (paths cited per entry) for the
GAP-3/GAP-4 roadmap entries. **Channel resolution (ADR-020):** the quota plane rides
the **`usage-stats`** ProxyChannel — `ICodingPlanSubscriptionService`
(`coding-plan-subscription`, channels.ts:116) is the PURCHASE facade
(Stripe/PayPal/products/orders) with no quota member and no `on*` event member; the
desktop's own UI POLLS `getEntitlementSnapshot` (useUsageEntitlement.ts:26-80;
`USAGE_ENTITLEMENT_ACCESS_REFRESH_MS = 60_000` — the desktop comment records merging
12 identical quota RPCs/min). There is NO quota push channel anywhere in the desktop
(grep: zero event members on both channels). The §13.3 overturn is therefore
narrower than the roadmap assumed: `usage-stats` gains the read + the ADR-020-gated
reset words; `coding-plan-subscription` STAYS "Never" (purchases from a phone are
out of scope, no ruling path in this campaign). Payload types are TypeScript
INTERFACES (not zod) — the golden fixtures are validated by the committed generator
TYPE-CHECKING each payload against the verbatim-extracted interfaces
(`tools/capture/generate-phase4-fixtures.mjs` →
`fixtures/protocol/phase4-wire-fixtures.json`; `tsc --noEmit`, rule 5's
committed-generator path adapted to the surface's schema form). Channel-call
convention = §7 (promise args ARRAY-wrapped). The phone polls CLIENT-SIDE (one
read per Usage-screen load + explicit refresh — the desktop's 60 s shared cache is
a four-surface optimization the single-surface phone does not reproduce). §13.3
fallback: read failure / channel refusal ⇒ the quota section renders NOTHING
(hidden) — the desktop's own `ChatCodingPlanUsageRemainingPanel` returns `null`
without state (CodingPlanContextUsage.tsx:206-208); a `no_plan`/
`not_authenticated` payload face renders the honest unavailable notice.

### 13.1 `getEntitlementSnapshot` → `UsageEntitlementSnapshot` (GAP-3)

- Phone call: `usage-stats` channel, method `getEntitlementSnapshot`, args
  `Arr([{}])` (all `UsageEntitlementRequest` fields optional; the host resolves the
  current account/provider — the phone has no provider tabs to arbitrate; ADR-020).
- Result (usage-stats.ts:73-89, interface):
  `{generatedAt: number, serverTime?: number, authenticated: boolean,
  unavailableReason?: "not_authenticated"|"not_configured"|"no_plan"|"unavailable",
  startPlanExpired?: boolean, teamPlanUnavailableReason?: "expired"|"unassigned",
  context?: {scope: "personal"|"team", …} | null,
  provider: {id: string, name: string} | null,
  remaining: {count: number, isShow: boolean, percentage?: number,
  nextResetTime?: number|null} | null,
  subscription: {identityType, identityMasked, details[]} | null,
  quota: UsageQuotaSnapshot | null,
  mcpQuota?: {serverTime: number, level: string|null, scope, aggregate:
  UsageQuotaLimit} | null}`.
- `UsageQuotaSnapshot` (usage-quota.ts) = `{level: string|null, limits:
  UsageQuotaLimit[]}`; `UsageQuotaLimit` = `{type: string, bucketId?, userPlanId?,
  periodStart?, periodEnd?, period?, meter?, unitType?, planId?, unit?, number?,
  usage?, currentValue?, remaining?, percentage?, nextResetTime?, usageDetails:
  [{modelCode, displayName?, usage}]}` (usageDetails REQUIRED; everything else
  optional), `type` kept RAW (unknown future values never guessed).
- Meter projection (the desktop `codingPlanQuotaPresentation.ts` port — VERBATIM
  semantics): 5-hour = find(`TOKENS_LIMIT`, unit=3, number=5); weekly =
  find(`TOKENS_LIMIT`, unit=6); tool-calls = find(`TIME_LIMIT`, unit=5, number=1);
  MCP = `mcpQuota.aggregate` (a separate entitlement field, NOT in `limits[]`).
  `TOKENS_LIMIT` ≡ `CREDIT_LIMIT` are the SAME category (zai team backends emit
  `CREDIT_LIMIT`; bigmodel emits `TOKENS_LIMIT` — identical unit/number semantics,
  codingPlanQuotaPresentation.ts:16-33). A limit matches only on category + exact
  `unit` (+ exact `number` when the query carries one).
- Display semantics (same file:60-131): `percentage` is the USED share — remaining
  = clamp(100 − percentage, 0, 100), `null` when percentage is absent/non-finite;
  full ⇔ remaining = 100 (reset affordance hidden); value label = "--" when null,
  else the number with max 0 fraction digits (≥10) / 1 (<10) + "%"; reset-time
  labels: 5-hour = HH:mm (h23) always (`formatContextFiveHourResetTime`), weekly /
  tools / MCP = "MMM d" (`formatQuotaResetTime` format "date"; MCP resets at
  00:00 daily — the schema comment pins the date-only choice).
- Unavailable face: `!remaining && limits.length === 0 && !planLevel` ⇒ the
  desktop's unavailable notice carrying `unavailableReason` verbatim
  (CodingPlanContextUsage.tsx:254-262).

### 13.2 Reset words (GAP-4; ADR-020-sanctioned, typed-confirm gated)

Types in coding-plan-reset.ts (interfaces): `CodingPlanResetType =
"FIVE_HOUR"|"WEEK"`; `CodingPlanResetStatusSnapshot` = `{availableFiveHourResets:
[{expireAt}], availableWeekResets: [{expireAt}], latestFiveHourResetHistory:
{usedAt}|null, latestWeekResetHistory: {usedAt}|null, hasUnreadHistory: boolean}`;
`CodingPlanResetOpportunityRequest`/`CodingPlanResetUseRequest` extend
`CodingPlanResetScopeRequest {preferredProviderId, accountAccess}` with
`idempotencyKey` (+ `resetType` for use); results `{granted, nextTryAt?}` /
`{used: true}`.

- Scope descriptors: `preferredProviderId` = the entitlement's `provider.id`
  (builtin grammar `account:<family>-<planKind>`,
  model-provider-types.ts:7-16). `accountAccess` = the phone-side DERIVED
  descriptor `{type: "zhipu-account", accountType: "zai"|"bigmodel", mode:
  "start-plan"|"individual-coding-plan"|"team-coding-plan"|"off-peak", entitled:
  boolean}` (`zcodeProviderAccountAccessSchema`, zcode-protocol/index.ts:836-850 —
  credential-free: the host resolves the actual key from its own store;
  ADR-020 §why). The phone derives it ONLY from the builtin id grammar + the
  entitlement's authenticated state — never guessed for unknown ids (no reset
  surface for them).
- Call sequence (the desktop `useCodingPlanResetUi`/coordinator port):
  `getCodingPlanResetStatus` (read) → apply the status state machine →
  `requestCodingPlanResetOpportunity` (scope-level grant evaluation, throttled by
  the coordinator; "status 只查询已发放机会；必须再调 /opportunity，否则后端不会执行
  资格判断") → on manual confirm `useCodingPlanReset` → status reconciliation
  retries ([0, 250, 750, 1500] ms desktop schedule) until `usedAt` confirms →
  `markCodingPlanResetHistoryRead` after the completion display.
- State machine (lib/codingPlanQuotaResetUi.ts port — pure functions): entry
  status available|processing|completed; completion requires the server's
  `usedAt` (unread flag owned by the NEWEST of the two histories); processing is
  STICKY — a pre-consume status snapshot never flips processing back to
  available; failure restores the pre-click opportunity count/expiry and REUSES
  the idempotency key; a new valid opportunity (`expireAt > now`) supersedes an
  old sticky completion; optimistic quota-override (pending until the next
  entitlement refresh. Confetti/auto-play/the-2.6-s-auto-collapse garnish is
  NOT ported — the phone's completion note persists until the next status
  refresh (the section re-reads quotas immediately after a confirmed reset, so
  the desktop's quotaOverridePending optimism has no window to fill).
- Gating (roadmap accept): `useCodingPlanReset` is reachable ONLY behind the
  typed-confirm dialog — the word `RESET` must be typed before confirm enables;
  a UI test pins ZERO transport calls without it. The typed surface carries
  exactly ONE write word on the whole plane (`codingPlanResetUse`), pinned by
  `V4PlaneViewerSurfaceTest` (INV-9 stays intact for the viewer group).
