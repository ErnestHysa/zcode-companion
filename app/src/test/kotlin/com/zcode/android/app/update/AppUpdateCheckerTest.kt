package com.zcode.android.app.update

import java.io.File
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AAP-3 — the §14.2 T4 gate, pinned end-to-end against a seeded releases endpoint
 * (MockWebServer): the exact truth table of the report's rule
 * `desktop.version > app.maxTested AND a newer release exists`.
 */
class AppUpdateCheckerTest {

    private fun releaseBody(vc: Long, versionName: String = "0.11.$vc") = """
        {"tag_name":"v0.11.$vc-vc$vc","name":"rel",
         "body":"r<!-- zcode-android-release-meta {\"versionName\":\"$versionName\",\"versionCode\":$vc,\"maxTestedDesktop\":\"3.14.5\"} -->",
         "assets":[{"name":"app-release.apk","browser_download_url":"https://x/app-release.apk"}]}
    """.trimIndent()

    private fun serverWithRelease(vc: Long): MockWebServer = MockWebServer().apply {
        enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/json")
                .body(releaseBody(vc))
                .build(),
        )
        start()
    }

    private fun checker(url: String, installedVc: Long) = AppUpdateChecker(
        releasesUrl = url,
        installedVersionCode = installedVc,
        maxTestedDesktop = "3.14.6",
        http = OkHttpClient(),
    )

    @Test
    fun `desktop newer + newer release = Available`() = runBlocking {
        val server = serverWithRelease(vc = 37)
        try {
            val state = checker(server.url("/repos/x/y/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.6")
            val available = state as UpdateCheckState.Available
            assertEquals(37L, available.release.versionCode)
            assertEquals("app-release.apk", available.release.apkAssetName)
            assertEquals(true, available.desktopNewer)
        } finally {
            server.close()
        }
    }

    @Test
    fun `desktop within tested matrix - update STILL offered (field fix 2026-10-12)`() = runBlocking {
        // The old gate skipped the check entirely when the desktop matched the
        // tested range — users were stuck. Now the gate only affects the wording.
        val server = serverWithRelease(vc = 37)
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.4")
            val available = state as UpdateCheckState.Available
            assertEquals(false, available.desktopNewer)
            assertEquals(37L, available.release.versionCode)
        } finally {
            server.close()
        }
    }

    @Test
    fun `desktop newer - Available carries desktopNewer=true for the compat wording`() = runBlocking {
        val server = serverWithRelease(vc = 37)
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.6")
            val available = state as UpdateCheckState.Available
            assertEquals(true, available.desktopNewer)
        } finally {
            server.close()
        }
    }

    @Test
    fun `unknown desktop - still checked, desktopNewer=false`() = runBlocking {
        val server = serverWithRelease(vc = 37)
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = null)
            val available = state as UpdateCheckState.Available
            assertEquals(false, available.desktopNewer)
            assertEquals(1, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `desktop newer but this build IS the latest = UpToDate`() = runBlocking {
        val server = serverWithRelease(vc = 36)
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.5")
            assertEquals(UpdateCheckState.UpToDate, state)
        } finally {
            server.close()
        }
    }

    @Test
    fun `older release than installed = UpToDate (never downgrade)`() = runBlocking {
        val server = serverWithRelease(vc = 35)
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.5")
            assertEquals(UpdateCheckState.UpToDate, state)
        } finally {
            server.close()
        }
    }

    @Test
    fun `releases endpoint error = Failed (the honest warning stays)`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(500).build())
        server.start()
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.5")
            assertTrue(state is UpdateCheckState.Failed)
        } finally {
            server.close()
        }
    }

    @Test
    fun `malformed release body = Failed`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().code(200).body("<html>not json</html>").build(),
        )
        server.start()
        try {
            val state = checker(server.url("/releases/latest").toString(), installedVc = 36)
                .check(desktopVersion = "3.14.5")
            assertTrue(state is UpdateCheckState.Failed)
        } finally {
            server.close()
        }
    }

    @Test
    fun `the app's own CompatibilityGate constant is the default maxTested`() {
        val checker = AppUpdateChecker(releasesUrl = "https://x", installedVersionCode = 36)
        assertEquals("3.14.5", checker.maxTestedDesktop)
        assertEquals(
            com.zcode.android.core.security.pairing.CompatibilityGate.MAX_TESTED_DESKTOP,
            checker.maxTestedDesktop,
        )
    }
}
