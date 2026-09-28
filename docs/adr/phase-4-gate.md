# Phase 4 Gate — Domain/Data Layer

| Field | Value |
|---|---|
| **Phase** | Phase 4 — Domain/Data Layer (§43.2) |
| **Status** | **PASS WITH KNOWN LIMITATIONS** (all six subphase exit gates green with executed evidence; limitations listed in §8, accepted in writing) |
| **Recorded** | 2026-09-28 19-04-14 BST |
| **Verifier** | model: `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); tools: Gradle 9.7.1, AGP 9.3.3, Kotlin 2.4.20 (JVM/Android, toolchain 17), Room 2.8.5 (KSP 2.3.12), DataStore 1.2.1 + protobuf-javalite 4.36.2 (plugin 0.10.0), Robolectric 4.17 (SDK 30 sandbox), kotlinx-coroutines-test 1.10.2, kotlinx-serialization-json 1.9.0, Tink 1.23.0 (credential forensics), emulator 37.1.11 (AOSP ATD arm64 API 30, `zcode-atd`), platform android-37/30 |
| **Preconditions** | Phase 3 credential flows stable ✓ (`docs/adr/phase-3-gate.md`: PASS WITH KNOWN LIMITATIONS; its 3 instrumented E2E tests re-executed green THIS session against the Phase-4 credential binding, see §2/§6) |
| **Schema freeze** | Room schema **v1 frozen** — `data/schemas/com.zcode.android.data.db.ZcodeDatabase/1.json` committed; 10 entities (tasks, task_workspace_meta, conversation_session_meta, conversation_rows, interactions, outbox, journal_state, journal_subscriptions, journal_pending_commands, drafts) |

---

## 1. Scope delivered (§43.2 Phase 4)

**In scope, delivered:** §16 models as pure-Kotlin `:domain` (typed id value classes incl. `WorkspaceIdentityKey` INV-12, sealed conversation-row hierarchy ported from the VERIFIED rows.ts v3.14.3 with INV-8 unknown tolerance, tolerant status enums, §20.4 fail-closed permission-option semantics, outbox state machine §17.5 + retry classifier §28.6, recovery-journal model §15.4, repository interfaces, store-op contract); `:data` Android library (Room schema v1 + DAOs + indices + schema export, ETag task cache, conversation page cache with epoch-guarded reconciliation and §17.3 bounds, interaction store bounds/TTL, Room outbox with machine-gated transitions, transactional recovery journal, drafts, DataStore-proto preferences/endpoint/credential stores with additive-first golden-v1 migration evidence); app composition wiring (process-scoped `AppStores`, credential binding swap per ADR-008).

**Non-scope, respected:** no UI (Phase 6); no DirectTransport stores (Phase 10); no runtime projection reducers / per-op stale-guard / §14 machine wiring (Phase 5.1–5.4 — the repositories expose the store contract those reducers sit on); no notification broker (Phase 8); no MockZCode expansion (§35.4 incremental rule).

## 2. Subphase exit gates — evidence (all executed 2026-09-28)

| Subphase | Exit gate | Evidence |
|---|---|---|
| 4.1 Domain models | model suite green; INV-8 | `:domain` 31/31 tests: `ConversationRowDecoderTest` 8/8 — all nine verified row kinds decode (fixtures derived from rows.ts @ 29628c9, read 2026-09-28); unknown KIND → `Unknown` + COUNTED with raw retained; unknown FIELDS tolerated + retained in `raw`; unknown ENUM values → tolerant `UNKNOWN(raw)` cases; missing REQUIRED fields on a known kind → typed `RowDecodeException` (fail-closed, §39.3/INV-14); `PermissionOptionTest` 3/3 — unknown option ids inert and NEVER allow-capable (§20.4/INV-14); `IdsTest`/`TaskStatusTest` — §7.11 identity derivation verbatim, blank-id rejection, distinct id types never conflated, status tolerant |
| 4.2 Room schema + DAOs | schema v1 frozen; migration test harness green | Schema export committed (1.json, 10 entities, verified programmatically); `DaoSuiteTest` 6/6 — per-table round-trips, ordering, pinned-exempt stale sweep, cap-victim selection (oldest-beyond-keep), outbox live-window/expire/counts, journal replace-per-snapshot with zero residue, draft upsert/discard; `MigrationHarnessTest` 2/2 — create-at-v1 → validate-open-at-v1 with committed schema assets, direct v1 open, SQL-level round-trip of the non-destructive classes |
| 4.3 Repositories & cache reconciliation | repo suites green; no divergence under §15 scenarios replayed at repo level | `ConversationRepositoryTest` 7/7 — snapshots REPLACE atomically reporting the previous epoch (§15.5); stale-epoch delta ops REJECTED with byte-identical state (no merge, §15-S9); fresh sessions adopt the epoch; duplicate upserts idempotent by rowId (S7); removal truncates upward (delta.ts); text append patches cached rows; missing-row append → `Dropped` (S11 class); per-session bound holds on every write (≤ 2×60+200, INV-5); corrupted cache rows dropped + COUNTED, never crash reads (INV-8); `ReconciliationPropertyTest` — 50 seeded randomized schedules × 60 ops (epoch flips, duplicates, out-of-order, stale-epoch probes, truncations, appends) with a model-based divergence check + bound assertion after EVERY op, and the §17.3 task-cache flood (700→500 newest-kept, ETag 304-class skip, removal-reflecting replace, 7 d stale sweep) |
| 4.4 Command outbox | outbox suites green; no path duplicates a server-contacted command (INV-4) | `OutboxStateMachineTest` 13/13 (domain): happy path, ack-status mapping (accepted/duplicate/noop → ACKED; rejected/failed → FAILED_TERMINAL; stale → FAILED_STALE), pre-contact failure + retry, send-lost → UNKNOWN (never assume success, §15.5), cancel PRE-transmit only, no double-transmit, user-gated retry from UNKNOWN with the SAME envelope, terminal states accept nothing, TTL on live states only, accept only from DRAFT; `CommandRetryClassifierTest` 3/3 — §28.6 rows verbatim, unknown types fail closed to USER_GATED, auto-retry matrix; `OutboxRepositoryTest` 8/8 (Room): uuid-v7 minted exactly once per accept, illegal transitions persist NOTHING (row unchanged + attempt count intact), ack detail recorded verbatim, pendingRefs project the §15.4 journal list, TTL sweep touches only live entries, unknown persisted state → typed `StorageCorruption` (§33 storage) |
| 4.5 Recovery journal persistence | journal drills green; journal never corrupts (§32.3) | `JournalRepositoryTest` 4/4 — whole-snapshot replace with zero residue; **crash-consistency via a SQLite RAISE(ABORT) trigger firing mid-transaction (after the state upsert, at the subscriptions insert): Room rolls back, the previous COMPLETE snapshot is intact**; cold-start replay from a closed file DB (§15-S2 process death) restores lastAppliedPerTopic; clear destroys the journal with the endpoint identity (§14.4.1 Closed) |
| 4.6 DataStore prefs/config | store suite green; additive migration; no secrets in plaintext | `DataStoreStoresTest` 7/7 — endpoint round-trip + replace-by-id; unknown endpoint kind → typed `StorageCorruption` (never guessed); prefs defaults for a never-written store (§27: MINIMAL notifications, screenshot protection ON, cache ON); unknown enum values fail closed to the documented default; **additive-first golden-v1 wire blob (fields 3–4 absent) parses with presence-aware domain defaults — never the proto zero-value (§32.3)**; credential envelope: blob round-trip, rotation replace, per-name delete to empty store (§12.7), at-rest forensics with a seal-before-store XOR stand-in for Tink AEAD (plaintext marker absent from disk, ciphertext round-trips through the inverse transform), corrupted envelope file fails CLOSED and is left byte-identical (no destructive fallback, §32.3) |

## 3. Phase-level acceptance criteria (§43.2)

| Criterion | Verdict | Evidence |
|---|---|---|
| All store suites green | ✅ | **JVM: 208 tests / 0 failures across all 9 modules** (domain 31 + data 40 new; 137 pre-phase regression green — see §6) |
| ADR-008 revisit triggers evaluated with measurements | ✅ | On-device measurement (`PersistenceTimingInstrumentedTest`, API-30 ATD): persistence first-touch total **≈ 38 ms** (room.open=0, room.firstWrite=36, room.firstRead=1, outbox accept=1; datastore first reads 0–1 ms, endpoint save 4 ms) — **trigger NOT tripped** (§17.2 threshold: Room adding > 100 ms to cold start). Q15 (Room vs DataStore for the conversation cache) resolved accordingly: Room stays. Logcat transcript: `room.openMs=0 … adr008.trigger(>100ms cold-start delta): NOT TRIPPED — Room split confirmed`. Full app cold-start budgets (§28.1) remain Phase 6/11 Macrobenchmark gates |

## 4. Architectural corrections / completions (§41.6 process)

1. **ADR-014** records the concrete persistence toolchain (Room 2.8.5/KSP 2.3.12, DataStore 1.2.1 + protobuf-javalite 4.36.2) including the AGP-9 protobuf-plugin wiring deviation (explicit `java` builtin with `lite` option).
2. **§17.5/§14.2 persistence-engine wording**: the lines "Persistence: DataStore journal" (§17.5) and the DataStore entries for Pending commands / Draft prompt (§14.2) predate the v1.0.1 Red-Team correction E4/ADR-008; implemented per ADR-008 (Room for outbox/journal/interactions/drafts/projections). Annotated in place with `[CORRECTED 2026-09-28, Phase 4]` — no silent reinterpretation.
3. **§16 ConversationRow typed port completed** against the verified rows.ts source (reference clone read 2026-09-28): the Phase-2 generic row (raw + rowId + kind) now has typed wrappers whose decode policy implements the exact INV-8/INV-14 split (unknown kinds/enums/fields tolerate; required-field violations reject safely). No architectural.md change needed — §16 already specified this.
4. **Credential binding swap executed** as planned by the Phase-3 `FileCredentialStore` note and §12.4: production credential storage is the DataStore-proto envelope store (ADR-008); Phase-3 instrumented suites re-pointed to the production binding with assertions unchanged; the interim file binding was removed from the app's main sources.
5. **Store-op contract defined** (`ConversationStoreOp`/`StoreApplyResult`): the Phase-5 projection reducers sit ON these repositories — the store's epoch rule is mechanical (mismatch → zero-mutation rejection), and the per-op `(logEpoch, seq)` judgment remains Phase 5.1 scope. This boundary is documented in `ConversationRepository` to prevent accidental reducer-early-implementation.

## 5. Security / invariants (§42 walk, Phase 4 delta)

| INV | Verdict | Evidence |
|---|---|---|
| INV-1 | upheld | every store is a cache of desktop truth; repositories expose projections + apply ops; no local authority claims (§14 table implemented as-is) |
| INV-2/6 | upheld | `:domain` is pure Kotlin (no Android/OkHttp/Compose; module graph per §40.2: domain→core:common only); `:data` depends on domain+protocol+security per the allowed edges; no UI exists to couple |
| INV-3 | upheld | credential envelope stores OPAQUE bytes; at-rest forensics (plaintext marker absent from disk); corrupted envelope fails closed; no secrets in any log path (repositories log nothing; ZLog unchanged) |
| INV-4 | upheld | outbox transitions are machine-gated in ONE place; persistence cannot mutate state directly; illegal transitions throw without writing; commandId minted exactly once per accept (uuid v7, stable across user-gated retries); duplicate-ack → ACKED (dedupe); TTL 24 h sweep |
| INV-5 | upheld | bounds asserted in tests: conversation rows ≤ 2×60+200 per session (evicted after EVERY write), tasks ≤ 500/workspace, interactions ≤ 64, outbox TTL; property suite asserts the conversation bound after every op across 3,000 randomized ops |
| INV-7 | upheld | Room is the APP's database; no sqlite driver against the desktop store; INV-7's CI dependency check unchanged |
| INV-8 | upheld | typed row decode with unknown-tolerance + counting; corrupted cache rows dropped + counted; unknown relay/persisted enums fail closed to typed UNKNOWN/StorageCorruption — tested |
| INV-9 | upheld | no write-capable desktop surfaces built (Phase 9 gate) |
| INV-10 | upheld | zero custom crypto: Tink sealing stays in core:security; the data layer stores ciphertext opaquely; HMAC untouched |
| INV-11 | upheld | no screens in this phase (UI states are Phase 6/INV-11's UI suite) |
| INV-12 | upheld | `WorkspaceIdentityKey` typed value class; §7.11 derivation ported verbatim + tested; endpoint store keeps path fields as display-only metadata, never identity |
| INV-13 | upheld | page cache bound = 2 render windows + 1 page (verified limits as constants); rowsRange limit enforcement unchanged in core:protocol |
| INV-14 | upheld | unknown permission option ids NEVER allow-capable (domain test); unknown command types fail closed to USER_GATED; unknown persisted/kind/enum values fail typed; required-field violations reject safely |
| INV-15 | upheld | no production relay, credentials, QRs, or personal data anywhere in tests/fixtures — synthetic data only (e.g. `d_synthetic`, `wss://example.invalid`); MockZCode untouched |
| INV-16 | upheld | desktop paths appear only as display-only metadata fields (documented at the type + proto schema); no feature constructs desktop paths; conversation/page keys are (sessionId, rowId), never paths |

## 6. Tests executed (full repo + device, 2026-09-28)

`gradle test assembleDebug` → **BUILD SUCCESSFUL — 208 tests, 0 failures**:

| Module | Tests | Delta |
|---|---|---|
| core:common (Phase 2) | 10 | 0 (regression green) |
| core:protocol (Phase 1+2) | 27 | 0 (regression green) |
| core:transport (Phase 2) | 37 | 0 (regression green) |
| core:security (Phase 3) | 33 | 0 (regression green) |
| **:domain (Phase 4.1)** | **31** | new |
| **:data (Phase 4.2–4.6)** | **40** | new |
| features:pairing (Phase 3) | 16 | 0 (regression green) |
| tools:mockzcode (Phase 2) | 5 | 0 |
| spike (Phase 1 regression) | 9 | 0 |

`gradle :app:connectedDebugAndroidTest` (zcode-atd, API 30) → **4/4 PASS**:

| Test | Result | Note |
|---|---|---|
| pairConnectRelaunchResumeUnpairWipe_forensicsClean_firstPairUnder60s | PASS (3.9 s) | Phase-3 E2E re-executed against the Phase-4 DataStore credential binding |
| rotation_sameSidReseal_classifiesRotated_andAtomicallyReplaces | PASS (2.6 s) | re-pointed to the production binding; assertions unchanged |
| wrongHash_sealedButRejectedByRelay_E5RePair | PASS (1.8 s) | unchanged |
| measurePersistenceFirstTouch | PASS (0.09 s) | NEW — ADR-008 measurement (§3) |

## 7. Known limitations (accepted)

1. **ADR-008 measurement fidelity**: first-touch timings are from the API-30 ATD emulator (not the §35.6 physical fleet), and app cold-start budgets (§28.1) are not measured — those are Phase 6/11 Macrobenchmark gates by plan. The revisit trigger (> 100 ms) is evaluated against the cold-start-relevant first-touch cost, which is ~38 ms with wide margin.
2. **Corrupted credential-envelope FILE remediation UX** (probability negligible: DataStore writes are atomic + backup-excluded): the store fails closed (typed error, file untouched); the user-facing recovery surface belongs to the Phase 6 settings/recovery screen. Recorded in ADR-014 §5.
3. **Migration harness proves v1→v1 only** — real cross-version migration drills become executable when schema v2 exists (the committed v1 export + mounted-assets harness is the mechanism, proven green).
4. **Golden-v1 blob is hand-encoded wire bytes** (documented field/tag layout in the test): the §13.1 TS-generated fixture pipeline remains the Phase-2 known limitation (§39.4 drift tooling); the proto golden blob is wire-exact and presence-checked.
5. **CI workflow**: unchanged from Phase 2/3 (new modules are picked up automatically by the root `gradle test`); the first real GitHub run still requires a push, which remains forbidden this session (same limitation as Phase 2 §7.5 / Phase 3 §8.6).
6. **Prototype-only consumers**: repositories have no production callers yet (Phase 5 wires the reducers/transport); the store contracts are frozen by the interface + suite evidence above.

## 8. Files changed (phase scope)

- `settings.gradle.kts` (+`:domain`, `:data`), `gradle/libs.versions.toml` (+room/ksp/datastore/protobuf)
- `domain/**` (new: build + ids + models + outbox + journal + repo interfaces, 4 test files)
- `data/**` (new: build + protos + db entities/DAOs/database + schema export 1.json + repos + stores, 8 test files)
- `app/build.gradle.kts` (+`:data`), `app/src/main/.../AppContainer.kt` (credential binding swap), `app/src/main/.../AppStores.kt` (new), `app/src/main/.../security/FileCredentialStore.kt` (removed — production binding supersedes; see §4.4), `app/src/androidTest/.../PairingE2eInstrumentedTest.kt` (binding-aligned, assertions unchanged), `app/src/androidTest/.../PersistenceTimingInstrumentedTest.kt` (new)
- `docs/adr/adr-014-phase4-persistence-stack.md` (new), `docs/adr/phase-4-gate.md` (this file)
- `architectural.md` (permitted bookkeeping only: §43.2 Phase 4 mark, header status line, §14.2/§17.5 `[CORRECTED 2026-09-28, Phase 4]` annotations)
