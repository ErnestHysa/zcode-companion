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
- TLS: `wss://` (tunnel) or tailnet HTTPS recommended for anything leaving the
  LAN; plain `ws://` LAN is plaintext-on-trusted-LAN — the app warns when the
  URL is not `wss`/`https` and offers optional TOFU pinning for self-hosted
  TLS (§11.5, fail-closed on fingerprint mismatch).
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
