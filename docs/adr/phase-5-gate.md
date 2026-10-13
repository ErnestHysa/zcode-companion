# Phase 5 Gate — State & Recovery Architecture

| Field | Value |
|---|---|
| **Phase** | Phase 5 — State & Recovery Architecture (§43.2) |
| **Status** | **PASS WITH KNOWN LIMITATIONS** (all five subphase exit gates green with executed evidence; limitations listed in §8, accepted in writing) |
| **Recorded** | 2026-09-28 20-30-53 BST |
| **Verifier** | model: `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); tools: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20 (JVM/Android, toolchain 17), Room 2.8.5, Robolectric 4.17 (SDK 30 sandbox), kotlinx-coroutines(-test) 1.10.2, MockWebServer (OkHttp 5.x test tooling), emulator 37.1.11 (AOSP ATD arm64 API 30, `zcode-atd`) |
| **Preconditions** | Phase 4 stores ✓ (`docs/adr/phase-4-gate.md`); Phase 2 transport ✓ (`docs/adr/phase-2-gate.md`) — the frozen `Transport` API consumed, not modified (one additive read-only accessor + one additive v4 call, see §4) |
| **Freeze** | **This gate is the second architecture freeze condition (§51.4)** — state ownership and recovery semantics are proven by the §15 matrix |

---

## 1. Scope delivered (§43.2 Phase 5)

**In scope, delivered:** the correctness core — per-topic single-writer conversation projection reducers with the `(logEpoch, seq)` stale-guard (duplicate/stale drop, bounded hold buffer, gap escalation); §14.4.2 task state machine and §14.4.3 permission/interaction state machine implemented EXACTLY (illegal transitions refuse; authoritative snapshots replace); §15.5 snapshot replacement semantics (atomic replace + cursor reset + derived-state discard + Android-owned local-only retention); the §15.1/§15.4 recovery subsystem (journal-driven subscribe/resync with resume-or-forced-snapshot escalation under a per-endpoint mutex, stale overlays, S12/S13 reconciliation passes); the §20 submission path wired through the outbox with INV-14 fail-closed answer classification and INV-4 single-commandId retries; the deep-link routing decision core (§24.7/S14) and notification reconciliation decisions (S12/§24.3); the §15 scenario matrix S1–S17 executed 17/17 against MockZCode through the REAL RelayTransport + Room stores; property/model-based testing audit with the invariant coverage table (`docs/invariant-coverage.md`).

**Non-scope, respected:** no screens/UI (Phase 6); no composer (Phase 7 owns the send UX — S16 exercises the outbox+gateway contract); no notification delivery (the broker is Phase 8; P5 delivers the reconciliation DECISIONS through a port); no direct-endpoint transports (Phase 10); no MockZCode expansion beyond the scenarios this phase needs (§35.4 incremental rule).

## 2. Subphase exit gates — evidence (all executed 2026-09-28)

| Subphase | Exit gate | Evidence |
|---|---|---|
| 5.1 Reducers + stale-guard | property suite green; zero ambiguous merges | `:domain` **78/78**: `ConversationTopicReducerTest` 13/13 (snapshot atomic replace with effect ordering, cursor reset, derived-state discard, duplicate/stale drop S7, hold-then-fill S8, hold-window expiry → resync S10, hold-buffer overflow bound INV-5, overlap-resend escalation, fresh-adoption guard, page epoch adoption without false gaps, 8 KiB/row + removal-trim derived bounds, unported patch/unknown ops counted never merged); `ConversationTopicReducerPropertyTest` — model-based: **60 seeded randomized schedules × 80 ops** against an INDEPENDENT §15.5/S7–S10 reference model with per-step store-rows/epoch/cursor equality + INV-5 bounds (zero ambiguous merges); `TaskStateMachineTest` 7/7 (§14.4.2 table verbatim incl. the two named-illegal rows, metadata-only terminal updates, stale overlay) |
| 5.2 Snapshot replacement semantics | S9 green; snapshots never overwrite drafts/UI state | Reducer-level: `ConversationTopicReducerTest` (replace effect order StreamingCleared→Persist→Cursor; derived discard; effect-vocabulary proof that a snapshot can only emit conversation-store/cursor/derived effects — no draft/UI/notification mutation path exists); end-to-end: matrix S9 (epoch-flip replace with the draft byte-identical after), S5 (draft retained through Degraded), S17 (task-scoped draft survives authoritative removal), S12 (snapshot replaces while the reconciler dismisses consumed notifications only) |
| 5.3 Recovery subsystem | S1–S17 matrix green (17/17) on MockZCode | `RecoveryScenarioMatrixTest` 17/17 against MockZCode via the REAL RelayTransport + Room + journal (§35.2 CI shape): S1 disconnect/recover gap-free with honest stale labels; S2 true process death (file-db survives, journal hydrate, projection restored without fabrication); S3 foreground wake immediate reconnect; S4 network transition + duplicated tail deduped; S5 desktop-offline → budget → Degraded with cached projection + manual retry recovery (fresh budget); S6 relay-unreachable → honest TRANSPORT-class failure + cached readable; S7 duplicates; S8 out-of-order hold/fill with journal watermark 5; S9 epoch replace + draft retention; S10 unrepairable gap → hold expiry → resync-resume → repeat → FORCED snapshot (never a local fill); S11 CRC fragment fault → protocol error → recovery; S12 authoritative terminal state applied + notification reconciled; S13 recovered pending-approval card (fail-closed hostile option refused); S14 deep-link routing over data truth; S15 double-tap no-op + resolved-elsewhere wins over an in-flight submit; S16 sendText accepted during replay + stale-CAS → FAILED_STALE + draft preserved; S17 removal reflects + draft preserved |
| 5.4 Permission state machine integration | race suite green; zero double-apply across 10k random schedules | `PermissionStateMachineTest` 9/9 (§14.4.3 table incl. RESOLVED→SUBMITTING and STALE→SUBMITTING hard refusals, double-tap no-op, ack-class routing, TTL row, INV-14 answer policy: unknown/near-miss ids inert, empty option set NOT approvable, refine requires text); `PermissionRacePropertyTest` — **10,000 randomized schedules**: resolution ≤ 1 and only via authoritative confirmation, commandId stability, zero hostile option ids on the wire, no runaway retransmits; coordinator cells in matrix S13/S15 (same-commandId retry after UNKNOWN, live-entry never duplicated) |
| 5.5 Property/model-based testing audit | invariant coverage table in `docs/` | `docs/invariant-coverage.md` — INV-1…INV-16 each mapped to ≥1 automated check or explicit manual gate; manual gates recorded (INV-11 UI suite = Phase 6; live-relay resync shape = Phase 12 canary; permission event-plane binding = Phase 7) |

## 3. Phase-level acceptance criteria (§43.2)

| Criterion | Verdict | Evidence |
|---|---|---|
| All §15 scenarios + §20.3 matrix + property suites green | ✅ | S1–S17: 17/17 (matrix); §20.3 cells: machine table + 10k-schedule property + coordinator S15/S16/S13; property suites: reducer model-based + race property — **JVM full repo: 272 tests / 0 failures** (see §6) |
| Invariant coverage complete | ✅ | `docs/invariant-coverage.md` — every INV row mapped; manual gates listed with their owning phase |

## 4. Architectural corrections / additions (§41.6 process)

No architectural.md section was contradicted by implementation evidence; no silent reinterpretation. Recorded additions/clarifications:

1. **Additive transport surface (P5.3 requirement):** `RelayTransport.resyncSession(...)` (the §15.1 escalation path; workspace-target param form mirroring the live-proven subscribe shape — the trusted host injects topic/connectionId per docs/protocol/v4-wire.md §4) and a read-only `subscriptionEpoch(sessionId)` accessor (the recovery re-subscribe ack may carry a moved epoch, S9). The frozen `Transport` INTERFACE is untouched; both additions are on the concrete relay transport. The phone-facing `resync` param shape on the production relay is `[REQUIRES VALIDATION]` (Phase 12 canary; INV-15 bars it from CI).
2. **Degraded→retry budget fix (S5):** an explicit user retry (`reconnectNow`) now opens a fresh reconnect budget — previously an exhausted budget could never leave Degraded, dead-ending §14.4.1's `Degraded → (manual retry) → Reconnecting` path. Budget still bounds AUTOMATIC retrying (§11.5 "attempt budget then degraded state (user action available)").
3. **Event-fault isolation (matrix finding):** the projection collector now isolates per-event faults (§28.4/INV-8 parity with the transport's parser) — one bad event can never permanently kill the ordered pipeline; the session is labeled stale and processing continues.
4. **INV-4 hardening found by the matrix:** interaction retries REUSE the pending outbox entry (same commandId, user-gated) and never duplicate a live entry — the §20.3 row-5 contract, previously only implicit.
5. **Escalation loop guard (S10):** a repeat resync escalation for the same session forces the authoritative snapshot ("resync; failure → full snapshot") — resume loops on unrepairable gaps are structurally impossible.
6. **MockZCode incremental extensions (§35.4 sanctioned):** rows.ts-schema-faithful synthetic rows (the typed decoder fails closed on known-kind violations), a per-session delta-frame log with resync resume/forced-snapshot semantics, connection-rejection fault class, §7.4.4 CAS enforcement (missing base → rejected; stale base → stale), epoch bump, S12/S13 snapshot stamping. No production behavior invented — all shapes grounded in the verified schema sources.

## 5. Security / invariants (§42 walk, Phase 5 delta)

Full walk recorded in `docs/invariant-coverage.md` (P5 rows). Headlines: INV-1 upheld (authoritative-snapshot seeding is the only non-table entry; event-class illegal transitions refuse); INV-4 hardened (single-commandId retries proven across 10k schedules); INV-5 held (every new buffer bounded before allocation: hold ≤ 500 ops verified limit, streaming ≤ 8 KiB/row, manager actor serialization); INV-8 held (unknown ops counted, event-fault isolation); INV-14 held (fail-closed answer policy is the ONLY submission gate; hostile-option property proven; known-kind decode violations escalate whole-frame resync, never partial merge). No new permissions, no crypto, no secrets in logs (ids + counts only, §34).

## 6. Tests executed (full repo + device, 2026-09-28)

`gradle test assembleDebug` → **BUILD SUCCESSFUL — 272 tests, 0 failures**:

| Module | Tests | Delta |
|---|---|---|
| core:common (Phase 2) | 10 | 0 (regression green) |
| core:protocol (Phase 1+2) | 27 | 0 (regression green) |
| core:transport (Phase 2 + P5 additive resync) | 37 | 0 (regression green incl. storm/corpus suites) |
| core:security (Phase 3) | 33 | 0 (regression green) |
| :domain (P4 + **P5.1/5.2/5.4 machines + property suites**) | 78 | +47 |
| :data (P4 + **P5.3/5.4 coordinators + S1–S17 matrix**) | 57 | +17 |
| features:pairing (Phase 3) | 16 | 0 (regression green) |
| tools:mockzcode (P2 + P5 extensions, self-test green) | 5 | 0 |
| spike (Phase 1 regression) | 9 | 0 |

`gradle :app:connectedDebugAndroidTest` (zcode-atd, API 30) → **4/4 PASS** (Phase 3/4 device suites re-executed green against the Phase 5 tree): pairing E2E forensics/first-pair, rotation, wrong-hash E5 re-pair, ADR-008 persistence timing.

## 7. Files changed (phase scope)

- `domain/`: `reducer/TopicEvents.kt`, `reducer/ConversationTopicReducer.kt`, `state/TaskStateMachine.kt`, `state/PermissionStateMachine.kt`, `recovery/DeepLinkRoute.kt`, `recovery/NotificationReconciler.kt` (+ 5 new test files)
- `data/`: `session/ConversationGateway.kt`, `session/ConversationSessionManager.kt`, `session/RecoveryCoordinator.kt`, `session/InteractionCoordinator.kt`; `build.gradle.kts` (+`:core:transport` — the §40.2 DATA→TRANS allowed edge — and `:tools:mockzcode` testImplementation, INV-15 test-tooling only) (+ `RecoveryScenarioMatrixTest`)
- `core/transport/`: `RelayTransport.kt` (additive `resyncSession`, `subscriptionEpoch`, Degraded-retry budget fix)
- `tools/mockzcode/`: `MockZCode.kt` (schema-faithful rows, delta log/resync, CAS, fault classes)
- `docs/`: `invariant-coverage.md` (new), `docs/adr/phase-5-gate.md` (this file)
- `architectural.md` (permitted bookkeeping only: §43.2 Phase 5 mark + header status line)

## 8. Known limitations (accepted)

1. **`resyncConversationV4` live-relay param shape `[REQUIRES VALIDATION]`**: the verified schema field set is carried and MockZCode implements both forms; the production relay's exact phone-facing shape rides the Phase 12 manual compatibility canary (INV-15 bars live-relay checks from CI). The guard machinery makes behavior safe under either outcome (resume replays are stale-guard verified; escalation ends in forced snapshot).
2. **Permission event-plane binding is Phase 7**: the InteractionCoordinator consumes the verified §20.1 payload models through a typed port and reconciles from VERIFIED row fields (`pendingApproval` + `approvalInteractionId`, rows.ts; synthesized option vocabulary §7.4.4); binding to the real transport event shapes (Q7/Q11) lands with the Phase 7 approval E2E against a live desktop.
3. **INV-11 (per-screen state suites)** is a Phase 6 gate by plan; Phase 5 delivers the observable state layer (stale overlay, Degraded, epoch-reject surfaces) the screens will bind.
4. **Journal writes are per applied frame** (correctness-first §15.4); batching/debounce is deferred to Phase 6/11 performance work (§29 batching note).
5. **Device evidence is emulator-based** (API-30 ATD), consistent with Phases 3/4; the §35.6 physical fleet sweep remains a pre-release gate.
6. **CI workflow**: unchanged (root `gradle test` picks up all modules); the first real GitHub run still requires a push, which remains forbidden this session (same limitation as Phases 2–4).
7. **Resync escalation counter resets on snapshot apply only** — a second UNRELATED consecutive gap escalation would force a snapshot rather than resume first; always safe (snapshot replacement is authoritative), slightly conservative.

## 9. Verdict

**PASS WITH KNOWN LIMITATIONS.** The correctness core is exhaustively tested: randomized schedules cannot produce ambiguous merges, every §15 recovery scenario behaves correctly against the protocol-faithful mock, permission races cannot double-apply, and every invariant has a named check. **This gate satisfies the second freeze condition (§51.4c)** — state ownership and recovery semantics are proven; Phase 6 (Tasks & Conversation UI) is unlocked per §43.1.
