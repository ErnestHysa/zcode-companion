package com.zcode.android.core.security

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.vault.CredentialStore
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.DirectEndpointMetadata
import com.zcode.android.core.security.vault.MasterKeyWrap
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
 * Phase 10 — direct-endpoint token custody (§12.4 "Server token" row): Tink-sealed
 * at rest (forensic scan), capability-only access (no raw-token method), per-endpoint
 * delete, and full-wipe coverage. Same fixture family as [CredentialVaultTest]
 * (real Tink AES-256-GCM + software master-key wrap; synthetic tokens only — INV-15).
 */
class DirectTokenVaultTest {

    private val now = 1_727_500_000_000L
    private val token = "direct-smoke-token-DO-NOT-USE-IN-PROD"

    private fun vault(): Pair<CredentialVault, MemoryStore> {
        val store = MemoryStore()
        return CredentialVault(store, SoftKeyWrap(), ZLog(sink = {}), newEndpointId = { "endpoint-fixed" }, clock = { now }) to store
    }

    private fun meta(id: String = "direct-ep-1") = DirectEndpointMetadata(
        endpointId = id,
        displayName = "NAS direct",
        wsUrl = "ws://nas:3030/ws",
        serverId = "srv-1",
        serverVersion = "3.14.3",
        addedAtMs = now,
    )

    @Test
    fun `seal then load source returns the exact token and metadata stays secret free`() {
        val (v, store) = vault()
        v.sealDirectToken(token, meta())
        val source = v.loadDirectTokenSource("direct-ep-1")
        assertNotNull(source)
        assertEquals(token, source())
        val loaded = v.loadDirectMetadata("direct-ep-1")
        assertNotNull(loaded)
        assertEquals("NAS direct", loaded.displayName)
        assertEquals("ws://nas:3030/ws", loaded.wsUrl)
        // §12.4: the metadata blob never contains the token (INV-3 at-rest forensics).
        val rawMeta = store.read(CredentialVault.directMetaName("direct-ep-1"))!!.decodeToString()
        assertFalse(rawMeta.contains(token), "token leaked into the metadata blob")
    }

    @Test
    fun `token is ciphertext at rest (INV-3)`() {
        val (v, store) = vault()
        v.sealDirectToken(token, meta())
        val raw = store.read(CredentialVault.directSecretName("direct-ep-1"))!!
        assertFalse(raw.decodeToString().contains(token), "plaintext token in the at-rest blob")
        // Tink AES-GCM envelopes never start with the plaintext.
        assertFalse(raw.take(8).toByteArray().contentEquals(token.take(8).encodeToByteArray()))
    }

    @Test
    fun `re-seal rotates in place (same endpoint)`() {
        val (v, _) = vault()
        v.sealDirectToken(token, meta())
        v.sealDirectToken("rotated-token-2", meta().copy(serverVersion = "3.15.0"))
        assertEquals("rotated-token-2", v.loadDirectTokenSource("direct-ep-1")!!())
        assertEquals("3.15.0", v.loadDirectMetadata("direct-ep-1")?.serverVersion)
        assertEquals(1, v.listDirectEndpoints().size)
    }

    @Test
    fun `per endpoint delete removes exactly that endpoint`() {
        val (v, store) = vault()
        v.sealDirectToken(token, meta("direct-ep-1"))
        v.sealDirectToken("other-token", meta("direct-ep-2"))
        assertTrue(v.deleteDirectToken("direct-ep-1"))
        assertNull(v.loadDirectTokenSource("direct-ep-1"))
        assertEquals("other-token", v.loadDirectTokenSource("direct-ep-2")!!())
        assertEquals(listOf("direct-ep-2"), v.listDirectEndpoints().map { it.endpointId })
        assertFalse(store.listNames().contains(CredentialVault.directSecretName("direct-ep-1")))
    }

    @Test
    fun `full wipe destroys direct tokens too per unpair rules`() {
        val (v, store) = vault()
        v.sealDirectToken(token, meta())
        val report = v.wipe()
        assertTrue(report.storeEmptyAfterWipe)
        assertTrue(store.listNames().isEmpty())
        assertNull(v.loadDirectTokenSource("direct-ep-1"))
    }

    @Test
    fun `corrupt envelope fails closed (no token, no crash)`() {
        val (v, store) = vault()
        v.sealDirectToken(token, meta())
        store.write(CredentialVault.directSecretName("direct-ep-1"), "garbage".encodeToByteArray())
        assertNull(v.loadDirectTokenSource("direct-ep-1"))
    }

    @Test
    fun `sealing registers the token with the log scrubber (INV-3)`() {
        val sinkEntries = ArrayList<String>()
        val v = CredentialVault(MemoryStore(), SoftKeyWrap(), ZLog(minLevel = ZLog.LogLevel.DEBUG, sink = { e ->
            sinkEntries += (e.details ?: "") + e.context.values.joinToString(" ")
        }), newEndpointId = { "endpoint-fixed" }, clock = { now })
        v.sealDirectToken(token, meta())
        v.loadDirectTokenSource("direct-ep-1")
        // Every log line that could carry the token is scrubbed (registered secret).
        assertTrue(sinkEntries.none { it.contains(token) }, "token reached a log sink")
    }
}

/** Shared test doubles (same shape as CredentialVaultTest's private fixtures). */
internal class MemoryStore : CredentialStore {
    private val blobs = HashMap<String, ByteArray>()
    override fun read(name: String): ByteArray? = blobs[name]
    override fun write(name: String, bytes: ByteArray) {
        blobs[name] = bytes
    }
    override fun delete(name: String): Boolean = blobs.remove(name) != null
    override fun listNames(): List<String> = blobs.keys.toList()
    override fun totalBytes(): Long = blobs.values.sumOf { it.size.toLong() }
}

internal class SoftKeyWrap : MasterKeyWrap {
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    override fun wrap(plain: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        val iv = c.iv
        val ct = c.doFinal(plain)
        return iv + ct
    }
    override fun unwrap(wrapped: ByteArray): ByteArray? = runCatching {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, wrapped, 0, 12))
        c.doFinal(wrapped, 12, wrapped.size - 12)
    }.getOrNull()
    override fun wipe(): Boolean = false
}
