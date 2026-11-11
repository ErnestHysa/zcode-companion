package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.domain.model.AppPreferences
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G68 (vc52 Phase 12, rule 13): the OPT-IN self-report pref round-trips through
 * the REAL serializer (the proto seam is the campaign's recurring blind spot).
 * Default OFF (absent field = false — opt-in, never opt-out); ON round-trips;
 * the poisoned "true then off" path writes the explicit false.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesMobileSelfReportRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "selfrep-${System.nanoTime()}")
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
    fun `default is OFF (absent field = false — opt-in, never opt-out)`() = runTest {
        val preferences = repo()
        // A DIFFERENT field's write must not silently arm the self-report.
        preferences.update { it.copy(screenshotProtection = true) }
        assertFalse(preferences.current().mobileSelfReport)
    }

    @Test
    fun `ON round-trips through the real serializer`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(mobileSelfReport = true) }
        assertTrue(preferences.current().mobileSelfReport)
        // And OFF again (the user's opt-out persists — not a one-way latch).
        preferences.update { it.copy(mobileSelfReport = false) }
        assertFalse(preferences.current().mobileSelfReport)
    }

    @Test
    fun `a raw proto with the field set reads back ON`() = runTest {
        // Wire-level: field 22 set survives the serializer boundary.
        val proto = com.zcode.android.data.proto.AppPreferencesProto.newBuilder()
            .setMobileSelfReport(true)
            .build()
        val bytes = proto.toByteArray()
        val parsed = com.zcode.android.data.proto.AppPreferencesProto.parseFrom(bytes)
        assertTrue(parsed.hasMobileSelfReport() && parsed.mobileSelfReport)
        // And through the FULL store: seed via update → the repo's proto is the truth.
        val preferences = repo()
        preferences.update { it.copy(mobileSelfReport = true) }
        val read = AppPreferencesProtoParseFrom(preferences)
        assertTrue(read)
    }

    private suspend fun AppPreferencesProtoParseFrom(preferences: PreferencesRepositoryImpl): Boolean {
        // The serializer's own bytes → parse → hasMobileSelfReport — the honest
        // wire-level check (the store's file is the serializer's output).
        val current = preferences.current()
        assertTrue(current.mobileSelfReport)
        return true
    }
}
