package com.zcode.android.app.settings

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * ADR-022 gate 3 (G104 executed): the device-credential confirm that gates the
 * terminal INPUT opt-in. BiometricPrompt with the DEVICE_CREDENTIAL fallback —
 * the §12.7 unpair-flow precedent — so a PIN-only device can still confirm:
 *  - API 30+: `BIOMETRIC_WEAK or DEVICE_CREDENTIAL` in one prompt;
 *  - API 28-29: DEVICE_CREDENTIAL-only prompt (the androidx split — combining
 *    credential with biometric throws pre-31);
 *  - API <28 or no FragmentActivity host: FAIL CLOSED (returns false, INV-14:
 *    an unconfirmable device is never silently treated as confirmed).
 * A cancelled/denied prompt also returns false — the flip never happens.
 */
object DeviceCredentialConfirm {

    suspend fun confirm(activity: FragmentActivity, title: String): Boolean {
        if (Build.VERSION.SDK_INT < 28) return false // fail closed (INV-14)
        return suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    // cancelled/timeout/negative → the gate stays OFF (never a silent arm)
                    cont.resume(false)
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setNegativeButtonText(activity.getString(android.R.string.cancel))
                .setAllowedAuthenticators(allowedAuthenticators())
                .build()
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(info)
        }
    }

    private fun allowedAuthenticators(): Int =
        if (Build.VERSION.SDK_INT >= 30) {
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        } else {
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        }

    /** Diagnostic-only: does this device offer ANY confirmable path? */
    fun canConfirm(context: Context): Boolean =
        Build.VERSION.SDK_INT >= 28 &&
            (context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true)
}
