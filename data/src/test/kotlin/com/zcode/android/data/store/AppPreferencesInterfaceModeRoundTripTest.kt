package com.zcode.android.data.store

import androidx.datastore.core.DataStoreFactory
import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.data.proto.AppPreferencesProto
import com.zcode.android.domain.model.AppPreferences
import java.io.ByteArrayInputStream
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-36-C (vc52 Phase 7, rule 13): the presentation-mode proto seam round-trip.
 * The writer stored the enum NAME ("OFFICE", DataStoreRepositories.setInterfaceMode)
 * while the reader ran the DESKTOP wire mapper (InterfaceMode.fromWire, matching only
 * `office|general|concise`) — so "OFFICE" fell through to CODING forever and the
 * shipped G36 feature was unreachable on device (pass-36 receipt: field 15 =
 * `7a 06 4f 46 46 49 43 45` in the pulled preferences.pb, dialog still Coding).
 *
 * The real-serializer round-trip covers EVERY enum field of AppPreferencesProto:
 * notification_content_level (2), theme_mode (9), interface_mode (15), app_locale (19),
 * plus the string-constrained interaction_behavior (14). The enum-audit pin enumerates
 * the proto's string fields via its descriptor so the NEXT unmapped enum seam fails
 * here instead of on a device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class AppPreferencesInterfaceModeRoundTripTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(ApplicationProvider.getApplicationContext<Context>().cacheDir, "ifmode-${System.nanoTime()}")
        dir.mkdirs()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun repo(preSeed: ByteArray? = null): PreferencesRepositoryImpl {
        // One DataStore per file per process — every instance gets its OWN subdir
        // (the "multiple DataStores active for the same file" gate).
        val instance = File(dir, "i-${System.nanoTime()}").apply { mkdirs() }
        if (preSeed != null) {
            File(instance, "preferences.pb").writeBytes(preSeed)
        }
        return PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { File(instance, "preferences.pb") }),
        )
    }

    // ---- the recorded defect matrix ----

    @Test
    fun `OFFICE round-trips through the real serializer`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(interfaceMode = AppPreferences.InterfaceMode.OFFICE) }
        assertEquals(
            "the shipped G36 feature must be reachable on device (the pass-36 dialog stayed Coding)",
            AppPreferences.InterfaceMode.OFFICE,
            preferences.current().interfaceMode,
        )
    }

    @Test
    fun `the on-device poison OFFICE reads back as OFFICE (existing installs)`() = runTest {
        // The exact pre-fix bytes the pass-36 forensics pulled: field 15 = "OFFICE".
        val poisoned = AppPreferencesProto.newBuilder()
            .setNotificationContentLevel("MINIMAL")
            .setInterfaceMode("OFFICE")
            .build()
            .toByteArray()
        assertEquals("OFFICE", AppPreferencesProto.parseFrom(poisoned).interfaceMode)
        val preferences = repo(preSeed = poisoned)
        assertEquals(
            "an existing install's stored enum NAME must be honored (case-insensitive read)",
            AppPreferences.InterfaceMode.OFFICE,
            preferences.current().interfaceMode,
        )
    }

    @Test
    fun `legacy wire spellings round-trip - general maps to OFFICE`() = runTest {
        val legacy = AppPreferencesProto.newBuilder()
            .setInterfaceMode("general")
            .build()
            .toByteArray()
        val preferences = repo(preSeed = legacy)
        assertEquals(AppPreferences.InterfaceMode.OFFICE, preferences.current().interfaceMode)
    }

    @Test
    fun `garbage falls to the CODING default`() = runTest {
        val garbage = AppPreferencesProto.newBuilder()
            .setInterfaceMode("weird-future-value")
            .build()
            .toByteArray()
        val preferences = repo(preSeed = garbage)
        assertEquals(AppPreferences.InterfaceMode.CODING, preferences.current().interfaceMode)
    }

    @Test
    fun `CODING round-trips and an absent field defaults to CODING`() = runTest {
        val preferences = repo()
        preferences.update { it.copy(interfaceMode = AppPreferences.InterfaceMode.CODING) }
        assertEquals(AppPreferences.InterfaceMode.CODING, preferences.current().interfaceMode)
        val absent = AppPreferencesProto.newBuilder().build().toByteArray()
        assertEquals(AppPreferences.InterfaceMode.CODING, repo(preSeed = absent).current().interfaceMode)
    }

    // ---- rule 13: EVERY enum field of AppPreferencesProto round-trips ----

    @Test
    fun `notificationContentLevel themeMode and appLocale round-trip`() = runTest {
        val preferences = repo()
        preferences.update {
            it.copy(
                notificationContentLevel = AppPreferences.NotificationContentLevel.CONTENT,
                themeMode = AppPreferences.ThemeMode.ZAI_DARK,
                appLocale = AppPreferences.AppLocale.ZH_RCN,
                interactionBehavior = "queue",
            )
        }
        val current = preferences.current()
        assertEquals(AppPreferences.NotificationContentLevel.CONTENT, current.notificationContentLevel)
        assertEquals(AppPreferences.ThemeMode.ZAI_DARK, current.themeMode)
        assertEquals(AppPreferences.AppLocale.ZH_RCN, current.appLocale)
        assertEquals("queue", current.interactionBehavior)
        preferences.update {
            it.copy(
                notificationContentLevel = AppPreferences.NotificationContentLevel.MINIMAL,
                themeMode = AppPreferences.ThemeMode.SYSTEM,
                appLocale = AppPreferences.AppLocale.SYSTEM,
                interactionBehavior = null,
            )
        }
        val back = preferences.current()
        assertEquals(AppPreferences.NotificationContentLevel.MINIMAL, back.notificationContentLevel)
        assertEquals(AppPreferences.ThemeMode.SYSTEM, back.themeMode)
        assertEquals(AppPreferences.AppLocale.SYSTEM, back.appLocale)
    }

    @Test
    fun `enum audit - every enum-shaped string field of the schema is covered by a round-trip mapping`() {
        // The pass-36 finding was the SECOND proto-mapping defect in this file (the
        // interactionBehavior builder shadow before it) — the proto seam is the
        // recurring blind spot. The generated class is protobuf-LITE (no descriptor
        // reflection), so this pin enumerates the schema SOURCE itself: every
        // `optional string` field inside the AppPreferencesProto message must be
        // covered here. A NEW string field added to the schema without an audit
        // entry (and without its own round-trip pin) fails — the next proto seam
        // is caught here, not on a device (rule 13).
        val schema = sequenceOf("src/main/proto/endpoint_store.proto", "data/src/main/proto/endpoint_store.proto")
            .map { java.io.File(it) }
            .firstOrNull { it.isFile }
            ?: error("endpoint_store.proto not found from the test working dir")
        val lines = schema.readLines()
        val start = lines.indexOfFirst { it.contains("message AppPreferencesProto") }
        val body = lines.drop(start).takeWhile { !it.startsWith("}") }
        val stringFields = body
            .mapNotNull { line -> Regex("optional\\s+string\\s+(\\w+)\\s*=").find(line)?.groupValues?.get(1) }
            .toSet()
        assertTrue("the schema parse must find the string fields", stringFields.isNotEmpty())
        val audited = mapOf(
            // field → the mapper that owns its mapping (each pinned above)
            "notification_content_level" to "CONTENT|MINIMAL (CONTENT-else-MINIMAL reader)",
            "theme_mode" to "LIGHT|DARK|SYSTEM|ZAI_LIGHT|ZAI_DARK",
            "interaction_behavior" to "\"queue\"|\"guide\"|null (string-constrained, not an enum)",
            "interface_mode" to "office|coding (the F-36-C seam; case-insensitive + legacy general/concise)",
            "app_locale" to "SYSTEM|EN|ZH_RCN",
            "selected_endpoint_id" to "free string (round-tripped byte-exact, no enum mapping)",
            "selected_workspace_key" to "free string (round-tripped byte-exact, no enum mapping)",
        )
        assertTrue(
            "unaudited schema string field(s): ${stringFields.filterNot { it in audited }} — " +
                "add a real-serializer round-trip pin for the new enum-like seam (rule 13)",
            stringFields.all { it in audited },
        )
        assertTrue(
            "the audit names fields the schema no longer has: ${audited.keys.filterNot { it in stringFields }}",
            audited.keys.all { it in stringFields },
        )
    }
}
