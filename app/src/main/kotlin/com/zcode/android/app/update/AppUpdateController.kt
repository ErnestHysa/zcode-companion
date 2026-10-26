package com.zcode.android.app.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.zcode.android.core.common.log.ZLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * §14.2 T4 controller: owns the update-check + download state for the
 * connection-center banner AND the system update notification. FIELD FIX
 * (2026-10-12): the check runs on EVERY app open (cold + foreground resume) and
 * from a manual refresh — it is NOT gated on the desktop version anymore (the
 * desktop gate only changes the banner wording). A newer release posts a system
 * notification (keyed per release versionCode, so it never stacks) with a deep
 * link straight into the app.
 */
class AppUpdateController(
    private val checker: AppUpdateChecker,
    private val downloader: ApkDownloader,
    private val scope: CoroutineScope,
    private val log: ZLog,
    private val installer: (File) -> Unit,
    private val notify: ((title: String, body: String) -> Unit)? = null,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val _check = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val check: StateFlow<UpdateCheckState> = _check

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    /** Newest release versionCode already notified this process (no stacking). */
    private var lastNotifiedVersionCode: Long = 0

    /** Coalesce: a foreground resume within [MIN_INTERVAL_MS] skips the network. */
    private var lastCheckAtMs: Long = 0

    init {
        downloader.onProgress = { running -> _download.value = running }
    }

    /**
     * Foreground check. [force] = explicit user action (button) — always hits the
     * network; otherwise a check within the last 10 minutes is reused.
     */
    fun checkForUpdate(desktopVersion: String?, force: Boolean = false) {
        val current = _check.value
        if (current is UpdateCheckState.Checking) return
        if (!force) {
            val fresh = nowMs() - lastCheckAtMs < MIN_INTERVAL_MS
            // Only the Available banner is flicker-protected (920fcd5's contract:
            // "Available blocks re-fetch — no flicker; TERMINAL states re-check on
            // the next trigger"). UpToDate previously rode `known` and stranded the
            // re-check — the pre-existing red pin this repair closes.
            val known = current is UpdateCheckState.Available
            if (fresh && known) return
        }
        _check.value = UpdateCheckState.Checking
        lastCheckAtMs = nowMs()
        scope.launch {
            val next = runCatching { checker.check(desktopVersion) }
                .getOrElse { UpdateCheckState.Failed("${it.javaClass.simpleName}: ${it.message?.take(120)}") }
            _check.value = next
            log.info(
                "update.check",
                "desktop=${desktopVersion?.take(24)} state=${next::class.simpleName}" +
                    (next as? UpdateCheckState.Available)?.let { " vc=${it.release.versionCode}" } ?: "",
            )
            if (next is UpdateCheckState.Available && next.release.versionCode != lastNotifiedVersionCode) {
                lastNotifiedVersionCode = next.release.versionCode
                notify?.invoke(
                    "ZCode Companion ${next.release.versionName} available",
                    if (next.desktopNewer) {
                        "Your desktop (newer than this build) is verified with the update — tap to update."
                    } else {
                        "A newer build is available — tap to update."
                    },
                )
            }
        }
    }

    /** "Update now": download → SHA-verify → hand off to the system installer. */
    fun updateNow() {
        val release = (_check.value as? UpdateCheckState.Available)?.release ?: return
        if (_download.value is DownloadState.Running) return
        // F-UPDATE2: a verified APK already on disk → straight to the install handoff
        // (the button doubles as "Install update" once the download is Ready).
        (_download.value as? DownloadState.Ready)?.let { ready ->
            runCatching { installer(ready.apk) }
                .onFailure {
                    log.warn("update.install_failed", "${it.javaClass.simpleName}: ${it.message?.take(120)}")
                    _download.value = DownloadState.Failed("installer handoff failed: ${it.message?.take(120)}")
                }
            return
        }
        _download.value = DownloadState.Running(0, 0)
        scope.launch {
            val result = downloader.download(release)
            _download.value = result
            if (result is DownloadState.Ready) {
                log.info("update.install_intent", "apk=${result.apk.name} vc=${release.versionCode}")
                runCatching { installer(result.apk) }
                    .onFailure {
                        log.warn("update.install_failed", "${it.javaClass.simpleName}: ${it.message?.take(120)}")
                        _download.value = DownloadState.Failed("installer handoff failed: ${it.message?.take(120)}")
                    }
            } else if (result is DownloadState.Failed) {
                log.warn("update.download_failed", result.detail)
            }
        }
    }

    companion object {
        private const val MIN_INTERVAL_MS = 10 * 60_000L

        /** System notification channel for update availability (created lazily). */
        const val UPDATES_CHANNEL = "updates"

        fun ensureUpdatesChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            manager.createNotificationChannel(
                NotificationChannel(
                    UPDATES_CHANNEL,
                    "App updates",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply { description = "New ZCode Companion releases" },
            )
        }
    }
}
