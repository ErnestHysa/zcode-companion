package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.domain.model.AppPreferences
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G63 (vc52 Phase 13, rule 13): the notification-sound pref round-trips through
 * the REAL serializer. Default ON (the historical behavior — absent field reads
 * true, never silently mutes an upgrade); OFF round-trips; a different field's
 * write never arms the mute.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesNotifySoundRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "sound-${System.nanoTime()}")
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
    fun `default is ON (absent field = audible — the upgrade path keeps its sound)`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(screenshotProtection = true) }
        assertTrue(preferences.current().notifySound)
    }

    @Test
    fun `mute round-trips through the real serializer`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(notifySound = false) }
        assertFalse(preferences.current().notifySound)
        // And back ON (a mute is a toggle, never a one-way latch).
        preferences.update { it.copy(notifySound = true) }
        assertTrue(preferences.current().notifySound)
    }

    @Test
    fun `raw proto field 23 survives the serializer boundary`() = runTest {
        val proto = com.zcode.android.data.proto.AppPreferencesProto.newBuilder()
            .setNotifySound(false)
            .build()
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(proto.toByteArray())
        assertTrue(parsed.hasNotifySound() && !parsed.notifySound)
    }

    @Test
    fun `the mute reaches the DISK as proto field 23 (file truth)`() = runTest {
        val instance = File(dir, "disk-${System.nanoTime()}").apply { mkdirs() }
        val file = File(instance, "preferences.pb")
        val preferences = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
        preferences.update { it.copy(notifySound = false) }
        // One DataStore per file (androidx contract) — the file's BYTES are the truth,
        // read back through the same serializer the store uses (no second instance).
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(file.readBytes())
        assertTrue("the mute persisted as an EXPLICIT false", parsed.hasNotifySound() && !parsed.notifySound)
    }
}
