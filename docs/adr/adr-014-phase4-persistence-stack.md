# ADR-014: Phase 4 persistence stack — Room/DataStore/KSP/protobuf toolchain

| | |
|---|---|
| **Status** | Accepted (2026-09-28, Phase 4) |
| **Context** | §43.2 Phase 4 (Domain/Data Layer); ADR-008 (Room/DataStore split); §32.3 (migrations); §17.2 |
| **Decides** | Concrete dependency versions + build wiring for the ADR-008 persistence engines |

## Decision

1. **Room 2.8.5** (latest stable, Google Maven) owns the structured durable stores:
   tasks + ETag metadata, conversation rows + session-epoch metadata, interaction
   store, command outbox, recovery journal (state/subscriptions/pending), drafts.
   Schema v1 exported to `data/schemas/com.zcode.android.data.db.ZcodeDatabase/1.json`
   and committed (§32.3); KSP codegen.
2. **KSP `com.google.devtools.ksp` 2.3.12** runs the Room compiler on Kotlin 2.4.20 /
   AGP 9.3.3 / Gradle 9.7.1 (KSP2 is compiler-decoupled; verified by build).
3. **DataStore 1.2.1 (Proto)** owns preferences, endpoint descriptors, and the sealed
   credential envelope. Proto codegen via **protobuf-javalite 4.36.2** +
   `com.google.protobuf` Gradle plugin **0.10.0**.
   - **Wiring note (AGP 9 deviation):** AGP 9 registers the library plugin under an
     internal id, so the protobuf plugin's default java-builtin wiring does not fire
     (protoc fails with "Missing output directives"). `data/build.gradle.kts`
     registers the `java` builtin with the `lite` option explicitly for every
     generate task. If a future protobuf-plugin release restores AGP 9 defaults,
     the explicit block may be simplified (non-breaking).
4. **Proto schemas are additive-first (§32.3)**: all scalars declared `optional`
   (proto3 presence) so absent fields map to DOMAIN defaults, never the proto
   zero-value; a committed golden v1 wire blob proves the old-file-reads-new-schema
   path (`DataStoreStoresTest`).
5. **Corruption policy**: endpoint/preferences stores are cache-class →
   `ReplaceFileCorruptionHandler` (fresh init, §32.3 "cache only"). The credential
   envelope store has NO replace handler — corruption fails closed (typed
   CorruptionException; file left untouched), and the binding surfaces a typed
   failure; user-facing remediation UX belongs to the Phase 6 settings/recovery
   surface (recorded as a Phase-4 limitation).
6. **Credential binding swap** (the Phase-3 `FileCredentialStore` note's planned
   swap): production `CredentialStore` is now `data/DataStoreCredentialStore`
   (opaque Tink ciphertext in a proto envelope, `noBackupFilesDir`). The Phase-3
   instrumented suites were re-pointed to the production binding (assertions
   unchanged); the interim file binding was removed.

## Consequences

- `:domain` stays pure Kotlin/JVM (models, state machines, repository interfaces);
  `:data` is the only module with Room/DataStore/protobuf (§40.2 graph respected).
- Migration drills from v1 use `androidx.room:room-testing` with the committed
  schema exports mounted as test assets; harness proven green at v1
  (`MigrationHarnessTest`).
- Measured (API-30 ATD emulator, `PersistenceTimingInstrumentedTest`): persistence
  first-touch total ≈ 38 ms (Room open 0 / first write 36 / first read 1 / outbox
  accept 1; DataStore first reads 0–1, endpoint save 4) — the §17.2 ADR-008
  revisit trigger (> 100 ms cold-start delta) is NOT tripped.
