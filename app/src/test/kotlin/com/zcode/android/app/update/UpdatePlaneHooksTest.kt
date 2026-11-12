package com.zcode.android.app.update

import com.zcode.android.core.common.log.ZLog
import java.io.File
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G43-P24 (vc52 Phase 13): the update-plane hooks.
 *
 * 1. Skip-version: a release the user skipped must not re-prompt — the system
 *    notification gate consults [AppUpdateController.isVersionSkipped]; the
 *    BANNER gate is the pure [UpdateSkipGate] (the ConnectionCenter composes it).
 *    Skipping hides the PROMPT, not the fact: check.value still resolves
 *    Available, and a later release (or the skip cleared) prompts again.
 * 2. What's-new: accepting the update (the download completes and the installer
 *    handoff fires) records the release via [AppUpdateController.onUpdateAccepted]
 *    — the app then owes a one-time notes sheet for THAT version.
 */
class UpdatePlaneHooksTest {

    private fun release(versionCode: Long) = ReleaseInfo(
        versionName = "1.3.$versionCode",
        versionCode = versionCode,
        apkUrl = "http://localhost/fake.apk",
        apkAssetName = "app-release.apk",
        sha256Hex = null,
        sha256Url = null,
        tag = "v1.3.$versionCode-vc$versionCode",
        notes = "Fixed things.",
        maxTestedDesktop = null,
    )

    private class FakeChecker(private val release: ReleaseInfo) : AppUpdateChecker(
        releasesUrl = "http://localhost/none",
        installedVersionCode = 1,
    ) {
        override suspend fun check(desktopVersion: String?): UpdateCheckState =
            UpdateCheckState.Available(release, desktopNewer = false)
    }

    private class FakeDownloader(private val target: File) : ApkDownloader(
        http = okhttp3.OkHttpClient(),
        updatesDir = File(System.getProperty("java.io.tmpdir")),
        log = ZLog(sink = {}),
    ) {
        override suspend fun download(release: ReleaseInfo): DownloadState = DownloadState.Ready(target)
    }

    private fun TestScope.controller(
        versionCode: Long,
        notify: ((String, String) -> Unit)? = null,
        isVersionSkipped: (Long) -> Boolean = { false },
        onUpdateAccepted: ((ReleaseInfo) -> Unit)? = null,
    ): AppUpdateController {
        val dir = File(System.getProperty("java.io.tmpdir"), "upd-${System.nanoTime()}").apply { mkdirs() }
        // The controller's scope rides the TEST scheduler — settle() drives it.
        return AppUpdateController(
            checker = FakeChecker(release(versionCode)),
            downloader = FakeDownloader(File(dir, "app-release.apk")),
            scope = kotlinx.coroutines.CoroutineScope(
                kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            ),
            log = ZLog(sink = {}),
            installer = { },
            notify = notify,
            isVersionSkipped = isVersionSkipped,
            onUpdateAccepted = onUpdateAccepted,
        )
    }

    private fun TestScope.settle() {
        testScheduler.advanceUntilIdle()
    }

    @Test
    fun `a skipped version never notifies - but the state still resolves Available`() = runTest {
        var notified = 0
        val c = controller(
            versionCode = 52,
            notify = { _, _ -> notified++ },
            isVersionSkipped = { it == 52L },
        )
        c.checkForUpdate(desktopVersion = null, force = true)
        settle()
        assertTrue("skip hides the PROMPT, not the fact", c.check.value is UpdateCheckState.Available)
        assertEquals("no system notification for a skipped version", 0, notified)
    }

    @Test
    fun `an unskipped version notifies exactly as before`() = runTest {
        var notified = 0
        val c = controller(
            versionCode = 53,
            notify = { _, _ -> notified++ },
            isVersionSkipped = { it == 52L },
        )
        c.checkForUpdate(desktopVersion = null, force = true)
        settle()
        assertEquals(1, notified)
    }

    @Test
    fun `accepting the update records the release for the what's-new sheet`() = runTest {
        var accepted: ReleaseInfo? = null
        val c = controller(
            versionCode = 53,
            onUpdateAccepted = { accepted = it },
        )
        c.checkForUpdate(desktopVersion = null, force = true)
        settle()
        c.updateNow()
        settle()
        assertEquals("the release rides the accept hook", 53L, accepted?.versionCode)
        assertEquals("Fixed things.", accepted?.notes)
    }

    @Test
    fun `the pure banner gate - only the EXACT skipped version is suppressed`() {
        assertTrue(UpdateSkipGate.bannerSuppressed(releaseVersionCode = 52, skippedVersionCode = 52))
        assertFalse(UpdateSkipGate.bannerSuppressed(releaseVersionCode = 53, skippedVersionCode = 52))
        assertFalse(UpdateSkipGate.bannerSuppressed(releaseVersionCode = 52, skippedVersionCode = null))
    }
}
