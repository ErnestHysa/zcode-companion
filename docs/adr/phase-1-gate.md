# Phase 1 Gate — Protocol Spike

| Field | Value |
|---|---|
| **Phase** | Phase 1 — Protocol Spike (headless JVM + one Android smoke), §43.2 |
| **Status** | **PASS WITH KNOWN LIMITATIONS** (all acceptance criteria met with live evidence; limitations listed in §6, accepted in writing) |
| **Recorded** | 2026-09-28 14-06-34 BST (gate decision; evidence timestamps below) |
| **Verifier** | model: `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); tools: Gradle 9.7.1, Kotlin JVM compiler 2.4.0 (toolchain target 2.4.20), Node v22.22.2, Android SDK build-tools 35.0.0 / platform android-30 / emulator 37.1.11 (AOSP ATD arm64), manual kotlinc+d8+aapt2+apksigner toolchain for the smoke APK |
| **Phase history** | BLOCKED recorded 2026-09-28 (commit `cac63c8`) pending user QR; user supplied fresh pairing link; live probes executed same day; gate re-evaluated to PASS WITH KNOWN LIMITATIONS |

---

## 1. Acceptance criteria — Phase-Level (§43.2 Phase 1)

*"All spike questions YES; sanitized corpus committed; Q1/Q2/Q3/Q4/Q9/Q10 answered with evidence; INV-15/16 upheld."*

### The six spike questions — all YES, live

| Question | Evidence (2026-09-28) |
|---|---|
| **Connect** | WSS to `wss://zcode.z.ai/ws?mid=<deviceMid>` — connected repeatedly; permessage-deflate negotiated by relay side. **The `?mid=` param is REQUIRED for correct classification**: without it the relay mis-classifies the terminal connection and kicks the desktop's device slot, leaving pairing stuck at `waiting` (live finding, live-auth-1 vs live-auth-2 captures). |
| **Auth** | Terminal-role HMAC handshake → `auth_ack pair_status=matched`, reproduced across ≥8 separate live connections. Wrong proof → `error{code:"AUTH_FAILED"}` (live-negproof-1). |
| **Receive** | Control plane (bootstrap/workspace-list/bridge) and rpc-frame transport both received and decoded live. |
| **Decode** | rpc-frame → CRC32 → Channel frames → `helloConversationV4` OK (protocolVersion=3, deliveryProfile=replayable, full HostCapabilities), `initializeConversationV4` OK, `subscribeConversationV4` OK, **2 real v4 conversation frames received and decoded**, `conversationRowsRangeV4` OK with **200 real rows** (kinds: toolCall=86, reasoning=59, assistantText=53, turnHeader=1, userInput=1). |
| **Safe request** | Read-only requests only (bootstrap, workspace-list, bridge-open, subscribe, rowsRange). No command sends (per §43.2-1.4 design constraint). |
| **Event + recovery** | Live recovery drill: kill socket → reconnect → re-auth → re-open bridge with stable `recoveryId` → frames replay gap-free — **10/10 cycles correct** (live-recover-4). |

### Sanitized corpus committed ✓
`fixtures/relay/live-subscribe-16.fixture.json` (33 messages — complete session),
`live-dbl-1.fixture.json` (KICKED takeover), `live-negproof-1.fixture.json`
(wrong-proof rejection). All pass `validate-fixture.mjs` including decode-and-scan of
every rpc-frame payload (content destroyed, transport geometry + CRC preserved) and the
redaction property tests (6/6). Fixture metadata per §41.3.

### §46 open questions — answered with evidence

| # | Answer | Evidence |
|---|---|---|
| **Q1** (fragment/ack names + schedules) | **ANSWERED.** Names/schema: `rpc-frame {seq, messageSeq, fragmentIndex, fragmentCount, messageBytes, checksum{crc32}, dataBase64}` + `rpc-frame-ack {ackMessageSeq}` (bundle `.strict()` schemas, live-confirmed). Schedules: acks are **mandatory** — receiver acks each delivered logical message with its messageSeq; unacked frames are resent by the peer and after grace the bridge degrades (`remote.rpcFrame.replayGraceExceeded`). Counters are **per-direction, each starting at exactly 1** — a first frame at msgSeq=2 → `remote.rpcFrame.messageGap`; a first frame at seq=2 → `remote.rpcFrame.physicalGap`. Adapter limits: 64 fragments × ≤1 MiB physical, 16 MiB logical, 30 s assembly. Fragment chunk size is NOT on the wire — receiver infers equal-size-with-short-tail. | desktop host logs (reason codes), live captures live-subscribe-4/5/8/16 |
| **Q2** (QR `t` expiry) | **ANSWERED (client-side scope).** The terminal-role handshake never transmits `t` (auth_init carries role/device_sid/meta/client_ts only — verified in every capture); the relay therefore cannot enforce QR-`t` at auth time, and a hours-old QR was accepted repeatedly. Server-side pairing-record expiry remains unobservable from the client (documented as inherent closed-system residual, §6). | all live captures |
| **Q3** (nonce policy / replay resistance) | **ANSWERED (observable scope).** Nonce issued per `auth_challenge` (fresh 24-hex-char value per attempt, live captures); wrong proof rejected AUTH_FAILED; repeated failed attempt did not lock out the next correct attempt. Proof replay was never attempted by design (never cache proofs — §7.6); server nonce-uniqueness policy remains server-internal. | live-negproof-1 + handshake captures |
| **Q4** (does relay validate `mid`/`name`?) | **ANSWERED — partial but decisive:** `mid` **is operationally required** in the connection URL (routing/classification — its absence breaks pairing, see Connect above); `name`/meta values are not validated (arbitrary strings accepted: "zcode-android-phase1" worked like "mobile-browser"). | live-auth-1 vs live-auth-2 |
| **Q9** (bootstrap payload schemas) | **ANSWERED.** Live bootstrap-response: `{windowControlSessionId, desktopAppVersion:"3.14.3", workspaces[7], tasks[476], initialViewState}`; workspace-list-response `{workspaces, tasks, activeWorkspaceKey, activeTaskId}`; workspace-bridge-ready `{bridge:{kind:"local", workspaceKey, workspacePath, initialTaskId}}` — exactly the bundle-extracted schemas (docs/protocol/relay-control-plane.md). | live-subscribe-16 fixture |
| **Q10** (second-terminal semantics) | **ANSWERED.** Second terminal authenticates and is `matched`; the displaced terminal receives `error{code:"KICKED"}` (empty message) then closes (1006). Takeover asymmetry = §15.3 model ("Reconnect takes over back"). | live-dbl-1 fixture |
| Q5 (sid/hash vs window-token) | sid/hash QR path works and is wired; abstraction covers both (monitor per §39.4). | — |
| Q6 (clientKind) | `initializeConversationV4` accepted `clientKind:"web"` (SPA parity); schema allows mobileRemote/mobileApp — declared "web" during spike per SPA parity, deferred to Phase 2 ADR. | SPA bundle + live handshake |
| Q7 (terminal-role channel authorization) | **Partial:** `zcode-agent` v4 plane fully usable on replayable connections (proven). Non-core channels (`terminal`, `file`) NOT probed — Phase 6/9 enumeration per plan. | live-subscribe-16 |

### INV-15/16 upheld ✓
No real credentials, QR links, conversation content, personal paths, or provider
secrets committed. Raw captures live only in `$TMPDIR` (mode 600 for the QR file).
Fixture lint scans envelope AND decoded rpc-frame content. The user-pasted QR link was
handled as a live secret (recommended rotation recorded below).

## 2. Tests executed (all actually run 2026-09-28)

| Suite | Result |
|---|---|
| `gradle :core:protocol:test` (golden vectors, fuzz, limits) | 11/11 PASS |
| `gradle :spike:test` (proof vectors, QR matrix, mock-relay integration) | 9/9 PASS |
| `node --test tools/capture/redact.test.mjs` | 6/6 PASS |
| Mock-relay E2E (discover / recover ×10 / negative-proof) | PASS / 10-of-10 / AUTH_FAILED |
| Live: auth E2E ×≥8 | matched every time |
| Live: bootstrap + workspace-list + bridge (Q9) | PASS |
| Live: v4 handshake + subscribe + 2 real frames + rowsRange 200 | PASS |
| Live: recovery drill ×10 | **10/10 correct** |
| Live: negative proof | AUTH_FAILED (expected) |
| Live: double terminal | KICKED on first (expected per §15.3) |
| Device smoke (ATD emulator, API 30): Keystore HMAC vector + OkHttp full cycle | **ALL PASS ×3** |
| `validate-fixture.mjs` on 3 committed fixtures | all OK |

## 3. Live findings that changed the spike client (all fixture/tests documented)

1. Relay URL requires `?mid=<deviceMid from QR>` for terminal connections.
2. All app payloads (control + rpc-frames) travel inside `{type:"data",payload:{…}}`
   envelopes; bare `{zcode_type:…}` is silently ignored by the desktop dispatch.
3. `java.net.http` delivers large WS messages as fragmented text frames — accumulate
   until `last=true` (OkHttp aggregates; JVM spike needed the fix).
4. rpc-frame counters are per-direction starting at 1; acks mandatory
   (`rpc-frame-ack ackMessageSeq`); missing acks → peer resends → replayGraceExceeded.
5. **ProxyChannel argument convention: Promise-call args are wrapped in an ARRAY**
   (`toService`: `h=[...d]`; server: `m.apply(target, h)`) — a bare object arg arrives as
   `undefined` (`fault.connection.handshakeRequired` cascade). Dynamic-event listen args
   stay raw. (Recoverable from open-source chunk `chunk-UHHNTW2R.js` — now documented.)
6. Device-side payload handling drops everything while its own socket is un-paired;
   after a terminal connect the device re-pairs on its next ~10 s heartbeat → clients
   must retry bootstrap-class requests with the same requestId (SPA does the same).
7. Android platform: AndroidKeyStore requires `KeyProtection` params for symmetric-key
   import; HMAC key = UTF-8 bytes of the base64 hash STRING (§12.4 import semantics
   now verified on-device).

## 4. ADR confirmation (§43.2-1.8)

- **ADR-002 (relay transport default)**: CONFIRMED — production relay accepts
  independent terminal-role clients; full conversation plane operational.
- **ADR-003 (hand-ported Kotlin subset)**: CONFIRMED — byte-identity proven (72 golden
  vectors) and the port operated the live plane end-to-end.
- **ADR-004 (vault/signer split)**: CONFIRMED — CredentialSigner answered live
  challenges; vectors byte-identical; Keystore import semantics verified on-device.
- **ADR-006 (snapshot+afterSeq replay, stale-guard)**: CONFIRMED for the mechanisms
  proven (recoveryId replay, per-direction counters, ack schedule); full §15 matrix is
  Phase 2/5 work as planned.
- New protocol findings codified in `docs/protocol/relay-control-plane.md` — no
  architectural contradictions; architectural.md §43.2 phase marking updated per
  permitted bookkeeping only.

## 5. Exit gate decision

**PASS WITH KNOWN LIMITATIONS** — every acceptance criterion is green with live
evidence; the limitations below are documented, bounded, and accepted in writing as
phase-2+ work per the plan. Phase 2 is UNLOCKED (its preconditions "Phase 1 PASS;
corpus + fixtures committed" are satisfied). The binding no-UI rule (§43.0) is hereby
SATISFIED for its Phase-1 condition — Phase 6 UI work may proceed only after Phase 5
per the dependency graph.

## 6. Known limitations (accepted)

1. Failure paths of the closed relay are only partially knowable: observed
   `KICKED`/`AUTH_FAILED`/`messageGap`/`physicalGap`/`replayGraceExceeded`/`INTERNAL`-class
   handling, but exotic loss/reorder schedules remain Phase 2 MockZCode territory.
2. Server-internal policies (nonce uniqueness, pairing-record lifetime, rate limits)
   remain unobservable; handled conservatively by design (never cache proofs, bounded
   retries).
3. Device smoke ran on an API-30 ATD emulator (no physical device attached); the
   §35.6 physical-device matrix is a pre-release gate, not a Phase 1 item.
4. The committed corpus contains one complete session + two small negative-path
   captures; more scenario coverage lands with Phase 2 captures.
5. `clientKind` for the production app (web vs mobileRemote vs mobileApp) is a Phase 2
   decision; the spike used SPA-parity "web".
6. Security hygiene: the pairing link used for probes traversed a chat channel —
   **rotation recommended** (desktop: refresh QR) — recorded as an action item for the
   user; app-side impact is none (credential rotation flow is Phase 3).

## 7. Invariant verification (§42 walk, final delta)

INV-2/6 clean module boundaries · INV-3 secrets never logged (nonce lengths only;
captures in $TMPDIR) · INV-5 bounds asserted in tests · INV-7 no sqlite dependency ·
INV-8 unknown shapes typed/counted (fuzz-tested) · INV-10 exact crypto (vectors +
device) · INV-14 fail-closed (typed faults; no lenient interpretation) · INV-15/16
sanitization pipeline + decode-and-scan lint (all committed fixtures pass).
