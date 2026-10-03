# Direct Transport — Phase 10 Protocol Surface

Spec-first extraction (§41.2) for §43.2-10.1/10.2. Every wire fact below was
re-verified against the ZCode reference clone at commit `29628c9` (v3.14.3) on
2026-10-03, files cited inline. This is the contract `DirectTransport`
(`core/transport`) implements and MockZCode's direct mode reproduces.

## 1. `/api/server-info` — [VERIFIED 2026-10-03, Phase 10]

`packages/server/src/http.ts:320` → `createServerInfo` (http.ts:169–184),
`SERVER_REMOTE_PROTOCOL_VERSION = 1` (`packages/shared/src/server-remote.ts:3`).

```json
{
  "serverId": "<hostname | ZCODE_SERVER_ID | 'zcode-server'>",
  "name": "<optional display name>",
  "version": "<ZCODE_VERSION — desktop/server version, compat-gate input §11.3>",
  "protocolVersion": 1,
  "authRequired": false,
  "workspaces": [ { "path": "<absolute desktop path — display-only, INV-16>", "label": "<basename>" } ],
  "capabilities": { "desktopContinuous": true, "websocketRpc": true, "processResourceTelemetry": true }
}
```

Decoder rules (INV-8): unknown fields tolerated + raw preserved; `workspaces[]`
entries without `path` are dropped (a workspace without a path cannot produce a
§7.11 identity key); `capabilities` flags default false (HostCapabilities
consumer rule, §7.4.1). `version` feeds the same `CompatibilityGate` matrix as
the relay QR `app_version` (§39.2).

## 2. Token auth (direct) — MUST NOT be conflated with relay HMAC — [VERIFIED 2026-10-03]

`http.ts:187` `zcodeLiteTokenCookieName = "zcode_lite_token"`; `http.ts:218–237`
`hasValidLiteToken`; `http.ts:310–318` middleware.

- Protected paths: exactly `/ws`, `/ws/*`, `/api/*` (http.ts:239).
- `?token=<t>` on ANY request where `t` equals the configured
  `ZCODE_SERVER_AUTH_TOKEN` → request passes AND the server answers with
  `Set-Cookie: zcode_lite_token=<t>; Path=/; HttpOnly; SameSite=Lax`.
- Subsequent requests may present the cookie instead of the query param.
- No cookie, no matching token → **HTTP 401** `{"error":"Unauthorized"}` (when a
  token is configured). No token configured → `authRequired:false`, everything
  open (loopback-fail-closed default is enforced server-side, §7.10).
- **No Bearer support. No HMAC challenge/proof. The relay auth model
  (`auth_init`/`auth_challenge`/`auth_response`, §7.6) does not exist on the
  direct surface** — §11.4's normative separation. DirectTransport consumes a
  `TokenProvider` capability (secret custody stays in `core:security`, §12.4
  "Server token" row: Tink-sealed, backup-excluded).

Client flow (DirectTransport): preflight `GET {origin}/api/server-info?token=<t>`
→ 200 validates the token + yields server-info + the cookie value; the WS
upgrade carries `Cookie: zcode_lite_token=<t>`. 401/403 on preflight = AUTH-class
failure (re-entry: user re-enters the token — §33 `auth` with direct-specific
copy, no QR re-scan). The token value is never logged (INV-3; URLs in logs are
stripped of the query).

## 3. `/ws` wire — binary SocketProtocol frames — [VERIFIED 2026-10-03]

`http.ts:326–333`: `GET /ws` → `setupChannelServer(ws, services,
"web-remote-replayable")` → `SocketProtocol(new WebSocketNodeSocket(ws))` +
`ChannelServer` (http.ts:86–95). The browser client wraps its WS in the SAME
`SocketProtocol` (`packages/client/src/websocket.ts:97`).

`packages/rpc/src/protocol.ts:236–303` `SocketProtocol`:

- `send(buffer)` writes exactly `ProtocolMessage(type=Regular(1), id=0, ack=0,
  data=buffer)` — **id/ack are constant zero**; there is NO ack bookkeeping, NO
  retransmit, NO KeepAlive at this layer (those live in `PersistentProtocol`,
  which the direct surface does not use for this client class).
- `readMessages()` fires ONLY `Regular` payloads; other frame types are consumed
  and ignored. Header = 13 bytes `[type u8][id u32 BE][ack u32 BE][len u32 BE]`
  (`HEADER_SIZE=13`, protocol.ts:206), already ported as `core:protocol`
  `SocketFrame` (Phase 2 §1.2).
- Payload = one serialized Channel frame (`ChannelWire` shapes, docs/protocol/
  v4-wire.md §7). Fragmentation/粘包 across WS messages is handled by
  ChunkStream (WS preserves message boundaries; the stream reassembly is used
  defensively).

`ChannelServer` sends `{Initialize}` (ResponseType 200) immediately on
construction (channelServer.ts:29–36, `deferInit=false` default) — the client's
`ChannelSession.awaitInitialize` gates on it exactly as in relay mode.

Consequence: on the direct surface each binary WS message = one SocketProtocol
frame; the inner Channel/v4 plane is BYTE-IDENTICAL to the relay bridge plane —
same `zcode-agent` channel, same workspace-target params, same
`onDynamicConversationFrame` scoped listen, same hello/clientHello, same
`PROTOCOL_V4_LIMITS` (§7.4.5), same command envelope (§7.4.4). §8.3.1: "Both
share ~90% of the stack (ADR-011)."

## 4. Connection scope — [VERIFIED 2026-10-03]

`http.ts:97–103`: the direct `/ws` connection scope is
`clientMode="web-remote-replayable"`, `role="terminal-client"`,
`connectionId="server-ws-<uuid>"` — exactly the phone profile of §5.3/§7.3.
Channel gating (e.g. provisioning refused for non-trusted hosts, http.ts:107–119)
matches §13.3's per-channel classification; refusals surface as channel errors
(`fault.*`), which feature layers translate into their §13.3 disabled states.

## 5. Close codes / loss classes — [VERIFIED §7.10, §33]

- `/ws/remote/:id` uses 4000 ("missing id") / 4004 ("not found") — the general
  `/ws` used by Phase 10 rejects at the HTTP layer (401) instead of WS close
  codes. `ErrorMapping.closeCode` already maps 4000/4004 → TRANSPORT (§33).
- WS-level liveness: OkHttp `pingInterval(20s)` (§11.5 keepalive); pong failure
  fails the socket → normal reconnect path. There is NO relay-style 10 s
  heartbeat / 30 s watchdog on the direct surface.

## 6. Task list plane on direct — `zcode-task.listTasks` — [VERIFIED 2026-10-03]

The relay bootstrap control plane (`bootstrap-request`/`bootstrap-response`,
docs/protocol/relay-control-plane.md) is RELAY-ONLY — the direct surface has no
such envelope. The task list on a direct endpoint uses the exposed service
channel (§7.8, `packages/services/src/session/zcodeTaskService.ts:379–384`):

```
call channel "zcode-task", method "listTasks",
  arg = [ { workspacePath: "<path>", workspaceIdentity?: "<identity>" } ]
→ ZCodeTaskMeta[] (packages/shared/src/zcode-task-types-core.ts:264+):
  { taskId, traceId, title, titleOverridden?, workspacePath, workspaceIdentity?,
    workspacePurpose?, createdAt, updatedAt, mode, model?, thoughtLevel?,
    runtimeEpoch?, provider?, ... status? }
```

- Workspaces come from `server-info.workspaces` (§1 above) — the direct
  endpoint's §11.6 "workspace list" source.
- Identity rule (INV-12): `workspaceIdentityKey = workspaceIdentity?.trim() ?:
  workspacePath` (§7.11 verbatim). Paths are display-only metadata (INV-16).
- Decoder is tolerant (INV-8): unknown fields dropped-with-raw, missing
  `taskId`/`title` entries skipped, status via `TaskStatus.fromWire`
  (UNKNOWN-preserving). `archivedAt`/`pinned` surfaces are NOT claimed (they
  live in `listArchivedTasks`/`listPinnedTasks` — out of Phase 10 scope).
- Live updates: Phase 10 does NOT port a sessions-index push for direct (the
  verified `workspace-list-updated` control push is relay-only). The task list
  refreshes on connect/reconnect/pull-to-refresh — documented limitation, not a
  silent gap.

## 7. Failover candidates + trust domains — normative (§11.3)

`EndpointDescriptor.candidates` are tried in stored order (LAN → tailnet →
tunnel) and MUST all be same-kind (DIRECT) — the registry never auto-switches
across trust domains (never DIRECT → RELAY without explicit user consent;
§11.3, §9.11 EPFAIL). Auth-class failures (401) do NOT continue the candidate
loop (a rejecting server authoritatively judged the token). Transport/network
failures (incl. the Android-16 Local-Network-Protection class, §25.3.7:
`EPERM`/`ECONNABORTED` surfaced as `local-network-blocked`) DO advance to the
next candidate.

## 8. TOFU pin option — [PROPOSED §11.5, implemented Phase 10]

Per-endpoint opt-in: on first connect over TLS the presented certificate
chain's leaf SHA-256 fingerprint may be recorded ("trust on first use");
subsequent connects verify the fingerprint and MISMATCH FAILS CLOSED (no
proceed-despite-risk path; §26.2-T11). Pin state lives with the endpoint
record; the verification is TLS policy in `core:transport` (no custom
cryptography — INV-10 untouched; standard `MessageDigest` SHA-256 over the
platform-presented X.509 chain).

## 9. Fixtures & tests (§35 mapping)

- MockZCode direct mode implements §1–§6 verbatim (server-info shape, 401 gate,
  binary SocketProtocol frames, Initialize, zcode-task.listTasks) — CI runs
  against it exclusively (INV-15; no real server in CI).
- Direct integration tests (core:transport): connect E2E (preflight → cookie →
  binary frames → Initialize → hello → subscribe → rowsRange), wrong token →
  AUTH, missing token on protected server → AUTH, rejectConnections →
  transport, candidate failover ordering + trust-domain guard, LNP class
  mapping, TOFU record/mismatch/fail-closed, oversized logical frame guard.
- Real `zcode --web` integration is a LOCAL MANUAL smoke (§35.2: real-desktop
  runs never CI) against a disposable synthetic workspace — runbook:
  docs/runbooks/direct-server.md.
