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
