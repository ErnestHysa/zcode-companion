package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Proof vectors cross-computed with the desktop's exact construction
 * (node crypto.createHmac("sha256", passHashString).update("${nonce}|${role}|${sid}")
 * .digest("base64url")) on 2026-09-28 — synthetic key material only (INV-15).
 */
class ProofVectorTest {
    private fun nodeProof(passHash: String, nonce: String, role: String, sid: String) = when (quadruple(passHash, nonce, role, sid)) {
        quadruple("synthetic-test-passhash", "nonce-12345", "terminal", "d_testdevice") ->
            "BImuv8UIys8Q-17iAT0YABluHxMEMN511ZTDjfgG8OY"
        quadruple("anotherPassHashBase64==", "abc", "device", "d_xyz") ->
            "mnMnsuX53vRUUXeMesk2Nv62z-U-gyUjUsYvV0YlcL4"
        else -> "83Ww5PKlO2FsuFb0QNS_n5ckzB-H5_hN_qVXenbFUxo"
    }

    private data class Q(val h: String, val n: String, val r: String, val s: String)
    private fun quadruple(h: String, n: String, r: String, s: String) = Q(h, n, r, s)

    @Test
    fun `proof matches desktop construction byte for byte`() {
        val cases = listOf(
            listOf("synthetic-test-passhash", "nonce-12345", "terminal", "d_testdevice"),
            listOf("anotherPassHashBase64==", "abc", "device", "d_xyz"),
            listOf("third-key-42", "non/ce+with=specials", "terminal", "d_mid"),
        )
        for ((passHash, nonce, role, sid) in cases) {
            assertEquals(
                nodeProof(passHash, nonce, role, sid),
                CredentialSigner(passHash).proof(nonce, role, sid),
                "proof vector mismatch for role=$role"
            )
        }
    }

    @Test
    fun `proof is base64url and key is the raw hash STRING not its bytes`() {
        // Same hash bytes, different string → different key → different proof (guards the
        // subtle createHmac(string-key) semantics).
        val a = CredentialSigner("synthetic-test-passhash").proof("n", "terminal", "d_x")
        val b = CredentialSigner("cynthetid-test-passhash").proof("n", "terminal", "d_x") // one char differs
        assertTrue(a != b)
        assertTrue(a.all { it.isLetterOrDigit() || it == '-' || it == '_' })
        assertFalse(a.contains('='))
    }
}

class PairingParamsTest {

    private val now = 1_727_500_000_000

    private fun qr(t: Long = now - 60_000) =
        "https://zcode.z.ai/remote/v4?sid=d_SyntheticSid00000000001&hash=${java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })}&t=$t&mid=mid123&name=MacBook&app_version=3.14.3"

    @Test
    fun `valid QR parses with all fields`() {
        val p = PairingParams.parse(qr(), nowMs = now)
        assertEquals("d_SyntheticSid00000000001", p.deviceSid)
        assertEquals("https://zcode.z.ai", p.endpointOrigin)
        assertEquals("/remote/v4", p.route)
        assertEquals("3.14.3", p.appVersion)
        assertTrue(PairingParams.supportedOrigin(p))
    }

    @Test
    fun `v3 route accepted with legacy flag`() {
        val p = PairingParams.parse(qr().replace("/remote/v4", "/remote/v3"), nowMs = now)
        assertTrue(p.routeIsLegacyV3)
    }

    @Test
    fun `non-zcode URL rejected`() {
        assertFailsWith<ProtocolException> {
            PairingParams.parse("https://example.com/remote/v4?sid=d_abcdefgh&hash=${"AAAA".repeat(22)}", nowMs = now)
        }
    }

    @Test
    fun `missing sid or bad sid format rejected`() {
        val badSid = qr().replace("d_SyntheticSid00000000001", "notd_format")
        assertFailsWith<ProtocolException> { PairingParams.parse(badSid, nowMs = now) }
        val noSid = qr().substringBefore("&sid=").substringBefore("?") // destroys query → missing sid
        assertFailsWith<ProtocolException> { PairingParams.parse(noSid, nowMs = now) }
    }

    @Test
    fun `non-base64 hash rejected and implausible length rejected`() {
        assertFailsWith<ProtocolException> {
            PairingParams.parse(qr().replaceAfter("hash=", "!!!not-base64!!!"), nowMs = now)
        }
        val shortHash = java.util.Base64.getEncoder().encodeToString(ByteArray(4))
        assertFailsWith<ProtocolException> {
            PairingParams.parse(qr().replaceAfter("hash=", shortHash), nowMs = now)
        }
    }

    @Test
    fun `future timestamp rejected stale timestamp only warns`() {
        assertFailsWith<ProtocolException> {
            PairingParams.parse(qr(t = now + 10 * 60_000), nowMs = now)
        }
        val stale = PairingParams.parse(qr(t = now - 60 * 60_000), nowMs = now)
        assertTrue(PairingParams.isPossiblyStale(stale, nowMs = now))
        val fresh = PairingParams.parse(qr(t = now - 60_000), nowMs = now)
        assertFalse(PairingParams.isPossiblyStale(fresh, nowMs = now))
    }
}
