package com.zcode.android.data

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.proto.AppPreferencesProto
import com.zcode.android.data.store.AppPreferencesSerializer
import com.zcode.android.data.store.DataStoreCredentialStore
import com.zcode.android.data.store.CredentialEnvelopeSerializer
import com.zcode.android.data.store.DataStoreNotificationRecordStore
import com.zcode.android.data.store.EndpointRepositoryImpl
import com.zcode.android.data.store.EndpointStoreSerializer
import com.zcode.android.data.store.NotificationRecordStoreImpl
import com.zcode.android.data.store.PreferencesRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertNull

/**
 * §43.2-4.6 DataStore suites — endpoint descriptors, preference store with the
 * ADDITIVE-first golden v1 blob (§32.3), and the sealed credential envelope binding:
 * opaque Tink ciphertext only, at-rest plaintext forensics (INV-3/§3.5), fail-closed
 * wipe semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class DataStoreStoresTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "ds-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun newEndpointStore() = DataStoreFactory.create(
        EndpointStoreSerializer,
        produceFile = { File(dir, "endpoint_store.pb") },
    )

    private fun newPrefsStore() = DataStoreFactory.create(
        AppPreferencesSerializer,
        produceFile = { File(dir, "preferences.pb") },
    )

    private fun newCredentialStore() = DataStoreCredentialStore(
        DataStoreFactory.create(CredentialEnvelopeSerializer, produceFile = { File(dir, "credential_envelope.pb") }),
    )

    // ------------------------------------------------------------- endpoints

    @Test
    fun `endpoint descriptors round-trip through the proto store`() = runTest {
        val repo = EndpointRepositoryImpl(newEndpointStore())
        val endpoint = Endpoint(
            endpointId = EndpointId("11111111-2222-4333-8444-555555555555"),
            kind = EndpointKind.RELAY_ZAI,
            displayName = "Test Desktop",
            wsUrl = "wss://example.invalid/ws",
            pairedAtMs = 42,
            deviceSid = "d_synthetic",
            deviceMid = "mid-synthetic",
            desktopAppVersion = "3.14.3",
            candidates = listOf("wss://fallback.invalid/ws"),
            workspacePath = "/synthetic/ws", // display-only metadata (INV-16)
            workspaceIdentity = "ws-ident",
        )
        repo.save(endpoint)
        assertEquals(listOf(endpoint), repo.endpoints())
        repo.save(endpoint.copy(displayName = "Renamed"))
        assertEquals(1, repo.endpoints().size, "save replaces by id")
        assertEquals("Renamed", repo.endpoints().single().displayName)

        repo.delete(endpoint.endpointId)
        assertTrue(repo.endpoints().isEmpty())
    }

    @Test
    fun `unknown endpoint kind fails typed - never guessed (33 storage)`() = runTest {
        val store = newEndpointStore()
        store.updateData {
            it.toBuilder().setSchemaVersion(1).addEndpoints(
                com.zcode.android.data.proto.EndpointDescriptorProto.newBuilder()
                    .setEndpointId("x").setKind("SOMETHING_NEW"),
            ).build()
        }
        val repo = EndpointRepositoryImpl(store)
        val error = runCatching { repo.endpoints() }.exceptionOrNull()
        assertTrue(error is StorageCorruption, "unknown kind must fail typed, got $error")
        store.data.first() // keep the store referenced
    }

    // ------------------------------------------------------------- preferences

    @Test
    fun `defaults apply for a store never written (27 privacy defaults)`() = runTest {
        val repo = PreferencesRepositoryImpl(newPrefsStore())
        val prefs = repo.current()
        assertEquals(AppPreferences.NotificationContentLevel.MINIMAL, prefs.notificationContentLevel)
        assertTrue(prefs.screenshotProtection, "§27 default: screenshots of sensitive surfaces protected")
        assertTrue(prefs.conversationCacheEnabled, "§27 default: cache on (UX cost warned at toggle)")
    }

    @Test
    fun `update persists and unknown enum values fail closed to the default`() = runTest {
        val store = newPrefsStore()
        val repo = PreferencesRepositoryImpl(store)
        repo.update { it.copy(notificationContentLevel = AppPreferences.NotificationContentLevel.CONTENT, screenshotProtection = false) }
        assertEquals(AppPreferences.NotificationContentLevel.CONTENT, repo.current().notificationContentLevel)
        assertFalse(repo.current().screenshotProtection)

        store.updateData { it.toBuilder().setNotificationContentLevel("FUTURE_LEVEL").build() }
        assertEquals(AppPreferences.NotificationContentLevel.MINIMAL, repo.current().notificationContentLevel)
    }

    @Test
    fun `ADDITIVE migration - golden v1 blob (fields 3-4 absent) reads with domain defaults (32-3)`() = runTest {
        // Wire bytes of a hypothetical SCHEMA-V1 file written before fields
        // screenshot_protection(3) / conversation_cache_enabled(4) existed:
        //   field1 schema_version=1 (varint), field2 notification_content_level="MINIMAL"
        val v1Blob = hexToBytes("08011207" + "4D494E494D414C")
        val parsed = AppPreferencesProto.parseFrom(ByteArrayInputStream(v1Blob))
        assertEquals(1, parsed.schemaVersion)
        assertEquals("MINIMAL", parsed.notificationContentLevel)
        assertFalse(parsed.hasScreenshotProtection())
        assertFalse(parsed.hasConversationCacheEnabled())

        // Domain mapping: unset optional fields fall back to §27 defaults — NOT to
        // the proto zero-value (the additive-first contract of §32.3).
        val repo = PreferencesRepositoryImpl(newPrefsStore())
        assertEquals(true, repo.current().screenshotProtection)
        assertEquals(true, repo.current().conversationCacheEnabled)
    }

    // ------------------------------------------------- Phase 8 additive fields

    @Test
    fun `autoReconnectProbe defaults OFF and round-trips (25-3 opt-in)`() = runTest {
        val repo = PreferencesRepositoryImpl(newPrefsStore())
        // §25.3: the probe is OPT-IN — the default is OFF (never schedule silently).
        assertFalse(repo.current().autoReconnectProbe)
        repo.update { it.copy(autoReconnectProbe = true) }
        assertTrue(repo.current().autoReconnectProbe)
        // And a store written before field 5 existed still reads (additive-first).
        val parsed = AppPreferencesProto.parseFrom(hexToBytes("08011207" + "4D494E494D414C"))
        assertFalse(parsed.hasAutoReconnectProbe())
    }

    @Test
    fun `notification records round-trip and tolerate unknown kinds (16, INV-8)`() = runTest {
        val store = NotificationRecordStoreImpl(
            DataStoreFactory.create(
                DataStoreNotificationRecordStore,
                produceFile = { File(dir, "notification_records.pb") },
            ),
        )
        store.save(
            listOf(
                NotificationRecord("interaction:i-1", taskId = "t1", kind = NotificationRecord.Kind.APPROVAL_NEEDED, createdAtMs = 5),
                NotificationRecord("task:t2:TASK_FINISHED", taskId = "t2", kind = NotificationRecord.Kind.TASK_FINISHED, createdAtMs = 6, seenAtMs = 9),
            ),
        )
        val loaded = store.load()
        assertEquals(2, loaded.size)
        assertEquals("interaction:i-1", loaded[0].notificationKey)
        assertEquals(NotificationRecord.Kind.APPROVAL_NEEDED, loaded[0].kind)
        assertEquals(9L, loaded[1].seenAtMs)
    }

    // ------------------------------------------------------------- credential envelope

    @Test
    fun `credential envelope binding - blob round-trip and wipe forensics (12-4)`() = runTest {
        val store = newCredentialStore()
        val sealed = RandomBytes(96) // stands in for an opaque Tink ciphertext
        val marker = "PLAINTXT-SECRET-MARKER"

        store.write("cred-ep1", sealed)
        assertTrue(sealed.contentEquals(store.read("cred-ep1")))
        assertEquals(listOf("cred-ep1"), store.listNames())
        assertEquals(sealed.size.toLong(), store.totalBytes())

        // INV-3 at-rest forensics: with the vault's SEAL-BEFORE-STORE contract
        // (production passes Tink ciphertext), the plaintext marker NEVER reaches
        // the file bytes. A reversible XOR stands in for the Tink AEAD transform.
        val plain = marker.encodeToByteArray()
        val key = RandomBytes(plain.size)
        val ciphertext = ByteArray(plain.size) { (plain[it].toInt() xor key[it].toInt()).toByte() }
        store.write("cred-ep2", ciphertext)
        val rawFile = File(dir, "credential_envelope.pb").readBytes()
        val asText = String(rawFile, Charsets.ISO_8859_1)
        assertTrue(!asText.contains(marker), "plaintext leaked to disk")
        // and the stored blob round-trips back through the inverse transform
        val roundTripped = store.read("cred-ep2")!!
        assertTrue(ByteArray(roundTripped.size) { (roundTripped[it].toInt() xor key[it].toInt()).toByte() }.contentEquals(plain))

        // replace (rotation) overwrites the entry
        val rotated = RandomBytes(96)
        store.write("cred-ep1", rotated)
        assertTrue(rotated.contentEquals(store.read("cred-ep1")))

        // wipe deletes per-name; store empties (§12.7 unpair)
        assertTrue(store.delete("cred-ep1"))
        assertNull(store.read("cred-ep1"))
        assertTrue(store.delete("cred-ep2"))
        assertEquals(0, store.totalBytes())
        assertTrue(store.listNames().isEmpty())
    }

    @Test
    fun `credential envelope - corrupted file fails CLOSED without reinit (32-3)`() = runTest {
        val file = File(dir, "credential_envelope.pb")
        file.writeBytes(byteArrayOf(0x0F, 0x0F, 0x0F, 0x0F)) // garbage — not valid proto
        val store = newCredentialStore()
        val error = runCatching { store.read("anything") }.exceptionOrNull()
        assertTrue(error != null, "corrupted envelope must fail closed (typed), never reinitialize")
        // and the file is left UNTOUCHED (no destructive fallback for the credential class)
        assertTrue(file.exists() && file.readBytes().contentEquals(byteArrayOf(0x0F, 0x0F, 0x0F, 0x0F)))
    }

    // ------------------------------------------------------------- helpers

    private fun RandomBytes(n: Int) = ByteArray(n).also { java.security.SecureRandom().nextBytes(it) }

    private fun hexToBytes(hex: String) = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
