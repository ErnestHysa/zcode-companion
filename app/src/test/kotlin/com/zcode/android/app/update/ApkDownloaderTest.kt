package com.zcode.android.app.update

import com.zcode.android.core.common.log.ZLog
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * AAP-3 — the APK download leg: stream to the private updates dir, honor the
 * published SHA-256 (inline or .sha256 asset), fail closed on any mismatch
 * (§37: the digest is published and the app verifies it).
 */
class ApkDownloaderTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun noopLog() = ZLog(minLevel = ZLog.LogLevel.ERROR, ringCapacity = 8, sink = {})

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun releaseWith(url: String, shaHex: String? = null, shaUrl: String? = null) =
        ReleaseInfo(
            versionName = "0.11.5",
            versionCode = 37,
            apkUrl = url,
            apkAssetName = "app-release.apk",
            sha256Hex = shaHex,
            sha256Url = shaUrl,
            tag = "v0.11.5-vc37",
            notes = null,
            maxTestedDesktop = null,
        )

    @Test
    fun `download streams the exact bytes into the updates dir`() = runBlocking {
        val apkBytes = ByteArray(300 * 1024) { (it % 251).toByte() }
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder()
                .code(200)
                .addHeader("Content-Type", "application/vnd.android.package-archive")
                .body(okio.Buffer().write(apkBytes))
                .build(),
        )
        server.start()
        try {
            val dir = tmp.newFolder("updates")
            val downloader = ApkDownloader(OkHttpClient(), dir, noopLog())
            val result = downloader.download(releaseWith(server.url("/apk").toString()))
            val ready = result as DownloadState.Ready
            assertEquals("app-release.apk", ready.apk.name)
            assertTrue(ready.apk.path.startsWith(dir.path))
            assertTrue(ready.apk.length() == apkBytes.size.toLong())
            assertTrue(ready.apk.inputStream().readBytes().contentEquals(apkBytes))
        } finally {
            server.close()
        }
    }

    @Test
    fun `inline sha256 mismatch fails closed and leaves no artifact`() = runBlocking {
        val apkBytes = "definitely-not-the-announced-digest".toByteArray()
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().code(200).body(okio.Buffer().write(apkBytes)).build(),
        )
        server.start()
        try {
            val dir = tmp.newFolder("updates2")
            val result = ApkDownloader(OkHttpClient(), dir, noopLog()).download(
                releaseWith(
                    server.url("/apk").toString(),
                    shaHex = "2222222222222222222222222222222222222222222222222222222222222222",
                ),
            )
            assertTrue(result is DownloadState.Failed)
            assertTrue("SHA-256" in (result as DownloadState.Failed).detail)
            assertEquals(0, dir.listFiles()?.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `sha256 from the sibling asset URL is fetched and verified`() = runBlocking {
        val apkBytes = "apk-payload-for-digest-check".toByteArray()
        val digest = sha256(apkBytes)
        val server = MockWebServer()
        // The downloader fetches the digest BEFORE the APK body — enqueue in that order.
        server.enqueue(
            // sha256sum format: "<hex>  <filename>"
            MockResponse.Builder().code(200).body("$digest  app-release.apk\n").build(),
        )
        server.enqueue(
            MockResponse.Builder().code(200).body(okio.Buffer().write(apkBytes)).build(),
        )
        server.start()
        try {
            val dir = tmp.newFolder("updates3")
            val result = ApkDownloader(OkHttpClient(), dir, noopLog()).download(
                releaseWith(server.url("/apk").toString(), shaUrl = server.url("/apk.sha256").toString()),
            )
            assertNotNull(result as DownloadState.Ready)
            assertEquals(2, server.requestCount)
        } finally {
            server.close()
        }
    }

    @Test
    fun `http error surfaces a Failed state, no partial file`() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse.Builder().code(503).build())
        server.start()
        try {
            val dir = tmp.newFolder("updates4")
            val result = ApkDownloader(OkHttpClient(), dir, noopLog())
                .download(releaseWith(server.url("/apk").toString()))
            assertTrue(result is DownloadState.Failed)
            assertNull((result as DownloadState.Failed).detail.takeIf { it.isEmpty() })
            assertEquals(0, dir.listFiles()?.size)
        } finally {
            server.close()
        }
    }

    @Test
    fun `stale target from a previous release is replaced`() = runBlocking {
        val dir = tmp.newFolder("updates5")
        val stale = File(dir, "app-release.apk").apply { writeText("stale-v36") }
        val fresh = "fresh-v37-payload".toByteArray()
        val server = MockWebServer()
        server.enqueue(
            MockResponse.Builder().code(200).body(okio.Buffer().write(fresh)).build(),
        )
        server.start()
        try {
            val result = ApkDownloader(OkHttpClient(), dir, noopLog())
                .download(releaseWith(server.url("/apk").toString()))
            assertTrue(result is DownloadState.Ready)
            assertTrue("fresh-v37-payload" == stale.readText())
        } finally {
            server.close()
        }
    }
}
