# Runbook — Direct-server deployment (Phase 10 precondition)

§43.2 Phase 10 preconditions: "Phase 8; server deploy docs." This is the
operator-facing deployment doc for Option B (§11/§7.10, all facts verified
against the reference clone `29628c9`, 2026-10-03).

## 1. Start a direct server (desktop side)

The distribution wrapper implements `zcode --web` (root README CLI
distribution table; §7.10):

```bash
zcode --web \
  --workspace /absolute/path/to/workspace \
  --port 3030 \
  --host 0.0.0.0 \
  --token "<long-random-secret>"
```

- `--token` maps to `ZCODE_SERVER_AUTH_TOKEN` (legacy `ZCODE_SERVER_TOKEN`).
  **Mandatory whenever `--host` is non-loopback** — the server refuses a
  non-loopback bind without a token (fail-closed, zcode-server-cli).
- Equivalent daemon form: `zcode-server-cli serve` (same HTTP/WS surface,
  `packages/zcode-server-cli`).
- Protected paths: `/api/*`, `/ws`, `/ws/*` → require `?token=` or the
  `zcode_lite_token` cookie (docs/protocol/phase10-direct.md §2).

## 2. Reachability tiers (failover candidate order, §11.3)

Order candidates from most-trusted/lowest-latency to least:

1. **LAN** — `ws://192.168.x.x:3030/ws` (same network; Android 16 Local
   Network Protection may block plain LAN traffic on future releases — the
   app surfaces `local-network-blocked` guidance; VPN interfaces exempt).
2. **Tailscale** — `ws://<magicdns-name>:3030/ws` (tailnet; encrypted mesh;
   unaffected by LNP).
3. **Tunnel** — `wss://<tunnel-host>/ws` (Cloudflare Tunnel/`cloudflared`;
   public edge; TLS at the edge).

The phone tries them in this stored order; it NEVER auto-switches to the
relay (different trust domain, §11.3).

**Cleartext reality (F-37-A, vc52).** Plain `ws://` / `http://` is decided by
Android's network-security policy, which OkHttp consults before connecting:
**release builds permit cleartext to no host**, and debug builds only to
`127.0.0.1` / `localhost` / `10.0.2.2` (the emulator rig). On a release build
the LAN and `ws://` Tailscale tiers above are therefore unreachable — use the
tunnel (`wss://`) or tailnet HTTPS (`tailscale serve` → `https://<host>.ts.net`).
The Add-direct-endpoint dialog checks the same policy and refuses a cleartext
URL (primary or fallback) **before any probe**, with "Plain ws:// isn't
allowed — use wss:// or a tunnel URL"; the probe itself runs off the UI
thread and keeps the Local-Network-Protection copy for real connect errnos
(EPERM/EACCES/ECONNABORTED) only.

## 3. Pair the phone (app side)

Settings → Endpoints → "Add direct endpoint":

- **Name** — display only.
- **Server URL** — `http(s)://host:port` (the app derives `ws(s)://host/ws`).
- **Token** — the `--token` value; sealed with Tink+Keystore like the relay
  credential (§12.4 "Server token" row), never logged, backup-excluded.
- The app validates via `GET /api/server-info?token=` before saving anything
  (401 → "token rejected"; nothing is persisted on a failed validation —
  §12.2's "write nothing until validation passes" rule applied to direct).

## 4. Security notes (§26)

- The token grants the full window surface for that server — treat like the
  relay QR (bearer-equivalent).
- TLS: `wss://` (tunnel) or tailnet HTTPS for anything leaving the device —
  plain `ws://` is refused by the platform on release builds (§2 "Cleartext
  reality"); the app offers optional TOFU pinning for self-hosted TLS (§11.5,
  fail-closed on fingerprint mismatch) on top of normal certificate validation.
- Desktop offline / server stopped → the endpoint goes Degraded with cached
  projections labeled stale (§15-S5 semantics; the recovery loop probes the
  saved candidates within its normal budget).

## 5. Manual smoke (developer; never CI — INV-15)

```bash
# Terminal 1 (synthetic workspace — never personal data, INV-16):
zcode --web --workspace /tmp/zcode-direct-smoke --port 18322 --host 127.0.0.1 \
  --token smoke-token-<random>
# Terminal 2: run the JVM direct smoke (tools/mockzcode test tag or the
# :spike DirectSmoke main) against ws://127.0.0.1:18322/ws — asserts
# server-info decode, cookie auth, binary frames, hello, listTasks.
```

Expected: preflight 200 + `zcode_lite_token` cookie, WS upgrade with cookie,
Initialize, `helloConversationV4` OK, `zcode-task.listTasks` returns `[]` (or
the smoke workspace's tasks). Record the transcript in the phase evidence —
never commit tokens or personal paths.

## 6. Rig: the DIRECT-mode mock (RIG-P4, vc52)

The on-device direct-endpoint legs run against MockZCode's DIRECT surface —
the same `zcode --web` contract as §1 (`/api/server-info?token=` → 200 + the
HttpOnly `zcode_lite_token` cookie; wrong/missing token → 401; `/ws` binary
SocketProtocol frames, cookie- or query-token gated). Synthetic data only
(INV-15/16); every other `MOCK_*` arm of the relay launcher applies.

```bash
# from the repo root — prints MOCKZCODE_DIRECT_URL[_EMULATOR] + MOCKZCODE_DIRECT_TOKEN
MOCK_DIRECT_TOKEN=rig-direct-token gradle --no-daemon :tools:mockzcode:mockDirectServer
# equivalent: MOCK_MODE=direct on the plain launcher (MockRelayMainKt) — default port 18200
curl "http://127.0.0.1:18200/api/server-info?token=rig-direct-token"   # 200 + server-info JSON
curl -o /dev/null -w '%{http_code}\n' "http://127.0.0.1:18200/api/server-info?token=wrong"  # 401
```

The three shapes F-37-A's live leg covers (Settings → Endpoints → Add direct endpoint):

1. **Loopback, debug build** — `ws://10.0.2.2:18200/ws` + the token: the debug
   network-security config permits cleartext to the emulator host, the probe
   runs off the UI thread, and the endpoint is added.
2. **LAN cleartext (release-shaped)** — e.g. `ws://192.0.2.1:3030/ws` (TEST-NET-1)
   or any `ws://192.168.x.x/ws`: refused at the dialog **before any probe** with
   "Plain ws:// isn't allowed — use wss:// or a tunnel URL" (§2 "Cleartext reality").
3. **TLS** — a publicly trusted certificate in front of the mock:
   `cloudflared tunnel --url http://127.0.0.1:18200` prints
   `https://<random>.trycloudflare.com`; add `wss://<random>.trycloudflare.com/ws`.
   (A self-signed front fails certificate validation by design — TOFU pinning is a
   second check on top of normal TLS validation, never a substitute for it.)
