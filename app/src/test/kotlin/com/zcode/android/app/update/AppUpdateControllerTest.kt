package com.zcode.android.app.update

import com.zcode.android.core.common.log.ZLog
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AAP-3 — controller semantics: the foreground check lifecycle and the
 * "Update now" download→install handoff, with test seams (§14.2 T4).
 */
private fun noopLog() = ZLog(minLevel = ZLog.LogLevel.ERROR, ringCapacity = 8, sink = {})

@OptIn(ExperimentalCoroutinesApi::class)
class AppUpdateControllerTest {

    private fun availableRelease() = ReleaseInfo(
        versionName = "0.11.5",
        versionCode = 37,
        apkUrl = "https://x/app-release.apk",
        apkAssetName = "app-release.apk",
        sha256Hex = null,
        sha256Url = null,
        tag = "v0.11.5-vc37",
        notes = null,
        maxTestedDesktop = null,
    )

    private class FakeChecker(var result: UpdateCheckState) : AppUpdateChecker("https://unused", 0L) {
        var calls = 0
        override suspend fun check(desktopVersion: String?): UpdateCheckState {
            calls += 1
            return result
        }
    }

    private class FakeDownloader(private val result: DownloadState) : ApkDownloader(
        okhttp3.OkHttpClient(), File("/nonexistent-updates"), noopLog(),
    ) {
        var downloads = 0
        override suspend fun download(release: ReleaseInfo): DownloadState {
            downloads += 1
            return result
        }
    }

    private class HangingDownloader : ApkDownloader(
        okhttp3.OkHttpClient(), File("/nonexistent-updates"), noopLog(),
    ) {
        override suspend fun download(release: ReleaseInfo): DownloadState {
            while (true) kotlinx.coroutines.delay(1_000)
        }
    }

    private fun TestScope.controller(
        checker: AppUpdateChecker,
        downloader: ApkDownloader,
        installed: MutableMap<File, Int>,
    ): AppUpdateController = AppUpdateController(
        checker = checker,
        downloader = downloader,
        scope = backgroundScope,
        log = noopLog(),
        installer = { installed[it] = (installed[it] ?: 0) + 1 },
    )

    @Test
    fun `checkForUpdate publishes the checker's Available state`() = runTest(UnconfinedTestDispatcher()) {
        val release = availableRelease()
        val installed = mutableMapOf<File, Int>()
        val c = controller(FakeChecker(UpdateCheckState.Available(release)), FakeDownloader(DownloadState.Idle), installed)
        c.checkForUpdate("3.14.5")
        assertEquals(release, (c.check.value as UpdateCheckState.Available).release)
    }

    @Test
    fun `a completed check is not restarted on re-entry (no flicker, no re-fetch)`() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeChecker(UpdateCheckState.UpToDate)
        val installed = mutableMapOf<File, Int>()
        val c = controller(fake, FakeDownloader(DownloadState.Idle), installed)
        c.checkForUpdate("3.14.5")
        c.checkForUpdate("3.14.5")
        assertEquals(1, fake.calls)
    }

    @Test
    fun `updateNow downloads and hands the verified APK to the installer exactly once`() = runTest(UnconfinedTestDispatcher()) {
        val installed = mutableMapOf<File, Int>()
        val apk = File.createTempFile("fake", ".apk")
        val c = controller(
            FakeChecker(UpdateCheckState.Available(availableRelease())),
            FakeDownloader(DownloadState.Ready(apk)),
            installed,
        )
        c.checkForUpdate("3.14.5")
        c.updateNow()
        val ready = c.download.value as DownloadState.Ready
        assertEquals(1, installed[ready.apk])
        apk.delete()
    }

    @Test
    fun `updateNow with no available release never downloads or installs`() = runTest(UnconfinedTestDispatcher()) {
        val fake = FakeChecker(UpdateCheckState.UpToDate)
        val downloader = FakeDownloader(DownloadState.Ready(File("/tmp/never.apk")))
        val installed = mutableMapOf<File, Int>()
        val c = controller(fake, downloader, installed)
        c.checkForUpdate("3.14.5")
        c.updateNow()
        assertEquals(UpdateCheckState.UpToDate, c.check.value)
        assertEquals(0, downloader.downloads)
        assertEquals(0, installed.size)
    }

    @Test
    fun `download failure surfaces Failed and the installer is never called`() = runTest(UnconfinedTestDispatcher()) {
        val installed = mutableMapOf<File, Int>()
        val c = controller(
            FakeChecker(UpdateCheckState.Available(availableRelease())),
            FakeDownloader(DownloadState.Failed("boom")),
            installed,
        )
        c.checkForUpdate("3.14.5")
        c.updateNow()
        assertEquals("boom", (c.download.value as DownloadState.Failed).detail)
        assertEquals(0, installed.size)
    }

    @Test
    fun `updateNow while a download is running is ignored (no double install)`() = runTest(UnconfinedTestDispatcher()) {
        val installed = mutableMapOf<File, Int>()
        val c = controller(
            FakeChecker(UpdateCheckState.Available(availableRelease())),
            HangingDownloader(),
            installed,
        )
        c.checkForUpdate("3.14.5")
        c.updateNow()
        c.updateNow()
        assertTrue(c.download.value is DownloadState.Running)
        assertEquals(0, installed.size)
    }
}
