package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.domain.model.AppPreferences
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G43-P24 (vc52 Phase 13, rule 13): the update-plane prefs round-trip through
 * the REAL serializer — the skip-version marker and the What's-new record.
 * Defaults: nothing skipped (null), nothing recorded (nulls), nothing pending
 * (shown=true — a fresh install never owes a What's-new sheet).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesUpdatePlaneRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "upd-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repo(): PreferencesRepositoryImpl {
        val instance = File(dir, "i-${System.nanoTime()}").apply { mkdirs() }
        return PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { File(instance, "preferences.pb") }),
        )
    }

    @Test
    fun `defaults - nothing skipped nothing recorded nothing pending`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(notifySound = true) }
        val p = preferences.current()
        assertNull(p.skippedUpdateVersionCode)
        assertNull(p.whatsNewVersionCode)
        assertNull(p.whatsNewVersionName)
        assertNull(p.whatsNewNotes)
        assertTrue(p.whatsNewShown)
    }

    @Test
    fun `skip-version persists and clears`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(skippedUpdateVersionCode = 52L) }
        assertEquals(52L, preferences.current().skippedUpdateVersionCode)
        preferences.update { it.copy(skippedUpdateVersionCode = null) }
        assertNull(preferences.current().skippedUpdateVersionCode)
    }

    @Test
    fun `the what's-new record round-trips and the shown flag flips once`() = runTest {
        val preferences = repo()
        preferences.update {
            it.copy(
                whatsNewVersionCode = 53L,
                whatsNewVersionName = "1.3.2",
                whatsNewNotes = "Faster reconnect; the offline hint.",
                whatsNewShown = false,
            )
        }
        val p = preferences.current()
        assertEquals(53L, p.whatsNewVersionCode)
        assertEquals("1.3.2", p.whatsNewVersionName)
        assertEquals("Faster reconnect; the offline hint.", p.whatsNewNotes)
        assertFalse(p.whatsNewShown)
        // The consume: shown=true persists (never re-asks).
        preferences.update { it.copy(whatsNewShown = true) }
        assertTrue(preferences.current().whatsNewShown)
        assertEquals("the record outlives the show", 53L, preferences.current().whatsNewVersionCode)
    }

    @Test
    fun `the skip marker reaches the DISK as proto field 24 (file truth)`() = runTest {
        val instance = File(dir, "disk-${System.nanoTime()}").apply { mkdirs() }
        val file = File(instance, "preferences.pb")
        val preferences = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
        preferences.update { it.copy(skippedUpdateVersionCode = 55L) }
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(file.readBytes())
        assertTrue(parsed.hasSkippedUpdateVersionCode())
        assertEquals(55L, parsed.skippedUpdateVersionCode)
    }
}
