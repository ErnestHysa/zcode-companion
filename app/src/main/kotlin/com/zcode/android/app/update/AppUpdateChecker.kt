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

    /**
     * A newer release exists. [desktopNewer] = the connected desktop is ALSO newer
     * than this build's tested range (the compat wording differs; the update itself
     * is offered EITHER WAY — field feedback 2026-10-12: gating the whole check on
     * the desktop version left users stuck on old builds with no signal).
     */
    data class Available(val release: ReleaseInfo, val desktopNewer: Boolean) : UpdateCheckState

    /** This build already IS the latest published release. */
    data object UpToDate : UpdateCheckState

    /** Releases endpoint unreachable/malformed — honest failure, retryable. */
    data class Failed(val detail: String) : UpdateCheckState
}

/**
 * §14.2 T4 client update check against GitHub Releases metadata (§37's non-Play
 * channel). FIELD FIX 2026-10-12: the check used to be GATED on
 * `desktop.version > app.maxTested` — a user whose desktop matched the tested
 * range never saw any update, ever. The gate now only affects the banner WORDING;
 * the check itself runs on every app open + manual refresh and offers any newer
 * release. No store assumption (§37); drafts/prereleases are invisible by GitHub's
 * own /releases/latest semantics, so "latest" always means published.
 */
open class AppUpdateChecker(
    private val releasesUrl: String,
    private val installedVersionCode: Long,
    internal val maxTestedDesktop: String = CompatibilityGate.MAX_TESTED_DESKTOP,
    private val http: OkHttpClient = OkHttpClient(),
) {

    open suspend fun check(desktopVersion: String?): UpdateCheckState {
        val desktopNewer = desktopVersion != null &&
            CompatibilityGate.check(desktopVersion) is CompatibilityGate.Verdict.AboveTested
        return fetchLatestRelease()?.let { release ->
            if (release.versionCode > installedVersionCode) {
                UpdateCheckState.Available(release, desktopNewer)
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
