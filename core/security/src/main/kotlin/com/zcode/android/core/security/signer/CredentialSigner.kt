package com.zcode.android.core.security.signer

/**
 * Proof capability (§12.4/§26.3): answers a relay challenge WITHOUT exposing material.
 * Implementations live in core:security; the transport consumes proofs via its own
 * identical SAM interface — the app-layer adapter is one lambda (custody → capability
 * → transport, ADR-004). INV-3/INV-10: no implementation may retain, log, or expose
 * the key material.
 */
fun interface CredentialSigner {
    /** proof = base64url(HMAC-SHA256(key = UTF8(passHashString), "${nonce}|${role}|${sid}")) — §7.6 exact. */
    fun proof(nonce: String, role: String, sid: String): String
}

/**
 * JCA implementation of the desktop-verified construction (§7.6, INV-10 — reproduce
 * EXACTLY, never modify): Node `crypto.createHmac("sha256", <passHashString>)` semantics
 * — the HMAC key is the UTF-8 bytes of the base64 hash STRING, not the decoded digest
 * bytes (verified by Phase 1 fixture vectors + on-device AndroidKeyStore test).
 */
class JcaCredentialSigner(private val passHash: String) : CredentialSigner {
    override fun proof(nonce: String, role: String, sid: String): String {
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(passHash.encodeToByteArray(), "HmacSHA256"))
        val msg = "$nonce|$role|$sid"
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(msg.encodeToByteArray()))
    }
}
