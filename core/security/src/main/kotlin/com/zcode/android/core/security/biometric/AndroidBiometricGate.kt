package com.zcode.android.core.security.biometric

import android.app.KeyguardManager
import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import kotlinx.coroutines.suspendCancellableCoroutine
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlin.coroutines.resume

/**
 * BiometricPrompt implementation of [BiometricGate] (§12.4 / Phase 3.3).
 *
 * CryptoObject gate pattern: a dedicated auth-required AndroidKeyStore AES key
 * (`zcode-vault-gate`) performs a Keystore crypto operation ONLY inside a successful
 * strong-biometric session. The first challenge encrypts a fresh random token (stores
 * IV+ciphertext); every later challenge decrypts it — an `Unlocked` verdict therefore
 * requires BOTH the biometric session AND the Keystore-bound crypto op to complete.
 * Key invalidation (new biometric enrollment) surfaces as a typed denial — never a
 * silent fallback (INV-14).
 *
 * API note: CryptoObject is incompatible with device-credential fallback in
 * androidx.biometric 1.1.0 — the challenge prompts for BIOMETRIC_STRONG only; the
 * capability() report distinguishes SECURE_LOCK_ONLY (device-credential custody is
 * still available for optional enrollment) from full biometric availability.
 */
class AndroidBiometricGate(
    private val activity: FragmentActivity,
    private val gateKeyAlias: String = GATE_KEY_ALIAS,
) : BiometricGate {

    override fun capability(): BiometricGatePolicy.Capability {
        val can = BiometricManager.from(activity)
            .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG)
        val secureLock = activity.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        return when {
            can == BiometricManager.BIOMETRIC_SUCCESS -> BiometricGatePolicy.Capability.AVAILABLE
            secureLock -> BiometricGatePolicy.Capability.SECURE_LOCK_ONLY
            else -> BiometricGatePolicy.Capability.NO_SECURE_LOCK
        }
    }

    /**
     * Create the auth-bound gate key (idempotent). Called during SecuritySetup when the
     * policy decided a gate is enforceable; the token mint happens lazily inside the
     * first [challenge] (it is itself an authenticated operation).
     */
    fun provisionGateKey(): Boolean = runCatching {
        val ks = keyStore()
        if (!ks.containsAlias(gateKeyAlias)) {
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, AndroidKeystoreKeyWrap.ANDROID_KEYSTORE)
            val builder = KeyGenParameterSpec.Builder(
                gateKeyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(true)
            if (Build.VERSION.SDK_INT >= 30) {
                // Crypto-bound validity: every op requires a fresh authenticated session.
                builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
            }
            kg.init(builder.build())
            kg.generateKey()
        }
        true
    }.getOrDefault(false)

    /** Remove gate state (unpair wipe — §12.7). Returns true when a key existed. */
    fun wipeGate(): Boolean {
        val removedKey = runCatching {
            val ks = keyStore()
            if (ks.containsAlias(gateKeyAlias)) {
                ks.deleteEntry(gateKeyAlias)
                true
            } else false
        }.getOrDefault(false)
        prefs().edit().clear().apply()
        return removedKey
    }

    override suspend fun challenge(reason: String, crypto: CryptoChallenge?): BiometricGate.GateResult {
        val stored = storedToken()
        val cipher = runCatching {
            Cipher.getInstance(TRANSFORM).apply {
                if (stored == null) {
                    init(Cipher.ENCRYPT_MODE, key())
                } else {
                    init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, stored.iv))
                }
            }
        }.getOrElse { return BiometricGate.GateResult.Unavailable(capability()) }

        val promptResult = prompt(reason, BiometricPrompt.CryptoObject(cipher))
        if (promptResult !is PromptOutcome.Success) {
            return when (promptResult) {
                is PromptOutcome.Cancelled -> BiometricGate.GateResult.Cancelled
                is PromptOutcome.Error -> BiometricGate.GateResult.Denied("code=${promptResult.code}")
                PromptOutcome.None -> BiometricGate.GateResult.Unavailable(capability())
            }
        }
        return runCatching {
            if (stored == null) {
                val token = ByteArray(TOKEN_LEN).also { SecureRandom().nextBytes(it) }
                val ct = cipher.doFinal(token)
                prefs().edit()
                    .putString(PREF_IV, b64(cipher.iv))
                    .putString(PREF_CT, b64(ct))
                    .apply()
            } else {
                cipher.doFinal(stored.ct) // throws if the key was invalidated → denied
            }
            BiometricGate.GateResult.Unlocked
        }.getOrElse { BiometricGate.GateResult.Denied("gate-crypto-failed") }
    }

    // ------------------------------------------------------------------ internals

    private sealed class PromptOutcome {
        data object Success : PromptOutcome()
        data object Cancelled : PromptOutcome()
        data class Error(val code: Int) : PromptOutcome()
        data object None : PromptOutcome()
    }

    private suspend fun prompt(reason: String, cryptoObject: BiometricPrompt.CryptoObject): PromptOutcome =
        suspendCancellableCoroutine { cont ->
            val callback = object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    if (cont.isActive) cont.resume(PromptOutcome.Success)
                }

                override fun onAuthenticationError(code: Int, errString: CharSequence) {
                    if (!cont.isActive) return
                    val cancelled = code == BiometricPrompt.ERROR_USER_CANCELED ||
                        code == BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    cont.resume(if (cancelled) PromptOutcome.Cancelled else PromptOutcome.Error(code))
                }
            }
            val info = BiometricPrompt.PromptInfo.Builder()
                .setTitle(reason)
                .setNegativeButtonText(activity.getString(android.R.string.cancel))
                .build()
            BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
                .authenticate(info, cryptoObject)
        }

    private data class StoredToken(val iv: ByteArray, val ct: ByteArray)

    private fun storedToken(): StoredToken? {
        val iv = prefs().getString(PREF_IV, null)?.let { unb64(it) } ?: return null
        val ct = prefs().getString(PREF_CT, null)?.let { unb64(it) } ?: return null
        return StoredToken(iv, ct)
    }

    private fun prefs() = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun key(): SecretKey {
        val ks = keyStore()
        return ks.getKey(gateKeyAlias, null) as SecretKey
    }

    private fun keyStore(): KeyStore =
        KeyStore.getInstance(AndroidKeystoreKeyWrap.ANDROID_KEYSTORE).apply { load(null) }

    private fun b64(bytes: ByteArray) = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    private fun unb64(s: String) = android.util.Base64.decode(s, android.util.Base64.NO_WRAP)

    companion object {
        const val GATE_KEY_ALIAS = "zcode-vault-gate"
        private const val PREFS = "zcode-gate"
        private const val PREF_IV = "gate.iv"
        private const val PREF_CT = "gate.ct"
        private const val TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val TOKEN_LEN = 32
    }
}
