package com.zcode.android.core.security

import com.zcode.android.core.security.biometric.BiometricGate
import com.zcode.android.core.security.biometric.BiometricGatePolicy
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3.3 gate policy decision table (§12.4, E7). INV-14: no path silently treats an
 * ungated device as gated; declining consent on a no-lock-screen device FAILS CLOSED.
 */
class BiometricGatePolicyTest {

    @Test
    fun `decision table — full matrix`() {
        
        // Biometrics available → the gate is enforceable, period.
        assertEquals(
            BiometricGatePolicy.Decision.REQUIRE_GATE,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.AVAILABLE, BiometricGatePolicy.Consent.NONE),
        )

        // Secure lock, no usable biometric → offer optional enrollment (never forced).
        assertEquals(
            BiometricGatePolicy.Decision.OFFER_OPT_IN,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.SECURE_LOCK_ONLY, BiometricGatePolicy.Consent.NONE),
        )
        assertEquals(
            BiometricGatePolicy.Decision.OFFER_OPT_IN,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.SECURE_LOCK_ONLY, BiometricGatePolicy.Consent.DECLINED),
        )

        // No secure lock screen → E7 explicit consent, or fail closed.
        assertEquals(
            BiometricGatePolicy.Decision.REQUIRE_E7_CONSENT,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.NO_SECURE_LOCK, BiometricGatePolicy.Consent.NONE),
        )
        assertEquals(
            BiometricGatePolicy.Decision.FAIL_CLOSED,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.NO_SECURE_LOCK, BiometricGatePolicy.Consent.DECLINED),
        )
        assertEquals(
            BiometricGatePolicy.Decision.PROCEED_REDUCED_CONSENT,
            BiometricGatePolicy.decide(BiometricGatePolicy.Capability.NO_SECURE_LOCK, BiometricGatePolicy.Consent.REDUCED_SECURITY_GIVEN),
        )
    }

    @Test
    fun `fake gate port behaves per result mapping (UI-test seam contract)`() {
        val gate = FakeGate(BiometricGate.GateResult.Unlocked)
        assertTrue(runBlocking { gate.challenge("test", null) } is BiometricGate.GateResult.Unlocked)
        val denied = FakeGate(BiometricGate.GateResult.Denied("code=7"))
        assertTrue(runBlocking { denied.challenge("test", null) } is BiometricGate.GateResult.Denied)
    }

    private class FakeGate(private val result: BiometricGate.GateResult) : BiometricGate {
        override fun capability() = BiometricGatePolicy.Capability.AVAILABLE
        override suspend fun challenge(reason: String, crypto: com.zcode.android.core.security.biometric.CryptoChallenge?) = result
    }
}
