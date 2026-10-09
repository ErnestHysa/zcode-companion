package com.zcode.android.core.security.vault

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore implementation of [MasterKeyWrap] (§12.4: "Tink AES-GCM, key wrapped
 * by Android Keystore AES key (StrongBox if available)").
 *
 * The master key is AES-256-GCM, generated INSIDE AndroidKeyStore (never exportable).
 * When [biometricBound] is set, the key requires recent user authentication
 * (`setUserAuthenticationRequired(true)` + 60 s validity window, §12.4 "per app-start
 * first unlock") — unwrap then throws [android.security.keystore.UserNotAuthenticatedException]
 * until the user passes BiometricPrompt; the pairing flow surfaces that as the gate
 * step (E7/§3.3). Default: not biometric-bound (optional enrollment per §2.6).
 */
class AndroidKeystoreKeyWrap(
    private val alias: String = DEFAULT_ALIAS,
    private val strongBoxPreferred: Boolean = true,
    private val biometricBound: Boolean = false,
) : MasterKeyWrap {

    private fun keyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun existingKey(): SecretKey? =
        keyStore().getKey(alias, null) as? SecretKey

    private fun generateKey(): SecretKey {
        val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val builder = KeyGenParameterSpec.Builder(
            alias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
        if (biometricBound) {
            builder.setUserAuthenticationRequired(true)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                builder.setUserAuthenticationParameters(
                    60,
                    KeyProperties.AUTH_DEVICE_CREDENTIAL or KeyProperties.AUTH_BIOMETRIC_STRONG,
                )
            } else {
                builder.setUserAuthenticationValidityDurationSeconds(60)
            }
        }
        if (strongBoxPreferred) {
            try {
                builder.setIsStrongBoxBacked(true)
                kg.init(builder.build())
                return kg.generateKey()
            } catch (e: Exception) {
                // StrongBox unavailable on this device → software keystore (§12.4 fallback).
                builder.setIsStrongBoxBacked(false)
            }
        }
        kg.init(builder.build())
        return kg.generateKey()
    }

    override fun wrap(plain: ByteArray): ByteArray {
        val key = existingKey() ?: generateKey()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ct = cipher.doFinal(plain)
        return byteArrayOf(IV_LEN_BYTES.toByte()) + cipher.iv + ct
    }

    override fun unwrap(wrapped: ByteArray): ByteArray? = runCatching {
        require(wrapped.size > IV_LEN_BYTES) { "wrapped blob too short" }
        val iv = wrapped.copyOfRange(1, 1 + IV_LEN_BYTES)
        val ct = wrapped.copyOfRange(1 + IV_LEN_BYTES, wrapped.size)
        val key = existingKey() ?: return null
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        cipher.doFinal(ct)
    }.getOrNull()

    override fun wipe(): Boolean {
        val ks = keyStore()
        return if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
            true
        } else {
            false
        }
    }

    companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val DEFAULT_ALIAS = "zcode-vault-master"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        /** GCM IV length this implementation emits (12 bytes, standard). */
        const val IV_LEN_BYTES = 12
        private const val GCM_TAG_BITS = 128
    }
}
