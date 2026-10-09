package com.zcode.android.app.update

import android.content.Intent
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.app.connect.ConnectionCenterScreen
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * the update check (T4) acceptance, on-device: with a seeded Releases fixture the banner + button
 * appear and the download → install-intent flow COMPLETES — real HTTP over device
 * loopback, real file write, real SHA-256 verification, real FileProvider URI, and
 * an install intent the system resolver can route to the installer (the user's
 * consent point, §37). The system installer activity itself is NOT launched —
 * the handoff contract is what the app owns.
 */
@RunWith(AndroidJUnit4::class)
class UpdateFlowInstrumentedTest {

    @get:Rule
    val rule = createComposeRule()

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun seededRelease_flowsToEnd_banner_download_installIntent() {
        val apkBytes = ByteArray(256 * 1024) { (it * 7 % 253).toByte() }
        val digest = sha256(apkBytes)

        val server = MockWebServer()
        // mockwebserver3: start() FIRST — url() throws before the socket is bound.
        server.start()
        // Order matters: (1) releases metadata, (2) the APK bytes.
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/json")
                .body(releaseJson(server.url("/app-release.apk").toString(), digest))
                .build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200)
                .addHeader("Content-Type", "application/vnd.android.package-archive")
                .body(okio.Buffer().write(apkBytes))
                .build(),
        )
        try {
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val controller = AppUpdateController(
                checker = AppUpdateChecker(
                    releasesUrl = server.url("/repos/x/y/releases/latest").toString(),
                    installedVersionCode = 36,
                    maxTestedDesktop = "3.14.4",
                    http = OkHttpClient(),
                ),
                downloader = ApkDownloader(OkHttpClient(), updatesDir, silentLog()),
                scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
                log = silentLog(),
                installer = { /* the recording assertion happens on the state below */ },
            )

            rule.setContent {
                ZCodeTheme {
                    val updateState by controller.check.collectAsState()
                    val downloadState by controller.download.collectAsState()
                    ConnectionCenterScreen(
                        phase = ConnectionPhase.CONNECTED,
                        endpointName = "Ernest's Mac",
                        desktopVersion = "3.14.5",
                        lastError = null,
                        lastRefreshAtMs = null,
                        onRetry = {},
                        onDismissError = {},
                        onBack = {},
                        updateState = updateState,
                        downloadState = downloadState,
                        onUpdateNow = controller::updateNow,
                    )
                }
            }

            // The route's foreground trigger, replicated: check on screen entry.
            controller.checkForUpdate("3.14.5")

            // Banner + button appear from the SEEDED release metadata…
            rule.waitUntil(10_000) {
                rule.onAllNodesWithTag("update_banner").fetchSemanticsNodes().isNotEmpty()
            }
            rule.onNodeWithTag("update_now").assertExists()

            // …and Update now drives the full download → verify → ready flow.
            rule.onNodeWithTag("update_now").performClick()
            rule.waitUntil(30_000) {
                controller.download.value is DownloadState.Ready
            }
            val apk = (controller.download.value as DownloadState.Ready).apk
            assertTrue(apk.path.startsWith(updatesDir.path))
            assertEquals(apkBytes.size.toLong(), apk.length())
            assertEquals(digest, sha256(apk.readBytes()))

            // The handoff contract the system installer receives (the user-consent
            // point, §37): a package-archive VIEW of a provider URI with read grant.
            val intent = ApkInstaller.installIntent(context, apk)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(
                "provider URI must resolve and stream",
                context.contentResolver.openInputStream(intent.data!!)?.read() != null,
            )
            val resolvers = context.packageManager.queryIntentActivities(intent, 0)
            assertTrue(
                "the system must be able to receive the install intent (resolvers=${resolvers.size})",
                resolvers.isNotEmpty(),
            )
        } finally {
            server.close()
        }
    }

    private fun releaseJson(apkUrl: String, digest: String): String = """
        {"tag_name":"v0.11.5-vc37","name":"rel",
         "body":"r<!-- zcode-android-release-meta {\"versionName\":\"0.11.5\",\"versionCode\":37,\"maxTestedDesktop\":\"3.14.5\",\"apk\":\"app-release.apk\",\"sha256\":\"$digest\"} -->",
         "assets":[{"name":"app-release.apk","browser_download_url":"$apkUrl"}]}
    """.trimIndent()

    private fun silentLog() = com.zcode.android.core.common.log.ZLog(
        minLevel = com.zcode.android.core.common.log.ZLog.LogLevel.WARN,
        ringCapacity = 16,
        sink = {},
    )
}
