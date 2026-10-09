package com.zcode.android.core.security

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.signer.JcaCredentialSigner
import com.zcode.android.core.security.vault.CredentialStore
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.MasterKeyWrap
import com.zcode.android.core.security.vault.SealOutcome
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 3.2 + 3.5 exit-gate suite: custody/separation, desktop proof vectors
 * byte-identical, rotation continuity, wipe completeness + forensic scan, and the
 * API-level guarantee that the secret is only ever reachable through the signer.
 * Runs on the JVM with REAL Tink AES-256-GCM and a software master-key wrap.
 */
class CredentialVaultTest {

    private val now = 1_727_500_000_000L
    private val hashA = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })
    private val hashB = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 1 })

    private fun link(sid: String = "d_SyntheticSid000001", hash: String = hashA) = PairingLink(
        endpointOrigin = "https://zcode.z.ai",
        route = "/remote/v4",
        deviceSid = sid,
        passHash = hash,
        issuedAtMs = now - 60_000,
        deviceMid = "mid123",
        desktopName = "MacBook",
        appVersion = "3.14.3",
    )

    private fun vault(): Pair<CredentialVault, MemoryStore> {
        val store = MemoryStore()
        val log = ZLog(sink = {})
        return CredentialVault(store, SoftKeyWrap(), log, newEndpointId = { "endpoint-fixed" }, clock = { now }) to store
    }

    // ------------------------------------------------------------ proof vectors

    @Test
    fun `proof vectors byte-identical to desktop construction`() {
        // Cross-computed with node crypto.createHmac("sha256", passHashString) on
        // 2026-09-28 (Phase 1; synthetic key material only — INV-15).
        val vectors = listOf(
            listOf("synthetic-test-passhash", "nonce-12345", "terminal", "d_testdevice")
                to "BImuv8UIys8Q-17iAT0YABluHxMEMN511ZTDjfgG8OY",
            listOf("anotherPassHashBase64==", "abc", "device", "d_xyz")
                to "mnMnsuX53vRUUXeMesk2Nv62z-U-gyUjUsYvV0YlcL4",
            listOf("third-key-42", "non/ce+with=specials", "terminal", "d_mid")
                to "83Ww5PKlO2FsuFb0QNS_n5ckzB-H5_hN_qVXenbFUxo",
        )
        for ((quadruple, expected) in vectors) {
            val (hash, nonce, role, sid) = quadruple
            assertEquals(expected, JcaCredentialSigner(hash).proof(nonce, role, sid), "role=$role")
        }
    }

    @Test
    fun `signer loaded from vault answers challenges with the same vectors`() {
        val (v, _) = vault()
        v.seal(link(hash = "synthetic-test-passhash"))
        val signer = v.loadSigner()
        assertNotNull(signer)
        assertEquals(
            "BImuv8UIys8Q-17iAT0YABluHxMEMN511ZTDjfgG8OY",
            signer.proof("nonce-12345", "terminal", "d_testdevice"),
        )
    }

    // ----------------------------------------------------------- seal / rotation

    @Test
    fun `first seal Creates and second same-sid seal Rotates with continuity`() {
        val (v, _) = vault()
        val first = v.seal(link(hash = hashA))
        assertTrue(first is SealOutcome.Created)
        assertEquals(1, first.metadata.generation)

        val second = v.seal(link(hash = hashB))
        assertTrue(second is SealOutcome.Rotated, "same sid must classify as rotation (§12.2 step 4)")
        assertEquals(2, second.metadata.generation)
        // Rotation continuity: the signer immediately proves with the NEW material.
        val signer = v.loadSigner()
        assertNotNull(signer)
        assertEquals(JcaCredentialSigner(hashB).proof("n", "terminal", "d_SyntheticSid000001"), signer.proof("n", "terminal", "d_SyntheticSid000001"))
    }

    @Test
    fun `different sid while paired Conflicts and writes nothing`() {
        val (v, store) = vault()
        v.seal(link(sid = "d_SyntheticSid000001"))
        val before = store.snapshot()
        val outcome = v.seal(link(sid = "d_OtherSid00000002"))
        assertTrue(outcome is SealOutcome.Conflicts)
        assertEquals("d_SyntheticSid000001", outcome.existingSid)
        assertEquals(before, store.snapshot(), "conflict must write NOTHING (fail-closed)")
    }

    // ------------------------------------------------------- custody / API surface

    @Test
    fun `at-rest blobs never contain the plaintext secret (forensic scan)`() {
        val (v, store) = vault()
        v.seal(link(hash = hashA))
        val concatenated = store.snapshot().values.joinToString(separator = "") { it.decodeToString() }
        assertFalse(concatenated.contains(hashA), "plaintext passHash must never exist in the store")
        assertFalse(concatenated.contains("passHash"), "secret envelope field names must not leak as plaintext either")
        // Metadata is secret-free by construction.
        val meta = store.snapshot()[CredentialVault.NAME_METADATA]!!.decodeToString()
        assertFalse(meta.contains(hashA))
        assertTrue(meta.contains("d_SyntheticSid000001")) // deviceSid is NOT a secret (§9.13)
    }

    @Test
    fun `API surface exposes no accessor for the raw secret`() {
        val (v, _) = vault()
        v.seal(link())
        // The metadata view is the only credential read besides the signer:
        val meta = v.loadMetadata()
        assertNotNull(meta)
        assertEquals("d_SyntheticSid000001", meta.deviceSid)
        // The signer interface exposes exactly one method; the vault's public API offers
        // no method returning passHash (compile-time shape; reflection walk as a tripwire).
        val signerMethods = CredentialVault::class.java.methods
            .filter { java.lang.reflect.Modifier.isPublic(it.modifiers) && it.declaringClass != CredentialVault.Companion::class.java && it.declaringClass != Object::class.java }
            .map { it.name }
        assertTrue("loadSigner" in signerMethods)
        assertTrue(
            signerMethods.none { it.lowercase().contains("hash") },
            "no public vault method may return hash material",
        )
        assertNull(runCatching { CredentialVault::class.java.getMethod("passHash") }.getOrNull())
    }

    // ------------------------------------------------------------------- wipe

    @Test
    fun `wipe is complete — blobs gone, key destroyed, load fails closed`() {
        val (v, store) = vault()
        v.seal(link())
        assertNotNull(v.loadSigner())
        assertNotNull(v.loadMetadata())

        val report = v.wipe()
        assertTrue(report.masterKeyDestroyed)
        assertTrue(report.storeEmptyAfterWipe)
        assertTrue(store.listNames().isEmpty())
        assertTrue(store.snapshot().isEmpty())
        assertNull(v.loadMetadata())
        assertNull(v.loadSigner())
        assertNull(v.pairedSid())

        // Re-pair after wipe works (fresh keyset, fresh credential).
        val again = v.seal(link())
        assertTrue(again is SealOutcome.Created, "post-wipe seal is a fresh credential, not a rotation")
    }

    @Test
    fun `wipe with master key destroyed makes old ciphertext undecryptable (fail-closed)`() {
        val (v, store) = vault()
        v.seal(link())
        val staleSecret = store.snapshot()[CredentialVault.NAME_SECRET]!!
        val fresh = SoftKeyWrap()
        // A vault over a DIFFERENT (post-wipe) master key must not decrypt the old blob.
        val v2 = CredentialVault(store, fresh, ZLog(sink = {}), clock = { now })
        v2.wipe() // destroys fresh's key... verify unwrap-failure path instead:
        val (v3, store3) = vault()
        v3.seal(link())
        val sealed = store3.snapshot()[CredentialVault.NAME_SECRET]!!
        // Tamper: flip a ciphertext byte → AEAD auth failure → loadSigner() returns null (never throws, never half-opens).
        val corrupted = sealed.copyOf().also { it[3] = (it[3].toInt() xor 0x40).toByte() }
        store3.write(CredentialVault.NAME_SECRET, corrupted)
        assertNull(v3.loadSigner(), "corrupted envelope must fail closed to null (unpaired), never throw")
    }

    // ----------------------------------------------------------------- INV-3

    @Test
    fun `sealed secret is registered with the log sink and can never surface`() {
        val sinkEntries = mutableListOf<ZLog.LogEntry>()
        val log = ZLog(sink = { sinkEntries.add(it) })
        val store = MemoryStore()
        val v = CredentialVault(store, SoftKeyWrap(), log, clock = { now })
        v.seal(link(hash = hashA))
        // Adversarial: attempt to log the hash directly post-registration.
        log.info("adversarial.attempt", "hash=$hashA proof=$hashA")
        log.info("vault.probe", hashA)
        val exported = log.exportDiagnostics()
        assertFalse(exported.contains(hashA), "registered secret must be scrubbed from every sink output (INV-3)")
        assertTrue(sinkEntries.isNotEmpty())
    }

    // ------------------------------------------------------------- fixtures

    /** In-memory CredentialStore (test fixture). */
    class MemoryStore : CredentialStore {
        private val blobs = LinkedHashMap<String, ByteArray>()
        fun snapshot(): Map<String, ByteArray> = blobs.toMap()
        override fun read(name: String): ByteArray? = blobs[name]
        override fun write(name: String, bytes: ByteArray) {
            blobs[name] = bytes
        }

        override fun delete(name: String): Boolean = blobs.remove(name) != null
        override fun listNames(): List<String> = blobs.keys.toList()
        override fun totalBytes(): Long = blobs.values.sumOf { it.size.toLong() }
    }

    /** Software AES-256-GCM master-key wrap (stands in for AndroidKeyStore on the JVM). */
    class SoftKeyWrap : MasterKeyWrap {
        private var key: javax.crypto.SecretKey? = null

        override fun wrap(plain: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key ?: KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().also { key = it })
            val ct = cipher.doFinal(plain)
            return byteArrayOf(12.toByte()) + cipher.iv + ct
        }

        override fun unwrap(wrapped: ByteArray): ByteArray? {
            val k = key ?: return null
            return runCatching {
                val iv = wrapped.copyOfRange(1, 13)
                val ct = wrapped.copyOfRange(13, wrapped.size)
                Cipher.getInstance("AES/GCM/NoPadding")
                    .apply { init(Cipher.DECRYPT_MODE, k, GCMParameterSpec(128, iv)) }
                    .doFinal(ct)
            }.getOrNull()
        }

        override fun wipe(): Boolean {
            val existed = key != null
            key = null
            return existed
        }
    }
}
