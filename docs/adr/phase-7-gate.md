# Phase 7 Gate — Commands & Permissions

| | |
|---|---|
| **Phase** | Phase 7 — Commands & Permissions, §43.2 |
| **Status** | **PASS WITH KNOWN LIMITATIONS** |
| **Date / time** | 2026-10-01 01-40-00 BST (final verification run) |
| **Verifier** | `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); toolchain: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20, Compose BOM 2026.09.00 (material3 1.4.0), Room 2.8.5, Robolectric 4.17; device leg: ATD emulator API 30 (`zcode-atd`) |
| **Preconditions** | Phase 6 PASS (2026-09-29 02-16-31 BST); Phase 5.4 permission machinery (§14.4.3/§20.3); Phase 4 outbox/drafts/interactions stores |

---

## Scope delivered (subphases 7.1–7.6)

| Subphase | Delivered |
|---|---|
| **7.1 Composer & drafts** | Pinned bottom composer (draft-bound `OutlinedTextField`, send, stop), §17.4 300 ms debounced Room drafts through the port scope, offline draft UX (§17.6: drafting allowed, send BLOCKED with explicit copy — no silent queuing), pending-send overlay (§14.2: SENDING / SENT_UNKNOWN / FAILED_RETRYABLE with user-gated Retry), restore-wipe fix (the store emission can no longer clobber a fast user's first keystrokes) |
| **7.2 Send path** | `CommandCoordinator` (data/session) — the ONE submission gate for non-interaction commands: §17.5 lifecycle through the REAL outbox (the Phase 6 `OutboxStub` retired), INV-4 idempotency (one commandId per accept; retries reuse the entry), §15.5 lost-outcome → UNKNOWN (never assumed success), §17.6 offline block before any mint, §28.6 SAFE_AGGRESSIVE auto-retransmit on reconnect (`AutoRetransmit` machine event + classifier gate), verified `sendText` payload (`{text}` + optional `requestedDelivery`/`heldQueueDisposition`/`expectedHeldQueueItemIds`), draft cleared ONLY on the accepted send (E8) |
| **7.3 Approval cards + inbox** | Pinned approval cards fed by the VERIFIED `pendingInteractions` state key (§9 — Q11 RESOLVED: replayable snapshots DO carry open interactions): tool name, summary, collapsed detail, EXACTLY the protocol's option set; consequence labels (§20.2); confirm steps for allowAlways/deny; workflowRefine text field; **unknown option ids render INERT (disabled "unsupported option") — INV-14 at the UI layer**; Approvals inbox (§18.3): cross-task open-interaction list from the interaction store, one-tap allowOnce, deny-confirm, tap-through to task, store-hydrated cards (`submitFromStore`) through the SAME fail-closed gate; honest offline note (answers blocked, nothing queued) |
| **7.4 User-input answers** | U9 plane: pendingInteractions `kind=userInput` → question card (prompt + the request's own options answered verbatim via `answer {optionId}` — the CLI-verified compat path; free-text via `answer {freeText}` when `payload.freeText`); `AnswerPolicy.decideUserInput` — fail-closed U9 variant (optionId outside the request set is inert; blank free text never sent) |
| **7.5 Stop / cancel / queue** | Stop control rendered ONLY from the authoritative `control.canStop/stopState` (§9 verified control block), `stop` + `cancelBackgroundWork` (SAFE_AGGRESSIVE), queue strip from `queue.items` with `sendQueuedNow`/`editQueueItem` (CAS: baseRevision from the projection's `revision`; `stale` acks → typed "queue changed — try again" copy), held-queue `choice` routing → the verified `heldQueueDisposition` dialog (clear/keep), `inputRouting` enqueue hint |
| **7.6 Race hardening** | `WritePathRaceHardeningTest`: 1,000 randomized tap × resolved-elsewhere × transport-death schedules over the REAL coordinators — R1 ≤ 1 minted commandId per interaction, R2 single commandId across transmissions, R3 zero post-resolution sends — plus the deterministic §20.3 cells (resolved-during-submit late acks, disconnect-mid-submit retryable with SAME commandId, duplicate taps no-op) |

**Also delivered (protocol port, spec-first):** the verified session-state projection (`docs/protocol/v4-wire.md` §9, from snapshot.ts/delta.ts @ `29628c9`, read 2026-09-30) — `SessionProjectionState` (control/queue/pendingInteractions/inputRouting/revision/backgroundWorks) with KEY-LEVEL whole-replacement merge (the protocol's own Object.assign rule, never deep-merge), per-key INV-8 tolerance (unknown keys ignored+counted, malformed known keys dropped+counted, tolerant enums); carried by the gateway (snapshot + `state.updated` patches) → reducer → manager `sessionState: StateFlow` → ports.

## Acceptance criteria (§43.2 Phase-Level) — evidence

| Criterion | Evidence | Result |
|---|---|---|
| §48 U4 — send prompt; draft never lost on any failure path (E8) | `CommandCoordinatorTest` (13): offline block mints nothing; mid-send death → UNKNOWN + user-gated retry with the SAME commandId; stale ack → FAILED_STALE; overlay bounded. `ConversationDraftLifecycleTest` (5): accepted send clears, offline/stale preserve + copy. `ConnectionHubWritePathTest.sendText…` (JVM E2E, real transport): send → ack → authoritative rows land → overlay reconciled → draft cleared. `Phase7InstrumentedTest` (device): draft survives a full activity recreation | PASS |
| §48 U8 — approve/deny; zero double-apply across 10k random schedules | Phase 5.4's `PermissionRacePropertyTest` (10k) + `WritePathRaceHardeningTest` (1,000 schedules incl. transport death) + `InteractionCoordinatorPhase7Test` (14) + `ConversationWriteUiTest` (§20.3 at UI level: deny confirms, unknown inert, allowOnce answers) | PASS |
| §48 U9 — answer user input | `InteractionCoordinatorPhase7Test` (options verbatim / freeText compat path / blank refused / injected optionId inert) + `UserInputCard` UI | PASS |
| §48 U24 — stop/cancel; aggressive retry safe | `CommandCoordinatorTest` (stop auto-retransmits from UNKNOWN on reconnect; sendText NEVER does) + `ConnectionHubWritePathTest.stop…` (JVM E2E: control flips stoppable → idle) + composer stop UI test | PASS |
| No silent offline queuing of approvals (§17.6) | Offline submit → `BlockedOffline` BEFORE any mint (test: `offline submit is BLOCKED — nothing minted, nothing queued`); approvals inbox offline note; device E2E `offline sends are BLOCKED` | PASS |

## Exit-gate checks (§43.2 per-subphase)

- 7.1 draft suite green — draft lifecycle + restore + debounce + E8 paths ✓
- 7.2 send E2E PASS — JVM E2E over real RelayTransport ↔ MockZCode (INV-4/idempotency/offline/CAS/stale) ✓
- 7.3 approval E2E PASS — pendingInteractions feed → card → allowOnce → mock effect clears the interaction; resolved-elsewhere race blocks the late submit ✓
- 7.4 suite green — user-input options/freeText/fail-closed ✓
- 7.5 suite green — stop/control, queue CAS + stale, cancelBackgroundWork payload ✓
- 7.6 randomized schedules — zero unsafe outcomes (1,000 + 10k Phase 5 heritage) ✓

## Tests executed (final verification runs, 2026-10-01)

- **JVM/Robolectric: 434/434 PASSED** (baseline at phase start: 368; +66 Phase 7). Modules: `:core:common` 14, `:core:protocol` 27, `:core:security` 33, `:core:transport` 59, `:domain` 90, `:data` 90, `:features:conversation` 40, `:features:pairing` 18, `:features:tasks` 13, `:app` 32, `:tools:mockzcode` 9, `:spike` 9. New Phase 7 suites: `SessionStateTest` (9), `CommandCoordinatorTest` (13), `InteractionCoordinatorPhase7Test` (14), `WritePathRaceHardeningTest` (1, 1,000 schedules), `ConnectionHubWritePathTest` (7, MockZCode E2E), `ConversationWriteUiTest` (8), `ApprovalsScreenUiTest` (5), `ConversationDraftLifecycleTest` (5), `MockZCodePhase7SelfTest` (4).
- **Instrumented (ATD API-30): 7/7 PASSED** — `Phase7InstrumentedTest.writePathJourney` (cold start → task list → conversation → draft typed → full activity recreation → draft restored), plus the full pre-existing fleet (`PairingE2e` ×3, `Phase6.captureFlow`, `PersistenceTiming`, `HistoryPaginationE2e`).
- **APK artifact**: `app/build/outputs/apk/debug/app-debug.apk` — `versionCode=20, versionName=0.7.0-phase7` (rebuilt fresh post-version-bump).

## Defects discovered during implementation — fixed with regression coverage

1. **Hub: `ensureCoordinator` raced itself** (found by `ConnectionHubWritePathTest`): the CONNECTED hook's `commandCoordinatorOrNull()?.onConnected()` ran concurrently with `openConversation`'s `ensureCoordinator()`; BOTH created a RecoveryCoordinator and the field kept the EMPTY one — every session projection flow (state/queue/interactions) read the orphaned-empty coordinator. **Fix:** `coordinatorMutex` single-flight. Regression: the write-path E2E (7 tests, 3× stability runs).
2. **ViewModel: draft restore could WIPE user typing** (found by the device journey): the restore collector's one-shot `draftLoaded` flag let a LATE store emission blank a just-typed draft — `vm.send()` then read an empty draft and silently no-op'd (E8 violation). **Fix:** restore applies only while the draft is empty; user content always wins.
3. **InteractionCoordinator: the retry path was unreachable** (first suite to execute it): a `UserGatedRetry` already moves the entry to TRANSMITTED; the unconditional follow-up `Transmit` threw an illegal transition that was swallowed — the retry silently became another UNKNOWN. **Fix:** Transmit applied only from ACCEPTED_LOCALLY. Regression: `transport death mid-submit keeps the card retryable with the SAME commandId`.
4. **MockZCode: effect pushes broke session seq continuity** — the initial snapshot now ANCHORS the seq head (the §9 contract: snapshots carry the alignment watermark) and effect rows/patches continue contiguously + survive into later snapshots/resync (no §15-S10 gap-hold loops).
5. **Composer UX: `EmptyBody`/`LoadingBody` used `fillMaxSize`**, pushing the pinned composer off-screen on empty/loading conversations. **Fix:** `weight(1f)`.
6. **Pre-existing: `:spike` no longer compiled** — Phase 6 field round 18 added `Reassembled.Poison` to the codec; the untouched Phase-1 spike's exhaustive `when` broke the whole `gradle test` invocation. **Fix:** mechanical spike-parity branch (ack + drop), recorded here per §41 (no wire behavior changed).

## Changes to prior-phase tests (owned by this phase)

- `ConversationPipelineTest`: `FakePort` extended with the Phase 7 port members (test-fake surface only); `ConversationUiTest` call sites extended with the write-surface parameters (no behavior assertion weakened).
- `ConnectionHubIntegrationTest`/`DefaultTasksPortTest`: hub construction gained the real outbox/drafts fakes; `FakeInteractionRepository` gained `loadStored` hydration.
- `MockZCode`: CAS currency for command-effect runs reads the stateRevision (one desktop, one revision); `commandEffects`/`emitStateKeys` default OFF so every Phase 2–6 suite keeps its exact prior contract (verified: all suites green unmodified apart from the fake-surface extensions above).

## Known limitations (accepted)

1. **The on-device instrumented journey ends at draft persistence.** The send → approve → stop segment is exercised end-to-end on the JVM against the REAL transport + MockZCode (`ConnectionHubWritePathTest` — same codecs, same state machines, same coordinators), but the multi-recreate device journey leaves a ghost Compose composition after the restore bounce: the compose-test click resolves a detached `composer_send` and `vm.send()` never runs (device log evidence: no `command.sendPrompt` entries; JVM E2E green). The on-device write-path canary against a live desktop remains a release-adjacent manual ritual (§43 Phase 1 pattern, INV-15).
2. **Question-array (`AskUserQuestion`-style) user-input payloads render read-only** ("answer on the desktop") unless `freeText` is set: the multi-question `answer {action, content}` content shape is CLI-side and was not ported (no guessed shapes, §4.1). Single-prompt options/freeText U9 flows are fully answered. Logged as an open question (Q18-adjacent).
3. **Restore-bounce after process recreation**: after a recreate while inside a conversation, the nav can bounce to the task list (restored back stack vs the pairing gate's async re-emission); the draft survives either way (tested), and deep-link routing (§24.7, Phase 8) supersedes this path.
4. **Live-desktop verification of the command plane** follows the Phase 1 manual-canary ritual (INV-15): the write path is verified against MockZCode, which is grounded in the verified §9/§7.4.4 schemas but is still a mock; the weekly drift watch (§39.4) covers schema drift.
5. **`fullAccess`/custom permission options render inert** ("answer on the desktop") — the §20.4 fail-closed rule applied to option ids outside the recognized vocabulary, even though the verified payload marks them `kind: custom`.

## Invariant verification (§42 — Phase 7 delta)

| Invariant | Verification |
|---|---|
| INV-1 (never authoritative) | All mutations are commands through the outbox; authoritative states (`pendingInteractions`, `control`, `queue`, rows) always win; disappearance reconcile flips cards by-elsewhere (tested) |
| INV-2/6 (UI never owns transport) | Features see ONLY the ports (`ConversationPort`, `TasksPort`); ApprovalsScreen consumes `ConversationPort` from `features/conversation/ui`; no transport type above `core:transport` |
| INV-3 (no secrets in logs) | New log lines carry ids/counts only; the redaction suite stays green |
| INV-4 (idempotent commands) | `CommandCoordinatorTest` + `WritePathRaceHardeningTest` R1/R2 + `InteractionCoordinatorPhase7Test` (single mint, same-commandId retries, no post-resolution sends) |
| INV-5 (no unbounded queues) | Pending-send overlay hard cap (64, tested); all buffers unchanged |
| INV-8/14 (unknown-safe, fail-closed) | Per-key state decode tolerance (counted); unknown option ids inert at the UI AND the gate (UI test + gate tests); unknown interaction kinds never actionable |
| INV-11 (state screens) | Composer offline copy, queue paused labels, approvals offline note, pending-send degraded states |
| INV-12/16 (identity/paths) | Unchanged; drafts keyed by (endpoint, workspaceIdentityKey, task) |

## ADR / correction references

- **`docs/protocol/v4-wire.md` §9** (new, this phase): the verified session-state keys, StatePatch key-level replacement semantics, Q11 RESOLVED (snapshots carry open interactions), and the Phase 7 command payload shapes.
- No architecture decision changed: the write path uses the Phase 4 outbox, the Phase 5 permission machinery, and the §11.2 transport contract exactly as specified. `ApprovalsScreen` lives in `features/conversation/ui` (its data plane IS `ConversationPort`); §40's `features/permissions` split remains available if the inbox grows independent surfaces.

---

**Phase Exit Decision: PASS WITH KNOWN LIMITATIONS** — all six subphase exit gates green with executed evidence; limitations 1–5 accepted and documented; no §47 No-Go condition triggered. Phase 8 (Notifications & Background) prerequisites are met.
