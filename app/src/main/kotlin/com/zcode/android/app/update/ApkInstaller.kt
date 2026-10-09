package com.zcode.android.app.update

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File

/**
 * The user-consented install handoff (§14.2 T4 "download APK → install intent";
 * §37: user-consented download + install intent, no store assumption).
 *
 * The system package installer is feature-detected, never assumed: the intent is
 * the generic package-archive VIEW that every Android resolver routes to the
 * installer, and [canRequestInstall] reports the unknown-sources consent state
 * so callers can explain a pending consent instead of failing silently. The
 * system installer UI IS the user consent (§37) — this class never installs
 * silently.
 */
object ApkInstaller {

    const val APK_MIME = "application/vnd.android.package-archive"

    fun canRequestInstall(context: Context): Boolean =
        context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            context.packageName + ".fileprovider",
            apk,
        )
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, APK_MIME)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
    }

    fun install(context: Context, apk: File) {
        context.startActivity(installIntent(context, apk))
    }
}
