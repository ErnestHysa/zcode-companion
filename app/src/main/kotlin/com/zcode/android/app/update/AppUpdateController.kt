package com.zcode.android.app.update

import com.zcode.android.core.common.log.ZLog
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * §14.2 T4 controller: owns the update-check + download state for the
 * connection-center banner. The check is FOREGROUND (§14.2): it runs when the
 * connection center is open, never in the background (§29 no-polling — the
 * screen open is the trigger, not a timer).
 */
class AppUpdateController(
    private val checker: AppUpdateChecker,
    private val downloader: ApkDownloader,
    private val scope: CoroutineScope,
    private val log: ZLog,
    private val installer: (File) -> Unit,
) {

    private val _check = MutableStateFlow<UpdateCheckState>(UpdateCheckState.Idle)
    val check: StateFlow<UpdateCheckState> = _check

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download

    init {
        downloader.onProgress = { running -> _download.value = running }
    }

    /**
     * Foreground check. Safe to call on every connection-center entry: when the
     * desktop is ≤ maxTested (or unknown) this returns [UpdateCheckState.NotApplicable]
     * WITHOUT touching the network, and an in-flight/completed check is not restarted.
     */
    fun checkForUpdate(desktopVersion: String?) {
        val current = _check.value
        if (current is UpdateCheckState.Checking) return
        if (current is UpdateCheckState.Available) return
        _check.value = UpdateCheckState.Checking
        scope.launch {
            val next = runCatching { checker.check(desktopVersion) }
                .getOrElse { UpdateCheckState.Failed("${it.javaClass.simpleName}: ${it.message?.take(120)}") }
            _check.value = next
            log.info(
                "update.check",
                "desktop=${desktopVersion?.take(24)} state=${next::class.simpleName}" +
                    (next as? UpdateCheckState.Available)?.let { " vc=${it.release.versionCode}" } ?: "",
            )
        }
    }

    /** "Update now": download → SHA-verify → hand off to the system installer. */
    fun updateNow() {
        val release = (_check.value as? UpdateCheckState.Available)?.release ?: return
        if (_download.value is DownloadState.Running) return
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
}
