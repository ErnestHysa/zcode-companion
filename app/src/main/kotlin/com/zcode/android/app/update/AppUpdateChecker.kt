package com.zcode.android.app.update

import com.zcode.android.core.security.pairing.CompatibilityGate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/** Result of a foreground update check (§14.2 T4). Rendered honestly, never guessed. */
sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState

    /** desktop > maxTested AND a newer release exists → banner + "Update now". */
    data class Available(val release: ReleaseInfo) : UpdateCheckState

    /** desktop ≤ maxTested (or unknown) — no check was even warranted. */
    data object NotApplicable : UpdateCheckState

    /** desktop is newer but this build already IS the latest release. */
    data object UpToDate : UpdateCheckState

    /** Releases endpoint unreachable/malformed — the honest compat warning stays. */
    data class Failed(val detail: String) : UpdateCheckState
}

/**
 * §14.2 T4 client update check against GitHub Releases metadata (§37's non-Play
 * channel). Gate is exactly the report's rule: `desktop.version > app.maxTested`
 * AND a newer release exists. No store assumption (§37): the installer is
 * feature-detected at use time, and the check itself only ever reads public
 * release metadata. Open for test seams only.
 */
open class AppUpdateChecker(
    private val releasesUrl: String,
    private val installedVersionCode: Long,
    internal val maxTestedDesktop: String = CompatibilityGate.MAX_TESTED_DESKTOP,
    private val http: OkHttpClient = OkHttpClient(),
) {

    open suspend fun check(desktopVersion: String?): UpdateCheckState {
        // The check is only warranted when the desktop is NEWER than what this
        // build was tested against (§14.2 T4: "desktop.version > app.maxTested").
        val aboveTested = desktopVersion != null &&
            CompatibilityGate.check(desktopVersion) is CompatibilityGate.Verdict.AboveTested
        if (!aboveTested) return UpdateCheckState.NotApplicable
        return fetchLatestRelease()?.let { release ->
            if (release.versionCode > installedVersionCode) {
                UpdateCheckState.Available(release)
            } else {
                UpdateCheckState.UpToDate
            }
        } ?: UpdateCheckState.Failed("releases metadata unavailable or unparseable")
    }

    private suspend fun fetchLatestRelease(): ReleaseInfo? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(
                Request.Builder()
                    .url(releasesUrl)
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "zcode-android")
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) return@withContext null
                ReleaseParser.parse(response.body.string())
            }
        }.getOrNull()
    }
}
