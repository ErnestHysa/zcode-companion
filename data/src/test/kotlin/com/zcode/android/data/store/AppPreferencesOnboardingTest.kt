package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.proto.AppPreferencesProto
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * H1.5 (Phase 6B): the onboarding-done flag round-trips through the additive-first
 * preference proto — a store that never wrote the field reads the domain default
 * (false → the pass shows once), and the flip survives the wire round-trip.
 */
class AppPreferencesOnboardingTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "onb-${System.nanoTime()}")
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
    fun `onboardingDone defaults false and round-trips the update`() = runTest {
        val preferences = repo()
        assertFalse("a fresh store has not run the pass", preferences.current().onboardingDone)
        preferences.update { it.copy(onboardingDone = true) }
        assertTrue("the flip survives the proto round-trip", preferences.current().onboardingDone)
    }

    @Test
    fun `a proto written before the field decodes the additive default`() = runTest {
        // A pre-H1.5 payload (12 fields, no onboarding_done) decodes with the default.
        val legacy = AppPreferencesProto.newBuilder()
            .setNotificationContentLevel("MINIMAL")
            .setScreenshotProtection(true)
            .build()
        val decoded = AppPreferencesSerializer.readFrom(ByteArrayInputStream(legacy.toByteArray()))
        assertFalse("unset optional field → domain default (additive-first)", decoded.onboardingDone)
    }
}
