package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
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
 * G104 (pass-0 Phase 9): ADR-022 gate 3 is a PERSISTED Settings row — the
 * terminal input opt-in round-trips through the REAL serializer. Default OFF
 * (absent field reads false — the upgrade path never arms input); ON
 * round-trips; a different field's write never arms input.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesTerminalInputOptInRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "terminput-${System.nanoTime()}")
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
    fun `default is OFF (absent field - the upgrade path never arms input)`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(terminalOptIn = true) } // the VIEW opt-in, not input
        assertFalse(preferences.current().terminalInputOptIn)
    }

    @Test
    fun `the granted flip round-trips through the real serializer`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(terminalInputOptIn = true) }
        assertTrue(preferences.current().terminalInputOptIn)
        preferences.update { it.copy(terminalInputOptIn = false) }
        assertFalse(preferences.current().terminalInputOptIn)
    }

    @Test
    fun `raw proto field 29 survives the serializer boundary`() {
        val proto = com.zcode.android.data.proto.AppPreferencesProto.newBuilder()
            .setTerminalInputOptIn(true)
            .build()
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(proto.toByteArray())
        assertTrue(parsed.hasTerminalInputOptIn() && parsed.terminalInputOptIn)
    }

    @Test
    fun `the granted flip reaches the DISK as proto field 29 (file truth)`() = runTest {
        val instance = File(dir, "disk-${System.nanoTime()}").apply { mkdirs() }
        val file = File(instance, "preferences.pb")
        val preferences = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
        preferences.update { it.copy(terminalInputOptIn = true) }
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(file.readBytes())
        assertTrue("the granted flip persisted as an EXPLICIT true", parsed.hasTerminalInputOptIn() && parsed.terminalInputOptIn)
    }
}
