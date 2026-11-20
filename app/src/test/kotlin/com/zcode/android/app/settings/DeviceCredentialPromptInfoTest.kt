package com.zcode.android.app.settings

import androidx.biometric.BiometricManager
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertTrue

/**
 * G104 rig-catch pin (2026-11-20): the credential confirm's PromptInfo is built
 * WITHOUT a negative button. androidx FORBIDS `setNegativeButtonText` whenever
 * DEVICE_CREDENTIAL is in the allowed set — the shipped helper set one and the
 * FIRST rig tap of the input row FATALed the app
 * ("Negative text must not be set if device credential authentication is allowed",
 * PromptInfo$Builder.build). build() here is the real androidx validation under
 * Robolectric: pre-fix this pin REDs with the exact IllegalArgumentException.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DeviceCredentialPromptInfoTest {

    @Test
    fun `the prompt builds WITHOUT a negative button while DEVICE_CREDENTIAL is allowed`() {
        // The exact build that FATALed the rig — must not throw.
        val info = DeviceCredentialConfirm.promptInfo("Confirm terminal input")
        assertTrue(
            info.negativeButtonText == null || info.negativeButtonText.isNullOrEmpty(),
            "no negative button alongside DEVICE_CREDENTIAL (got: ${info.negativeButtonText})",
        )
        assertTrue(
            (info.allowedAuthenticators and BiometricManager.Authenticators.DEVICE_CREDENTIAL) != 0,
            "the device credential stays in the allowed set (the ADR's fallback)",
        )
    }
}
