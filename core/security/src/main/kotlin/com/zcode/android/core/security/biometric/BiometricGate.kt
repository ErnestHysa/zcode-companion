package com.zcode.android.core.security.biometric

/**
 * Biometric gating — §12.4 + Phase 3.3.
 *
 * Policy (pure, JVM-tested): decides what the pairing flow's SecuritySetup step must
 * present, per device capability:
 *  - biometrics available (hardware + enrolled)         → REQUIRE_GATE (CryptoObject
 *    challenge on every reveal/rotate/unpair; optional per-start unlock);
 *  - secure lock screen, no usable biometrics           → OFFER_OPT_IN (offer device-
 *    credential gate enrollment — never forced);
 *  - NO secure lock screen                              → REQUIRE_E7_CONSENT (§2.6 E7:
 *    explicit reduced-security consent — NEVER a silent fallback; failing consent
 *    fails closed).
 *
 * INV-14: an ungated/unconsented device can never be silently treated as gated.
 */
object BiometricGatePolicy {

    enum class Capability {
        /** Hardware present + something enrolled → gate enforcible. */
        AVAILABLE,
        /** Secure lock screen exists but no usable biometric → device-credential gate only. */
        SECURE_LOCK_ONLY,
        /** No secure lock screen at all → E7 territory. */
        NO_SECURE_LOCK,
    }

    enum class Decision {
        /** Enforce the biometric gate (CryptoObject challenge) on protected operations. */
        REQUIRE_GATE,
        /** Offer optional gate enrollment (device credential); proceeding ungated is allowed. */
        OFFER_OPT_IN,
        /** Show the reduced-security consent screen (E7) — must be explicitly accepted. */
        REQUIRE_E7_CONSENT,
        /** Consent was explicitly recorded — proceed in reduced-security mode, labeled. */
        PROCEED_REDUCED_CONSENT,
        /** Fail closed: the caller refused consent / cancelled — pairing cannot proceed ungated silently. */
        FAIL_CLOSED,
    }

    fun decide(capability: Capability, consent: Consent): Decision = when (capability) {
        Capability.AVAILABLE -> Decision.REQUIRE_GATE
        Capability.SECURE_LOCK_ONLY -> when (consent) {
            Consent.NONE, Consent.DECLINED -> Decision.OFFER_OPT_IN
            Consent.REDUCED_SECURITY_GIVEN -> Decision.PROCEED_REDUCED_CONSENT
        }
        Capability.NO_SECURE_LOCK -> when (consent) {
            Consent.NONE -> Decision.REQUIRE_E7_CONSENT
            Consent.DECLINED -> Decision.FAIL_CLOSED
            Consent.REDUCED_SECURITY_GIVEN -> Decision.PROCEED_REDUCED_CONSENT
        }
    }

    enum class Consent { NONE, REDUCED_SECURITY_GIVEN, DECLINED }
}

/**
 * Gate port — platform implementation prompts the user (BiometricPrompt + CryptoObject
 * on device) and reports the outcome. Pure fakes back UI tests (§35.3).
 */
interface BiometricGate {
    fun capability(): BiometricGatePolicy.Capability

    /**
     * Run one gate challenge. [CryptoChallenge] carries the platform crypto object
     * binding (null = device-credential-only challenge where CryptoObject is
     * unavailable — recorded in the result for honest labeling).
     */
    suspend fun challenge(reason: String, crypto: CryptoChallenge?): GateResult

    sealed class GateResult {
        data object Unlocked : GateResult()
        data object Cancelled : GateResult()
        data class Denied(val code: String) : GateResult()
        data class Unavailable(val capability: BiometricGatePolicy.Capability) : GateResult()
    }
}

/** Platform-provided crypto binding for a gate challenge (CryptoObject payload). */
class CryptoChallenge(val platformObject: Any?)
