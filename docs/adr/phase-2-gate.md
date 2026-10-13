# Phase 2 Gate — Transport & Protocol Core

| Field | Value |
|---|---|
| **Phase** | Phase 2 — Transport & Protocol Core (§43.2) |
| **Status** | **PASS WITH KNOWN LIMITATIONS** (all subphase exit gates green with executed evidence; limitations listed in §7, accepted in writing) |
| **Recorded** | 2026-09-28 15-36-32 BST |
| **Verifier** | model: `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); tools: Gradle 9.7.1, Kotlin JVM compiler 2.4.0 (toolchain 17), OkHttp/mockwebserver3 5.1.0, kotlinx-coroutines 1.10.2, Node v22 (fixture lint tools) |
| **Preconditions** | Phase 1 PASS ✓ (gate `docs/adr/phase-1-gate.md`: "Phase 2 is UNLOCKED"); corpus + fixtures committed ✓ (`fixtures/relay/` ×3, `fixtures/protocol/golden-vectors.json`) — verified against the working tree before implementation started |

---

## 1. Scope delivered (§43.2 Phase 2)

**In scope, delivered:** `core:transport` full (interface, §14.4.1 connection state machine, RelayTransport production-grade, reconnect/backoff/flow-control, capability & error mapping); `core:protocol` completion (v4 wire layer: hello/capabilities, TopicWireFrame + logical-frame assembler + codec, command envelope + CAS + uuid v7, conversation topics/frames/deltas/rows vocabulary, suspend ChannelSession); MockZCode core (§35.4, Kotlin JVM on MockWebServer, built on the shipped codecs); redaction logging sink (`core:common`); CI integration.

**Non-scope, respected:** credential storage UX (no vault code written; `CredentialSigner` exists as the §12.4 capability interface only), features/UI (none), DirectTransport (skeleton interface + documented stub only, per non-scope), no new Android modules/AGP (repo remains JVM-pure this phase).

## 2. Subphase exit gates — evidence

| Subphase | Exit gate | Evidence (all executed 2026-09-28) |
|---|---|---|
| 2.1 Transport interface + connection state machine | API frozen; machine matches §14.4.1 exactly | `Transport.kt` (interface + `EndpointDescriptor` + `CredentialSigner`/`TokenProvider` capability interfaces — the §11.2 contract); `ConnectionStateMachine.kt`. `ConnectionStateMachineTest` 11/11: every §14.4.1 table row, the four named-illegal transitions throw (`Pairing→Connected`, `Degraded→Recovering`, `Failed→Connected`, `Closed→any`), unlisted transitions anomaly-logged and rejected, `Any→Closed` absorbing. Interpretation note (§6.2): socket-loss-class rows for the acquisition states (`Authenticating/Connecting/Recovering → Reconnecting`) added as the §15-S1/S4 scenario extensions, documented in code + here |
| 2.2 RelayTransport production-grade | corpus replay 100% green | `RelayTransportTest` 7/7 (auth happy + wrong-proof + unknown-sid, watchdog, KICKED takeover, flow signal, INV-5 burst, full v4 plane: hello/capabilities → bootstrap → workspace-list → subscribe → snapshot frame → rowsRange(200-bounded) → command ACK); `CorpusReplayTest` 5/5 — the committed sanitized corpus replays green three ways: wire-faithful decode of every recv frame through the production inbound pipeline (7 rpc-frames CRC-decode, control payloads surface, strict send-shape lint = desktop `.strict()` parity), live-socket playback of `live-subscribe-16` through MockZCode into a CONNECTED production transport (dedupe absorbs counter collisions by design, zero protocol faults), and KICKED/AUTH_FAILED fixtures mapping to §33 classes |
| 2.3 Reconnect, backoff, flow control | storm suite green; no unbounded growth (INV-5) | `StormTest` 7/7: disconnect storm (auto-recovery to CONNECTED, `unacked` drains to 0), duplicate storm (S7 dedupe: exactly 1 snapshot despite double delivery), out-of-order fragments (S8: shuffled fragments assemble in index order), oversized envelope (#228 class → protocol fault → resync-class recovery), CRC-corrupted fragment (S11 → fault, never merges), latency storm (bounded, connected), full-jitter backoff unit test (base 1 s ×2 cap 60 s, bounds-checked, budget exhaustion → null → Degraded, immediate-trigger collapses to 0). Suite re-run ×3 — stable |
| 2.4 Capability & error mapping | mapping complete; every §33 category has ≥1 test | `ErrorMapping.kt` (close codes 4000/4004/4009/4010/4011/4012/4013; relay codes AUTH_FAILED/KICKED/INTERNAL/WRONG_PARAM; transport-local protocol/serialization/network/timeout/versionIncompat/degraded constructors); `ErrorMappingTest` 7/7 incl. the 18-category walk (every `ErrorCategory` covered exactly once, taxonomy fields asserted) + unknown-vs-unsupported-vs-incompatible non-conflation test + `===true` capability gate test |
| 2.5 MockZCode core | self-test green; CI integration | `tools/mockzcode` (Kotlin JVM, MockWebServer3 dispatcher — no queue, clean shutdown): auth scenarios (happy / wrong-proof / unknown-sid), single-terminal kick (Q10), control plane (bootstrap/workspace-list/bridge-open with recoveryId replay), v4 plane (initialize/hello/subscribe+snapshot push/rowsRange/command ack/flow), fault scripting (duplicates, shuffled fragments, oversized #228, CRC corruption, disconnect-after-N, latency, heartbeat suppression), corpus playback mode. `MockZCodeSelfTest` 5/5 — "the mock must decode its own output": Channel responses decode via `ChannelWire`, topic frames round-trip via the real `TopicWireFrameCodec`+assembler, rpc-frames reassemble via the real `RelayFrameCodec`, server HMAC byte-compared to the desktop construction. **CI**: `.github/workflows/ci.yml` committed (gradle test + fixture lint + redaction tests; INV-15-clean — no relay, no credentials). The workflow's exact commands were executed locally and are green; the first real GitHub run requires a push, which this session is forbidden to perform — recorded as limitation §7.5 |
| 2.6 Redaction logging sink | INV-3 test green | `core:common` `ZLog` — single sink, levels (DEBUG gated in release posture), structured correlation context, registered-secret + structural key-value scrubbers, bounded 500-entry diagnostics ring, export. `ZLogRedactionTest` 8/8: randomized property test (300 iterations — no registered secret substring ever survives in any output), overlapping/substring secrets, adversarial key-value shapes (pass_hash=, "proof":, Authorization: Bearer, nonce=), benign-identifier non-mangling (word boundaries), ring bound, level gate, context scrubbing |

## 3. Phase-level acceptance criteria (§43.2 Phase 2)

| Criterion | Verdict | Evidence |
|---|---|---|
| Fault matrix green | ✅ | `StormTest` 7/7 (+ `RelayTransportTest` fault paths) |
| Corpus replay green | ✅ | `CorpusReplayTest` 5/5 against the committed sanitized corpus |
| INV-5 upheld (no unbounded queue/cache) | ✅ | bounds structural + asserted: send queue 1024 (`Channel` capacity; saturation → error), parse buffer 1024 (`trySend` failure → drop+resync), inbound SharedFlow 256 (overflow → resync), logical assembler 32 concurrent/16 MiB/32 MiB staged/30 s (protocol tests), ring 500, oversized message refused before allocation (golden tests); `pendingAckCountForTest()` asserted 0 post-storm |
| INV-6 upheld (transport replaceable; features branch on capabilities) | ✅ | module graph: `core:transport` depends only on `core:common`+`core:protocol`; no features modules exist to violate INV-2/6; `CapabilitySnapshot` is the declared variability source; `DirectTransport` skeleton keeps the abstraction honest |
| MockZCode in CI | ✅ (workflow committed; first remote run pending push — §7.5) | `.github/workflows/ci.yml` |

## 4. Tests executed (full repo, clean `--rerun-tasks` run, 2026-09-28 15:34 BST)

Command: `gradle test --rerun-tasks --console=plain` → **BUILD SUCCESSFUL in 45 s**

| Module | Tests | Failures |
|---|---|---|
| core:common (2.6) | 10 | 0 |
| core:protocol (Phase 1 golden + Phase 2 v4 layer, incl. INV-13 rowsRange bound) | 27 | 0 |
| core:transport (2.1–2.4) | 37 | 0 |
| tools:mockzcode (2.5 self-test) | 5 | 0 |
| spike (Phase 1 regression — all previously-passing suites still green) | 9 | 0 |
| **Total** | **88** | **0** |

Stability: the core:transport suite was additionally re-run end-to-end ×3 (`--rerun-tasks`) with 0 failures after two test-infrastructure race fixes (collector-attach ordering on replay-0 SharedFlows; per-connection I/O-loop generations — the latter was a real transport bug found by the storm suite: a KICKED delivered on a half-closed old socket could hijack the new generation's recovery; fixed by tracking and cancelling per-connection loops).

## 5. Architectural corrections / decisions (§41.6 process)

1. **§7.4.4 CAS-set correction** `[CORRECTED 2026-09-28]` — the v1.0.1 example "`COMMANDS_REQUIRING_BASE_REVISION` (e.g. `sendText`)" contradicts the v3.14.3 schema (command.ts:296 @ `29628c9`): `sendText` is not in the set. The port implements the verified sets verbatim (tested in `V4CommandTest`); architectural.md §7.4.4 annotated in place. Evidence class: Tier-1 schema text; impact: none on MVP flows (sendText still carries a commandId per the verified envelope invariant; CAS enforcement remains for the 15 verified types).
2. **§14.4.1 machine — scenario extension rows** — `Authenticating/Connecting/Recovering + SOCKET_LOST → Reconnecting` and `Recovering + SOCKET_LOST → Reconnecting` are added as the §15-S1/S4-required acquisition-loss rows (the §14.4.1 table lists socket loss only from Connected). They contradict no illegal rule; without them the machine deadlocks in acquisition states. Documented in the machine's header and tested.
3. **Q6 decision (clientKind)** — `mobileRemote` declared by default (`EndpointDescriptor.CLIENT_KIND_DEFAULT`), per §46 Q6's fallback rule and Phase 1 evidence that `initializeConversationV4` accepts it live.
4. **`ProtocolException` made `open`** — allows the typed `V4WireVersionException` subtype for the §39.3 wire-version gate (catchable separately for the compat screen); all existing typed-failure semantics unchanged.
5. **Flow signal provenance** — `v4/connection/flow` is host→CLI on the desktop; whether the production relay pushes it to terminal-role clients is `[REQUIRES VALIDATION]` (§46-class note, added to docs/protocol/v4-wire.md §6). The consumer side is implemented and MockZCode-tested; an absent signal is a safe no-op.

## 6. Invariant verification (§42 walk, Phase 2 delta)

| INV | Verdict | Evidence |
|---|---|---|
| INV-1 | upheld | transport holds no runtime-owned state mutation; projections/acks only (Phase 5 owns reducers) |
| INV-2 / INV-6 | upheld | module graph: features (none yet) cannot see transport; transport imports only protocol+common; capability snapshot is the sole variability source |
| INV-3 | upheld | `ZLogRedactionTest` 8/8 property + adversarial; transport logs id lengths only (`nonce len=`), never material |
| INV-4 | upheld (mechanism) | `CommandEnvelope.build` CAS validation fails closed before the wire (tests); uuid v7 retry-stable ids (format + monotonicity tests); transport never auto-retries commands (resend is rpc-frame-ack-level, not command-level) |
| INV-5 | upheld | bounds structural + tested (§3 row 3) |
| INV-7 | upheld | no sqlite driver dependency anywhere (`core/transport/build.gradle.kts` deps: protocol, common, coroutines, okhttp) |
| INV-8 | upheld | unknown relay frame types / channel payloads / delta ops / row kinds counted, never crash (parser-fault isolation test path; malformed-channel counter asserted in corpus replay) |
| INV-10 | upheld | HMAC construction exactly as verified (server-side mock byte-compared to the desktop construction in the self-test; transport consumes proofs via the `CredentialSigner` capability only) |
| INV-13 | upheld | `rowsRange` limit hard-bounded 1..200 at the model layer (test); subscribe carries no unbounded snapshot semantics (tail-first per §28.3 remains Phase 5/6) |
| INV-14 | upheld | CAS commands rejected before the wire; CRC/geometry/unknown-enum faults typed and escalated to resync, never merged |
| INV-15 / INV-16 | upheld | no production relay, real credentials, QRs, or personal data in any test/fixture; MockZCode is synthetic-only; CI workflow codifies the ban |

## 7. Known limitations (accepted)

1. **Closed-relay failure paths remain partially knowable** — the storm suite covers the fault classes observed in Phase 1 (KICKED, AUTH_FAILED, gap codes, replayGraceExceeded class) plus §15-scripted classes on MockZCode; exotic real-network loss/reorder schedules remain canary/drift territory (§36, §39.4).
2. **Live-relay validation of Phase 2 code did not run this session** — no fresh QR was supplied; all evidence is against MockZCode + the committed Phase 1 corpus (which IS production-captured, sanitized). The Phase 1 live-plane proof (connect/auth/decode/recover ×10) plus corpus-replay-green bounds the risk; a manual canary against a disposable environment remains the §36 ritual before release.
3. **v4 fixture generation from TS zod schemas (§13.1) is manual this phase** — v4 fixtures are hand-derived from direct schema inspection (cited file+commit in `docs/protocol/v4-wire.md`) rather than generated by a TS script; the automated generation pipeline lands with the §39.4 drift tooling. Binary-layer golden vectors (72) remain TS-generated from Phase 1.
4. **`v4/connection/flow` provenance** — see §5.5; consumer implemented, production-relay delivery unverified.
5. **CI workflow committed but not remotely executed** — pushing is forbidden this session; the workflow's exact commands were executed locally and are green. First push will trigger the real run.
6. **Performance benchmark (§43.2 risk "performance of Kotlin codec")** — not measured this phase (no Macrobenchmark module yet; that lands with Phase 6/11 per §36). The codec's correctness is fixture-proven; budget measurement is explicitly a later-phase engineering target (§28.1), not a Phase 2 gate item.

## 8. Files changed (phase scope)

- `settings.gradle.kts`, `gradle/libs.versions.toml` (+3 modules, +5 library versions)
- `core/common/**` (new: ZLog sink, AppError model, tests)
- `core/protocol/**` (+ v4 wire/command/conversation, ChannelSession, `ProtocolException` open, tests)
- `core/transport/**` (new: Transport, ConnectionStateMachine, ReconnectPolicy, ErrorMapping, RelayTransport, Ws seam, DirectTransport skeleton, tests ×6 files)
- `tools/mockzcode/**` (new: MockZCode + self-test)
- `.github/workflows/ci.yml` (new)
- `docs/protocol/v4-wire.md` (new), `docs/adr/phase-2-gate.md` (this file)
- `architectural.md` (permitted bookkeeping only: §43.2 Phase 2 mark, status line, §7.4.4 `[CORRECTED 2026-09-28]` annotation)
