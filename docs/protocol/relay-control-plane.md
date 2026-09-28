# Relay Control Plane & Frame Transport — Phase 1 Extraction

Evidence class: `[VERIFIED]` = read directly from the named source on 2026-09-28.
Sources: shipping desktop bundle v3.14.3 (`/Applications/ZCode.app` — `app.asar` main
bundle + `glm/zcode.cjs`), reference clone `zcode/repo` @ `29628c9`. Mock-relay E2E
evidence: this repo's `tools/mockrelay` + `:spike` (JVM) + `android-smoke` (device).

## 1. Connection (terminal role) — `[VERIFIED]` (app.asar `WebRemoteControlDeviceTransport`)

```
WS connect  wss://zcode.z.ai/ws
            ?mid=<desktop deviceMid>        # desktop role appends this; terminal role: not observed yet
            header X-Device-ID: <mid>       # desktop role only (observed); permessage-deflate negotiated
→ auth_init      {type:"auth_init", role:"terminal", device_sid, meta:{platform?,version?,name?}, client_ts}
← auth_challenge {type:"auth_challenge", nonce}
→ auth_response  {type:"auth_response", device_sid, proof, client_ts}
← auth_ack | pair_status_ack {type, pair_status:"waiting"|"matched"}
heartbeat every 10s (jittered) in paired|waiting_terminal:
→ pair_status_query {type, device_sid, client_ts}
← pair_status_ack {type, pair_status}
watchdog: 30s without pair_status_ack → reconnect
reconnect delay: 1s (reconnectDelayMs; constants 3e4/3e4/3e3 also present)
```

Proof (INV-10 — reproduce exactly): `base64url(HMAC-SHA256(key = UTF8(passHashString),
msg = "${nonce}|${role}|${sid}"))`. **The HMAC key is the UTF-8 bytes of the base64
hash STRING, not the decoded digest bytes** (Node `createHmac("sha256", <string>)`
semantics — verified by fixture vectors + AndroidKeyStore device test).

Relay error frames: `{type:"error", code, message}` with codes observed in the bundle:
`KICKED` (reconnect-on-kick), `AUTH_FAILED` (one register-mode retry on desktop),
`INTERNAL`, `WRONG_PARAM`. Close codes per architectural.md §33 mapping.

## 2. Physical envelope — `[VERIFIED]` (zcode.cjs zod schemas)

Strict schema, both directions:
```json
{"type":"data", "payload":{...}, "client_ts":<ms>?, "server_ts":<ms>?}
```
**All app payloads (bootstrap/workspace/bridge control + rpc-frames) travel inside
`data` envelopes.** Top-level `{zcode_type:...}` messages are silently ignored by the
desktop dispatch (`handleMessage` routes only auth_*/device_register_*/data/error).

Payload union (zcode_type):
- `rpc-frame` (strict): bridgeSessionId (1..256, `[A-Za-z0-9._~-]+`), bridgeGeneration?
  (int ≥ 0), recoveryId?, **seq** (int > 0), **messageSeq** (int > 0), fragmentIndex
  [0,63], fragmentCount [1,64], messageBytes [1..16 MiB], checksum
  `{algorithm:"crc32", value:8-hex}` over the DECODED fragment bytes, dataBase64
  (canonical base64, ≤ 1 MiB chars).
- `rpc-frame-ack` (strict): bridgeSessionId, bridgeGeneration?, recoveryId?,
  **ackMessageSeq** (int > 0).
- Adapter limits (bundle `QI`): maxPhysicalFrameBytes 1 MiB (whole envelope; desktop
  drops oversize), maxMessageBytes 16 MiB, maxFragments 64, assemblyTimeoutMs 30 s,
  transportIdMaxChars 256.

Fragmentation semantics: logical messages fragment into equal-size chunks (short tail);
`messageSeq` identifies the logical message (what acks reference); `seq` counts physical
envelopes. Peer chunk size is NOT carried on the wire — the receiver infers
equal-size-with-short-tail (fragment 0's length when fragmentCount > 1). `[VERIFIED
schema]` / `[REQUIRES VALIDATION schedule]` — live ack/fragment cadence still to be
observed on production traffic (Q1 remainder).

Control-plane payloads (zcode_type, all strict on the desktop side):
- `bootstrap-request` {requestId} → `bootstrap-response` {requestId, success:true,
  result:{windowControlSessionId, desktopAppVersion?, workspaces[], tasks[],
  initialViewState?, mobileViewState?}}
- `workspace-list-request` {requestId} → `workspace-list-response` {requestId,
  success:true, result:{workspaces[], tasks?, activeWorkspaceKey?, activeTaskId?}}
- `workspace-list-updated` {result} (push)
- `workspace-bridge-open` {requestId, bridgeSessionId (client-generated),
  bridgeGeneration?, recoveryId?, workspaceKey, taskId?} → `workspace-bridge-ready`
  {requestId, bridgeSessionId, bridgeGeneration?, recoveryId?, bridge:{kind:"local"|
  "remote", workspaceKey, workspacePath, workspaceIdentity?, remoteSessionId?,
  initialTaskId?}} | `workspace-bridge-error` {requestId, …, reason, error}
- `workspace-reconnect-request` {requestId, workspaceKey} → `…-response`
- `mobile-view-state-update` {viewState:{activeWorkspaceKey?, activeTaskId?, updatedAt}, deviceInfo?}
- `bridge-degraded` {bridgeSessionId, …, reason: rpc-transport-fault|rpc-frame-gap|
  buffer-overflow|buffer-timeout, seq?, expectedSeq?, droppedCount?}
- `app-error` {requestId?, bridgeSessionId?, reason, error}
- `mobile-diagnostic` {event: state-transition|socket-close|socket-error|recover-start|
  recover-scheduled|pair-status|failure, timestamp, …}
- `platform-request`/`platform-response` (methods: isDockerAvailable, listWSLDistros,
  listDockerContainers, listSSHConfigAliases, loadMcpFromUserDirectory,
  saveMcpToUserDirectory, migrateLegacyCommonMcp)
- `telemetry-report` {event:{elementName:"session_create", …}} (desktop-side product
  telemetry — NOT sent by the phone client)

WorkspaceInfo: {workspacePath, workspaceIdentity?, remoteSessionId?, label,
workspacePurpose?: project|conversation, kind: local|remote, connectionState?,
lastConnectionError?}

Bridge failure reason enum: session-not-found | session-expired | session-conflict |
workspace-closed | desktop-disconnected | invalid-mobile-connection |
desktop-bootstrap-timeout | connection-recovery-timeout | relay-unavailable |
unsupported-action | unexpected-error.

## 3. Inside rpc-frame — binary Channel protocol — `[VERIFIED]` (reference clone, byte-identical port)

`dataBase64` decodes to one Channel frame: `serialize([respType, id]) ++ serialize(data)`
(server→client) or `serialize([reqType, id, channelName, name]) ++ serialize(arg)`
(client→server). Serialization: `[DataType tag][VQL length][payload]`; Object tag (5)
holds `JSON.stringify` bytes (booleans/null/non-integer numbers take this path).
Byte-identity proven by 72 golden vectors from the reference TS: `fixtures/protocol/
golden-vectors.json` (11/11 tests green).

v4 conversation plane rides the `zcode-agent` channel: `helloConversationV4`,
`initializeConversationV4`, `subscribeConversationV4` {workspacePath, workspaceIdentity?,
remoteSessionId?, base?:{logEpoch,seq}, visibility?}, `resyncConversationV4`,
`conversationRowsRangeV4`, `sendConversationCommandV4`; downstream frames via event
`onDynamicConversationFrame` (workspace-level).

## 4. Android platform findings (Phase 1.7 device smoke)

1. AndroidKeyStore **requires `KeyProtection` parameters when importing symmetric
   keys** — `setEntry(..., null)` throws `KeyStoreException: Protection parameters must
   be specified when importing a symmetric key`. Import with
   `KeyProtection.Builder(PURPOSE_SIGN|PURPOSE_VERIFY).setDigests(DIGEST_SHA256)`.
2. HMAC key import semantics confirmed on-device: import the UTF-8 bytes of the base64
   hash string; proofs then match the host byte-for-byte (§12.4 "only if import
   semantics verified in Phase 1" — verified).
3. `java.net.http.HttpClient` is unavailable on Android — OkHttp 4.x/5.x for WS (as
   architectural.md §10.1 already mandates).
4. Android's cleartext policy blocks plain `ws://` — irrelevant for the production
   `wss://` relay; the smoke APK carries a smoke-only cleartext exception for the local
   mock.

## 5. Evidence matrix for this document

| Claim | Source | Fixture/test |
|---|---|---|
| Handshake message shapes | app.asar 273.5M–273.6M region | mock E2E (JVM + device) |
| rpc-frame/ack schema + limits | zcode.cjs @796293–797656 | codec tests |
| Envelope-only dispatch | app.asar parseRelayMessage/handleMessage | mock E2E (top-level ignored) |
| Control-plane payload schemas | zcode.cjs kzi union @816241–818660 | docs + mock fixtures |
| Wire byte-identity | reference clone TS | 72 golden vectors |
| HMAC construction + string-key semantics | app.asar createNodeWebRemoteControlRelayAuthProvider | proof vectors + device test |
