# Invariant Coverage Table — Phase 5 exit artifact

Phase 5.5 exit gate (§43.2-5.5): "each invariant INV-1…INV-16 mapped to ≥1 automated
check or explicit manual gate." This table is the audit. Every row names the
automated check (test class/method) or the explicit manual gate that holds it.
Phase 5 additions are marked **(P5)**; prior-phase checks re-verified at the Phase 5
full-suite run are marked with their origin phase.

| INV | Invariant (§42) | Automated check / gate | Kind |
|---|---|---|---|
| INV-1 | Android never authoritative for task/session state | `TaskStateMachineTest` — local event table refuses fabrication; only `applySnapshot` (authoritative) seeds state **(P5)**; `ConversationRepositoryImpl` epoch rules (`ConversationRepositoryTest`, P4); `RecoveryScenarioMatrixTest` S9/S12/S17 — desktop wins, overlay discarded | auto |
| INV-2 | UI never owns transport | Module graph (§40.2): no `features → core:transport` edge exists; `:domain` is pure Kotlin (no Android/OkHttp/Compose — `domain/build.gradle.kts` deps are core:common + coroutines only) | build-graph (CI gradle graph) |
| INV-3 | Credentials/secrets never enter logs/backups/crash/telemetry | `ZLogRedactionTest` (P2 property test — no secret substring in any output); `DataStoreStoresTest` at-rest forensics (P4); P5 delta: coordinator/manager logs carry ids + counts only (§34), no payload content — reviewed at the phase gate | auto + review |
| INV-4 | Permission actions idempotent + replay-safe (stable commandId; no auto-retry post-contact) | `PermissionRacePropertyTest` — 10,000 randomized schedules, zero double-apply + stable commandId + no unknown-option wire submissions **(P5)**; `OutboxStateMachineTest`/`OutboxRepositoryTest` (P4); `RecoveryScenarioMatrixTest` S15/S16 (coordinator cells: same-commandId retry, never duplicate a live entry) | auto |
| INV-5 | No unbounded queue or cache anywhere | Reducer hold-buffer bound (verified 500 ops) + 8 KiB/row streaming bound asserted in `ConversationTopicReducerTest` **(P5)**; store bounds after EVERY op in `ConversationTopicReducerPropertyTest` + `ReconciliationPropertyTest` (P4); `StormTest` (P2); transport bounded queues (P2) | auto |
| INV-6 | Transport replaceable; features branch on capabilities only | `ConversationGateway` interface — the projection subsystem consumes only the gateway port; `RelayConversationGateway` is the only binding (Phase 10 adds DirectTransport behind the same port) **(P5)**; no feature code exists above the port. **P10 UPDATE (2026-10-03): delivered** — `AppTransport : Transport, V4Plane` is the data/app-facing type; `RelayTransport` and `DirectTransport` both implement it via the shared `ChannelV4Plane` (`DirectTransportTest` auth-model-separation + hub switch-matrix tests prove swap-without-rewrite); features still branch on capabilities, never on kind | auto + structure |
| INV-7 | Desktop sqlite never read directly | No sqlite driver dependency against the desktop store in any module (dependency audit, P2–P4 unchanged); all history flows via protocol queries (`ConversationGateway`, P5) | dependency audit |
| INV-8 | Unknown-but-safe protocol shapes never crash | `ConversationRowDecoderTest` (P4); gateway unknown-op mapping → `ProjectionOp.Unknown` counted **(P5)**; `V4WireTest` unknown tolerance (P2); matrix S7/S11 — corrupt/unknown frames never crash the pipeline (P5 event-fault isolation: one bad event can never kill the ordered projection collector) | auto |
| INV-9 | No write ops to desktop filesystem/shell in MVP | No `file`/`terminal` write methods exist (Phase 9 gate; P4 carried); **P10: unchanged** — the shared `V4Plane` surface carried the read-only terminal/git/file signatures verbatim; `zcode-task.listTasks` is a read call (phase10-direct.md §6) | API surface |
| INV-10 | No custom cryptography; verified constructions exactly | `MockZCodeSelfTest` HMAC byte-compare (P2); `CredentialVaultTest` vectors (P3); P5 adds NO crypto (resync/journal/reducers only) | auto |
| INV-11 | Every screen implements error/offline/reconnecting/empty/loading | No screens in P5 (non-scope §43.2-P5); the STATE layer they will bind is delivered + tested (stale overlay, Degraded, EpochRejected surfaces) **(P5)** — the UI suite lands with Phase 6 (explicit manual gate for this phase) | manual (Phase 6) |
| INV-12 | Workspace identity never a filesystem path | `IdsTest` (P4); matrix + coordinators key everything by `SessionId`/`EndpointId`/`WorkspaceIdentityKey` — no path-typed identity anywhere **(P5)**. **P10 UPDATE (2026-10-03): `ConnectionHubDirectEndpointTest` asserts the §7.11 derivation on the direct plane both ways** — path-fallback when no `workspaceIdentity`, identity-wins when present (INV-12 direct-plane evidence) | auto |
| INV-13 | Large histories paginated/lazy; no full-snapshot for unknown-size sessions | `rowsRange` limit enforcement in protocol (P2); store page bounds `ConversationCacheBounds` asserted per-write (P4 + P5 property); S11 400-row fragmented fixture exercises the fragmented path **(P5)** | auto |
| INV-14 | Security-sensitive unknowns fail closed | `PermissionStateMachineTest` answer-policy rows (unknown id inert, empty option set NOT approvable, refine requires text) **(P5)**; `PermissionRacePropertyTest` — no hostile option id EVER reached the wire across 10k schedules **(P5)**; gateway known-kind decode violation → `DecodeFaulted` → resync, never partial merge **(P5)**; `PermissionOptionTest` (P4); matrix S13 fail-closed submission on a recovered card | auto |
| INV-15 | Production relay/real accounts never CI dependencies; no secrets in fixtures | Matrix + all suites run against MockZCode on localhost only (§35.2); synthetic creds (`d_syntheticdevice000001`); no capture content added in P5 | auto (CI shape) |
| INV-16 | Desktop paths never treated as Android paths | P5 touch point: the recovery subsystem never constructs desktop paths; workspace path fields remain display-only descriptors (P4 store audit unchanged). **P10: server-info `workspaces[].path` is display-only metadata** (phase10-direct.md §1 decoder drops path-less entries; keys derive via §7.11 — `DirectTransportTest` server-info tolerance test) | review + auto |

Manual gates recorded for this phase (§43.0 semantics):
1. INV-11's UI-state suite is Phase 6 work by plan — the Phase 5 manual gate is the
   state-layer handoff above (stale/degraded/error surfaces exist as observable state).
2. `resyncConversationV4` phone-facing param shape on the PRODUCTION relay is
   `[REQUIRES VALIDATION]` (verified schema shape carried; MockZCode implements both
   forms) — rides the Phase 12 manual compatibility canary (INV-15 bars it from CI).
3. The live-relay event-plane shapes for `permission.requested`/`resolved` (Q7/Q11)
   remain unverified — the InteractionCoordinator consumes the verified §20.1 payload
   models and reconciles from VERIFIED row fields; real-event binding is Phase 7 with
   the approval E2E.
