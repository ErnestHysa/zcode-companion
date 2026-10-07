package com.zcode.android.app.update

import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * AAP-3 — the install handoff contract (§14.2 T4, §37 user-consented). The intent
 * is the generic package-archive VIEW routed by the system to its installer with
 * a FileProvider URI + explicit read grant; the unknown-sources consent is the
 * system's, feature-detected here (§37: no store assumption).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ApkInstallerTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun `install intent is the package-archive VIEW with a granted provider URI`() {
        val apk = java.io.File(context.cacheDir, "updates/app-release.apk")
            .apply { parentFile.mkdirs(); writeText("apk") }
        val intent = ApkInstaller.installIntent(context, apk)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(ApkInstaller.APK_MIME, intent.type)
        assertEquals(
            "content://${context.packageName}.fileprovider/updates/app-release.apk",
            intent.data.toString(),
        )
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @Test
    fun `feature detection reports the unknown-sources consent state without assuming it`() {
        // §37: "update logic feature-detects install permission". On a Robolectric
        // app the call itself must succeed and return a boolean — the value is the
        // platform's, never hardcoded by the app.
        val can = ApkInstaller.canRequestInstall(context)
        assertEquals(context.packageManager.canRequestPackageInstalls(), can)
    }
}
