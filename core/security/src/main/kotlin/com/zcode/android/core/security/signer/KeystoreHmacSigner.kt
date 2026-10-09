package com.zcode.android.core.security.signer

import android.security.keystore.KeyProtection
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Keystore-resident HMAC signer — §12.4 row 2 / §26.4 row 3, the "Keystore-HMAC import
 * variant" required BEHIND A FLAG by Phase 3.2. The passHash STRING bytes are imported
 * into AndroidKeyStore as a non-exportable HmacSHA256 key; proofs are computed inside
 * the keystore so the material is never again held in app memory after import.
 *
 * Import semantics are the Phase-1-verified ones (docs/protocol/relay-control-plane.md
 * §4): the HMAC key is the UTF-8 bytes of the base64 hash STRING (Node
 * `createHmac("sha256", <string>)`), and AndroidKeyStore REQUIRES KeyProtection
 * parameters for symmetric-key import.
 *
 * INV-10: the proof construction is byte-identical to [JcaCredentialSigner] (tested on
 * device, Phase 1.7 + Phase 3 E2E); nothing about the verified algorithm is changed.
 */
class KeystoreHmacSigner(
    private val alias: String,
) : CredentialSigner {

    /** Import (or re-import) this passHash string at seal/rotate time. */
    fun importSecret(passHash: String) {
        val raw = passHash.encodeToByteArray()
        val secret = SecretKeySpec(raw, "HmacSHA256")
        val protection = KeyProtection.Builder(
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        )
            .setDigests(KeyProperties.DIGEST_SHA256)
            .build()
        keyStore().setEntry(alias, KeyStore.SecretKeyEntry(secret), protection)
    }

    override fun proof(nonce: String, role: String, sid: String): String {
        val key = keyStore().getKey(alias, null) as? SecretKey
            ?: throw IllegalStateException("keystore HMAC key missing — import before use")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(key)
        val msg = "$nonce|$role|$sid"
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg.encodeToByteArray()))
    }

    /** Wipe the imported key (unpair/rotation — §12.7). Returns true when it existed. */
    fun wipeImported(): Boolean {
        val ks = keyStore()
        return if (ks.containsAlias(alias)) {
            ks.deleteEntry(alias)
            true
        } else {
            false
        }
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance(AndroidKeystoreAlias.STORE).apply { load(null) }
}

internal object AndroidKeystoreAlias {
    const val STORE = "AndroidKeyStore"
}
