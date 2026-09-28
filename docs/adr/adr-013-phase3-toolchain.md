# ADR-013: Phase 3 toolchain & dependency set (AGP adoption; Android security/UI stack)

| | |
|---|---|
| **Status** | Accepted (2026-09-28, Phase 3) |
| **Context** | §43.2 Phase 3 requires `core:security` (Tink+Keystore sealing, BiometricPrompt), the pairing UI flow (Compose per §10.1/§18), and on-device verification. The repo was JVM-pure through Phase 2 (deliberate: no AGP before the protocol was proven). |
| **Decision** | Adopt the Android Gradle Plugin and add the Phase-3 dependency set below, all previously selected by architectural.md §10.1/§44 (this ADR satisfies the §8.2 mandatory-ADR rule for new `core/*` dependencies and records the AGP step). |
| **Consequences** | Android modules join the build (`core:security`, `features:pairing`, `app`); CI gains an APK compile gate; MockZCode remains test-only and never ships (INV-15). |

## Decisions

1. **AGP 9.3.3** with Gradle 9.7.1, `android.builtInKotlin=false` + `android.newDsl=false` so all modules standardize on **Kotlin 2.4.20** (the catalog version; AGP 9's built-in Kotlin pins a different compiler and cannot consume 2.4-compiled core modules). compileSdk **37** for Android modules (Compose BOM 2026.09.00's ui 1.12.1 requires compileSdk ≥ 37 — the §10.1-anticipated "compileSdk 37 when AGP supports stable" path); **minSdk 26 / targetSdk 36 unchanged** (§10.1, Play policy S23).
2. **`com.google.crypto.tink:tink-android:1.23.0`** — local sealing exactly as §12.4/§26.4 mandate (AES-256-GCM, keyset wrapped by AndroidKeyStore AES-256-GCM, StrongBox when available). No custom crypto anywhere (INV-10); the ZCode proof HMAC remains raw JCA reproduced byte-for-byte.
3. **`androidx.biometric:biometric:1.1.0`** (latest *stable*; 1.2+/1.4 are alpha) — BiometricPrompt + CryptoObject gate (§3.3). Constraint honored: CryptoObject is incompatible with device-credential fallback in 1.1.0, so the challenge prompts BIOMETRIC_STRONG only and the capability report distinguishes `SECURE_LOCK_ONLY` for the optional-enrollment path.
4. **CameraX 1.6.2 + ML Kit barcode-scanning 17.3.0 (bundled)** — the §10.1/§44 scanner choice (bundled works off-GMS). CameraX drifted 1.4.x → 1.6.x stable since the §44 verification date; same artifact family, no API-surface conflict.
5. **Jetpack Compose via BOM 2026.09.00** (material3 1.4.0, per §10.1) + **activity-compose 1.13.0** for the pairing UI (§18.3 states; the §2.6 journey). Navigation-Compose deliberately NOT yet adopted — the Phase 3 flow is single-destination; the navigation host is Phase 6 scope.
6. **Robolectric 4.17 + androidx.test 1.7.0** for JVM-run Compose UI tests (§35.3 per-state UI tests stay CI-runnable without an emulator); instrumented E2E runs against the in-process MockZCode on real/emulated hardware (§35.5), never the production relay (INV-15).
7. **MockZCode host/device launcher** (`tools/mockzcode` `MockRelayMain` + `mockRelayServer` JavaExec) — test tooling only; `androidTestImplementation` so it can never enter a shipped artifact.
8. **AGP DSL note**: `debug` builds add a loopback-only cleartext `networkSecurityConfig` (127.0.0.1/localhost/10.0.2.2) for mock E2E; release keeps platform defaults (production relay is `wss://`).

## Alternatives considered

- **JVM-pure `core:security` with app-side platform adapters**: rejected — §40.1 names core:security "the ONLY module that touches key material"; Keystore/BiometricPrompt adapters belong in the security module.
- **biometric 1.2.0-alpha/1.4.0-alpha**: rejected for a security surface (stable-only policy for crypto/gating code paths).
- **Plain Keystore+AES without Tink**: rejected — explicitly listed as the wrong alternative in §44 ("easy to get wrong").
