package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.proto.AppPreferencesProto
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * G4/AC6 (vc51): the default interaction-behavior preference round-trips through the
 * additive-first preference proto — a store that never wrote the field reads null (no
 * local default = follow the desktop), the chosen value survives the wire round-trip,
 * and a hand-written junk value can never leak out of the closed set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesInteractionBehaviorStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "g4-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repo() = PreferencesRepositoryImpl(
        DataStoreFactory.create(AppPreferencesSerializer, produceFile = { File(dir, "preferences.pb") }),
    )

    @Test
    fun `interactionBehavior defaults null and round-trips the update`() = runTest {
        val preferences = repo()
        assertNull("a fresh store has no local default", preferences.current().interactionBehavior)
        preferences.update { it.copy(interactionBehavior = "queue") }
        assertEquals("queue", preferences.current().interactionBehavior)
        preferences.update { it.copy(interactionBehavior = "guide") }
        assertEquals("guide", preferences.current().interactionBehavior)
        preferences.update { it.copy(interactionBehavior = null) }
        assertNull("cleared back to follow-the-desktop", preferences.current().interactionBehavior)
    }

    private fun repoSeededWith(proto: AppPreferencesProto): PreferencesRepositoryImpl {
        val file = File(dir, "preferences.pb")
        file.writeBytes(proto.toByteArray())
        return PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { file }),
        )
    }

    @Test
    fun `a proto written before the field decodes the additive default`() = runTest {
        // A pre-G4 payload (13 fields, no interaction_behavior) reads the domain default.
        val legacy = AppPreferencesProto.newBuilder()
            .setNotificationContentLevel("MINIMAL")
            .setScreenshotProtection(true)
            .build()
        val preferences = repoSeededWith(legacy)
        assertNull("unset optional field → domain default (additive-first)", preferences.current().interactionBehavior)
    }

    @Test
    fun `an out-of-set stored value never leaks past the closed set`() = runTest {
        val raw = AppPreferencesProto.newBuilder().setInteractionBehavior("bogus").build()
        val preferences = repoSeededWith(raw)
        assertNull("junk decodes to no default (the mapping is the closed-set gate)", preferences.current().interactionBehavior)
    }
}
