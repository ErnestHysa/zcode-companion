# Phase 3 Gate — Authentication & Secure Pairing

| Field | Value |
|---|---|
| **Phase** | Phase 3 — Authentication & Secure Pairing (§43.2) |
| **Status** | **PASS WITH KNOWN LIMITATIONS** (all six subphase exit gates green with executed evidence; limitations listed in §8, accepted in writing) |
| **Recorded** | 2026-09-28 17-52-46 BST |
| **Verifier** | model: `account:zai-individual-coding-plan/GLM-5.3-Flash` (ZCode harness); tools: Gradle 9.7.1, AGP 9.3.3, Kotlin JVM/Android compiler 2.4.0 (toolchain 17), Compose BOM 2026.09.00 (material3 1.4.0), Tink 1.23.0, androidx.biometric 1.1.0, Robolectric 4.17, OkHttp 5.1.0, Android SDK build-tools 35/36 + platform android-37.0/36/30, emulator 37.1.11 (AOSP ATD arm64 API 30, `zcode-atd`) |
| **Preconditions** | Phase 2 core green ✓ (`docs/adr/phase-2-gate.md`: PASS WITH KNOWN LIMITATIONS; 88-test suite re-verified green in this session before implementation); threat model current ✓ (§26 v1.0.1; re-walked below per §26.6) |

---

## 1. Scope delivered (§43.2 Phase 3)

**In scope, delivered:** §12 full — `core:security` (PairingLinkParser §12.2, CompatibilityGate §39.2, CredentialVault custody §12.4/§26.3 with Tink+Keystore sealing, JcaCredentialSigner + KeystoreHmacSigner §7.6/INV-10, BiometricGatePolicy + AndroidBiometricGate §3.3, ZLog secret registration INV-3); pairing UI flow (`features:pairing`: pure flow state machine with E1–E7, Compose screens per §18.3, CameraX+ML Kit scanner, manual entry first-class); `app` composition root (MainActivity single-activity, AppContainer manual DI, DefaultPairingPort = the only security↔transport seam, dataExtractionRules/backup exclusions §12.4/§26.2-T6); rotation/unpair/secure wipe (§12.7) with forensic verification; RelayTransport workspace discovery for the §9.5 pairing-completion step; MockZCode standalone launcher (device E2E, INV-15-clean).

**Non-scope, respected:** multi-endpoint UI (MVP single endpoint; different-sid QR → `Conflicts` requiring explicit unpair), direct-endpoint credentials (Phase 10), no task/conversation surfaces (Phase 6), no push/notifications (Phase 8).

## 2. Subphase exit gates — evidence (all executed 2026-09-28)

| Subphase | Exit gate | Evidence |
|---|---|---|
| 3.1 QR ingestion & validation | parser suite green; zero state writes on invalid input; E1–E3 handled | `PairingLinkParserTest` 19/19: valid shapes (prod/test/legacy-v3/dev-loopback origins, URL-encoded params, 16–64 B hash tolerance); every typed rejection (NOT_A_URL, UNSUPPORTED_ORIGIN incl. the `user@host` spoof class, WRONG_ROUTE exact-match, MISSING_PARAM ×6, BAD_SID, BAD_HASH, FUTURE_TIMESTAMP w/ skew grace, DUPLICATE_PARAM, OVERSIZED_INPUT, BAD_APP_VERSION); hostile corpus (javascript:/data:/file:, `//remote/v4` lookalike, oversized, malformed escapes); **fuzz 5,000 seeded mutations — zero throws, zero unvalidated Ready**; `CompatibilityGateTest` 3/3 (§39.2 matrix rows, garbage fail-closed, numeric comparison). Purity = side-effect-free functions; seal only after all gates (`DefaultPairingPort.validate` → `completePairing` ordering) |
| 3.2 CredentialVault + CredentialSigner | vector tests green; signer answers challenges without exposing material | `CredentialVaultTest` 9/9 (REAL Tink AES-256-GCM on JVM): **3 desktop cross-computed proof vectors byte-identical** (incl. the string-key createHmac semantics + specials-in-nonce case); vault-loaded signer reproduces vectors; seal→Create / same-sid→Rotated (gen bump, continuity to new material) / different-sid→Conflicts **with zero store writes**; at-rest forensic scan — plaintext hash absent from every raw blob; API-level exposure tripwire (reflection walk: no public method returns hash material; metadata is secret-free); corrupted envelope → fail-closed `null` (never throws, never half-opens); sealed secret registered with ZLog → adversarial logging scrubbed. Keystore-HMAC import variant implemented BEHIND FLAG (`KeystoreHmacSigner`, Phase-1-verified import semantics: UTF-8 bytes of the base64 hash STRING + KeyProtection) |
| 3.3 Biometric gating | gate tests green; E7 explicit; no silent fallback | `BiometricGatePolicyTest` full decision table (AVAILABLE→REQUIRE_GATE; SECURE_LOCK_ONLY→OFFER_OPT_IN; NO_SECURE_LOCK→REQUIRE_E7_CONSENT; declined→FAIL_CLOSED; consented→PROCEED_REDUCED_CONSENT). `AndroidBiometricGate`: CryptoObject challenge bound to an auth-required AndroidKeyStore AES key (per-op validity; first challenge mints the stored token under the authenticated op; invalidation → typed denial — never bypass). **On-device: the E7 path executed on the ATD emulator** (no secure lock → the explicit reduced-security consent screen appears; consent forwarded to the port and asserted in Robolectric `PairingScreensUiTest`); the CryptoObject happy path on biometric hardware is NOT verified this session (ATD has no fingerprint/enrollment — see §8.3) |
| 3.4 Pairing UI flow | pairing E2E PASS; first-pair ≤ 60 s | Flow reducer `PairingFlowTest` 9/9 (every E1–E7 branch + takeover + unpair + draft scoping). Robolectric Compose `PairingScreensUiTest` 7/7 (rejected-E2, compat-block-E3, E7-consent forwarding, E5 re-pair screen without retry, E6 takeover screen, unpair→wipe forwarding, welcome explainer). **Instrumented E2E on the API-30 ATD emulator, 3/3 PASS** (`PairingE2eInstrumentedTest`): real UI + real vault (AndroidKeyStore+Tink) + real RelayTransport vs in-process MockZCode — happy path (manual-entry variant of §12.2 step 1) → E7 consent → **Connected; first-pair measured 1101 ms** (budget 60 s, §48); logcat transcript: `pairing.validate.begin → vault.sealed → relay.connect → auth.challenge (nonce len=24) → auth.matched → relay.connected` |
| 3.5 Rotation, unpair, secure wipe | wipe completeness; rotation continuity; incident drill executed | Rotation: JVM `CredentialVaultTest` (Rotated classification, generation bump, signer continuity to new material, atomic metadata replace) **+ on-device** (`rotation_sameSidReseal…` PASS: Rotated gen 2, metadata replaced). Unpair wipe: `wipe()` deletes every blob + destroys the master key (JVM) **+ on-device forensics** (`pairConnectRelaunchResume…` PASS: `vault.wiped :: blobs=3 masterKeyDestroyed=true storeEmpty=true`; post-wipe AndroidKeyStore aliases `zcode-vault-master`/`zcode-vault-gate` absent; relaunch → unpaired Welcome). Persistence across relaunch (§15-S2 seed): recreate → resume card → reconnect with the SEALED credential → Connected. **§26.5 incident-response drill row 1 executed**: credential-leak response = rotate-in-place (same sid — exercised above) or unpair+wipe+re-pair (exercised above, incl. the desktop-"Stop" guidance string in the unpair dialog per §12.7) |
| 3.6 Security gate review | re-walk T1–T18; no open HIGH | §4 below. **No new HIGH.** Residual risks re-affirmed with Phase-3 deltas |

## 3. Phase-level acceptance criteria (§43.2)

| Criterion | Verdict | Evidence |
|---|---|---|
| Pairing E2E PASS | ✅ | Instrumented 3/3 on emulator (§2-3.4 row); the real-desktop leg is a manual ritual (§35.5) — **not executed this session (no user QR available; environmental)** — bounded by the Phase-1 live-relay proof of the same handshake the app replays, and by MockZCode grounding in the committed sanitized corpus |
| Security review PASS | ✅ | §4 walk; no open HIGH |
| INV-3 upheld | ✅ | ZLog registration at seal/load (property + adversarial test); transport logs nonce LENGTH only; device logcat shows zero secret material (§2-3.4 transcript); backup exclusion: `allowBackup=false` + `dataExtractionRules` (cloud-backup + device-transfer exclude-all) + legacy `fullBackupContent` + `noBackupFilesDir` storage |
| INV-10 upheld | ✅ | HMAC construction byte-identical (desktop vectors, JVM + device); zero custom crypto — Tink for local sealing exactly as §12.4 mandates; custody (vault) / capability (signer) / transport (SAM seam) separated (ADR-004 confirmed) |
| INV-14 upheld | ✅ | Parser fail-closed (typed rejections; fuzz); compat gate fail-closed on garbage; E7 consent required before any seal on no-lock devices (policy + UI + port triple-check); unknown/ambiguous input never interpreted leniently; corrupted envelopes fail closed to `null` |

## 4. Security gate review — T1–T18 re-walk (§26.6, Phase-3 delta)

| Threat | Phase-3 delta | Residual |
|---|---|---|
| T1 QR theft | Stale warning at 10 min (Q2: relay does not enforce `t` — warning is advisory); rotation replaces in place; desktop refresh-QR invalidates `[VERIFIED]` | unchanged (bearer-link model inherent) |
| T2 malicious app reads credential | Tink AEAD envelope, keyset wrapped by AndroidKeyStore (StrongBox attempted, software fallback); blobs in `noBackupFilesDir`; **at-rest plaintext hash never exists** (forensic test); signer-only access (API-level) | unchanged (compromised OS documented) |
| T3 overlay/screen-scrape on approval UI | Not in Phase-3 surface (approvals are Phase 7); pairing screens carry no secret content; FLAG_SECURE toggle remains §27 Phase-6+ setting | deferred, tracked |
| T4 ADB/debug leak | Debug builds log lifecycle events only (ZLog INFO; nonce length); no secrets in any log line (device transcript verified); redaction sink central | unchanged |
| T5 crash/analytics leakage | Still zero third-party SDKs | none |
| T6 cloud backup exfiltration | `allowBackup=false` + dataExtractionRules exclude-all (cloud + device transfer) + legacy rules + noBackupFilesDir | none for Phase-3 data |
| T7 clipboard sniffing | Manual entry is explicit user paste; parser never reads the clipboard; no auto-read anywhere | unchanged |
| T8 lock-screen notification leak | Notifications are Phase 8 | deferred |
| T9 stolen phone | Biometric/consent policy per §3.3; E7 explicit reduced-security path (honest labeling); keystore-bound gate key when biometrics available | E7 devices = reduced security BY EXPLICIT CONSENT (documented UX) |
| T10 stolen/compromised desktop | Out of app scope; unpair guidance incl. desktop "Stop" | unchanged |
| T11 MITM | WSS only for verified origins (parser enforces; http only for loopback dev class, debug-only cleartext config); no user-overridable cert validation for relay | unchanged |
| T12 auth replay | Server nonce per challenge (Phase-1 live-verified); proofs never cached (signer computes per challenge; nothing retained) | unchanged |
| T13 credential reuse across endpoints | Different-sid QR while paired → `Conflicts` (no write; explicit unpair required); rotation only on same sid | none |
| T14 social-engineered approval | Approvals are Phase 7; fail-closed machinery pre-built (policy + §20.4 tests land Phase 5/7) | deferred |
| T15 malicious content rendering | Phase-3 UI renders only app-owned strings + display metadata; link field is input, never rendered as rich content | minimal |
| T16 push-bridge content | Phase 8 | deferred |
| T17 rooted device | StrongBox when present; gate is deterrent not DRM (documented §26.2-T17) | inherent |
| T18 endpoint spoofing | Origin allowlist (verified prod/test + loopback dev); userinfo-spoof (`@`) rejected; route exact-match; compat gate | unchanged |

**Verdict: no open HIGH.** Deferred items (T3/T8/T14/T16) are later-phase surfaces with their own gates (§26.6 Phase 8/10 + pre-release).

## 5. Architectural corrections / completions (§41.6 process)

1. **§9.5 workspace discovery implemented in the transport** — the §9.5 sequence (auth → workspace-list → bridge-open with the ACTIVE workspaceKey) was previously implicit; `RelayTransport.openBridgeInternal` now discovers the active workspace when the endpoint pins none (Q9-verified payload shape, Phase-1 live evidence). No Transport API change (§43.2-2.1 freeze holds); Phase-2 suites (corpus replay, storms) unchanged and green — their endpoints pin explicit workspaces.
2. **`CredentialMetadata` gained `endpointOrigin`** (non-secret endpoint identity, §9.13) so the relaunch resume path (§15-S2) can rebuild the WS URL without touching secret material. Additive, versioned envelope.
3. **AGP/compileSdk** — Compose BOM 2026.09.00 requires compileSdk ≥ 37: the §10.1-anticipated evaluation ("compileSdk 37 when AGP supports stable") resolves to compileSdk 37 for build targets; minSdk 26 / targetSdk 36 unchanged. Recorded in ADR-013.
4. **§14.4.1 machine vs pairing flow** — the pairing flow's `SecuritySetup` step sits between `Pairing` and `Authenticating` as the §2.6 journey prescribes; the connection machine itself is untouched.

## 6. Tests executed (full repo, 2026-09-28)

`gradle test --console=plain` → **BUILD SUCCESSFUL — 137 tests, 0 failures**:

| Module | Tests | Delta |
|---|---|---|
| core:common (Phase 2) | 10 | 0 (regression green) |
| core:protocol (Phase 1+2) | 27 | 0 (regression green) |
| core:transport (Phase 2) | 37 | 0 (regression green, incl. corpus replay after the §9.5 change) |
| core:security (Phase 3.1–3.3) | 33 | new |
| features:pairing (Phase 3.4) | 16 | new (9 flow + 7 Robolectric Compose) |
| tools:mockzcode (Phase 2) | 5 | 0 |
| spike (Phase 1 regression) | 9 | 0 |
| **Instrumented** `:app:connectedDebugAndroidTest` | **3/3 PASS** (4.0 s + 1.2 s + 1.1 s) | new |

## 7. Invariant verification (§42 walk, Phase 3 delta)

| INV | Verdict | Evidence |
|---|---|---|
| INV-2/6 | upheld | `features:pairing` imports neither security nor transport (consumer-side `PairingPort` only); the sole seam is `app/DefaultPairingPort` (composition root); module graph matches §40.2 |
| INV-3 | upheld | see §3 row 3; device logcat transcript clean; scrubbers property-tested |
| INV-4 | upheld (mechanism) | Phase 3 issues no commands; command plane untouched |
| INV-5 | upheld | no new unbounded structures; ZLog ring bounded (Phase 2) |
| INV-7 | upheld | no sqlite dependency anywhere in the new modules |
| INV-8 | upheld | parser/gate tolerate nothing silently; unknown shapes fail typed |
| INV-9 | upheld | no write-capable surfaces built (terminal/file out of scope) |
| INV-10 | upheld | see §3 row 4; ADR-004 CONFIRMED by evidence |
| INV-11 | upheld | every pairing screen implements its error/empty/loading states (§18.3 row 1); UI tests per state |
| INV-12 | upheld | no path-typed identities; endpoint identity = endpointId + deviceSid (not paths) |
| INV-13 | upheld | no history requests in Phase 3 |
| INV-14 | upheld | see §3 row 5 |
| INV-15/16 | upheld | MockZCode synthetic-only (device E2E in-process, no production relay); no real credentials/QRs/personal data committed; loopback cleartext is debug-only and mock-class |

## 8. Known limitations (accepted)

1. **Real-desktop pairing E2E not executed this session** — no user QR was supplied; the manual §35.5 leg (real desktop) remains a local ritual before release. Bounded by: the app replays the exact handshake Phase 1 proved live (connect/auth matched ×≥8, decode, recovery ×10), and the MockZCode ground truth is the committed sanitized corpus.
2. **Biometric hardware leg unverified** — the ATD emulator has no fingerprint enrollment, so the E7 reduced-security path is the on-device-verified branch; the `AVAILABLE`→CryptoObject challenge path is covered by policy tests + code-path symmetry and needs one enrollment session on real hardware (§35.6 matrix, pre-release).
3. **Biometric gating is optional in the MVP default posture** — the master key is not auth-bound by default (`biometricBound=false`); the gate challenges protected operations (wipe/reveal class) when biometrics are available, per §12.4 "where enabled". Making the default posture stricter (auth-bound master key with per-start unlock) is a settings deliverable of the Phase 6 shell, not a Phase 3 item; the mechanism is present and flag-gated.
4. **Push/KICKED-during-idle, background socket death, FGS** — Phase 5/8 scope; the transport's KICKED→takeover mapping was observed live in the E2E transcript (old socket KICKED when the relaunch reconnects) and is UI-covered (E6).
5. **Scanner camera leg** — CameraX+ML Kit wired and permission-gated (E1); automated QR-decode-from-camera was not scripted this session (manual-entry is the first-class §12.2 path and carries the E2E evidence); a scanner smoke belongs to the §35.6 device matrix.
6. **CI workflow updated but not remotely executed** (unchanged Phase-2 limitation §7.5 — pushing is forbidden this session); its exact commands (`gradle test`, `:app:assembleDebug`, fixture lint) run green locally.

## 9. Files changed (phase scope)

- `settings.gradle.kts`, `gradle/libs.versions.toml`, `build.gradle.kts`, `gradle.properties` (new), `.gitignore` (+local.properties), `.github/workflows/ci.yml`
- `core/security/**` (new: parser, compat gate, vault, signers, biometric gate, Android adapters, 4 test files)
- `core/transport/src/.../RelayTransport.kt` (§9.5 workspace discovery in bridge-open; JsonArray import)
- `core/security` consumer-rules.pro; `features/pairing/**` (new: flow, port, VM, screens, scanner, strings, 2 test files)
- `app/**` (new: build, manifest + debug overlay, resources (themes, backup rules, network config), MainActivity, AppContainer, DefaultPairingPort, FileCredentialStore, theme, instrumented E2E)
- `tools/mockzcode/src/.../MockRelayMain.kt` (new) + `mockRelayServer` task
- `docs/adr/adr-013-phase3-toolchain.md` (new), `docs/adr/phase-3-gate.md` (this file)
- `architectural.md` (permitted bookkeeping only: §43.2 Phase 3 mark + header status line)
