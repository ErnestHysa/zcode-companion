package com.zcode.android.app.update

import com.zcode.android.core.common.log.ZLog
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface DownloadState {
    data object Idle : DownloadState
    data class Running(val bytesRead: Long, val totalBytes: Long) : DownloadState
    data class Ready(val apk: File) : DownloadState
    data class Failed(val detail: String) : DownloadState
}

/**
 * Streams a release APK into the app's private cache (§37: user-consented download;
 * INV-16 — the artifact lives app-private until the install intent hands a
 * FileProvider URI to the system installer). Verifies the published SHA-256 when
 * the release carries one (inline T3 metadata or the sibling .sha256 asset) —
 * §37 publishes the digest and the app honors it.
 */
open class ApkDownloader(
    private val http: OkHttpClient,
    private val updatesDir: File,
    private val log: ZLog,
) {

    open suspend fun download(release: ReleaseInfo): DownloadState = withContext(Dispatchers.IO) {
        val expected = release.sha256Hex ?: release.sha256Url?.let { fetchSha256(it) }
        updatesDir.mkdirs()
        val part = File(updatesDir, "${release.apkAssetName}.part")
        val target = File(updatesDir, release.apkAssetName)
        val digest = MessageDigest.getInstance("SHA-256")
        try {
            http.newCall(
                Request.Builder()
                    .url(release.apkUrl)
                    .header("User-Agent", "zcode-android")
                    .build(),
            ).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext DownloadState.Failed("download HTTP ${response.code}")
                }
                val body = response.body
                val total = body.contentLength()
                var read = 0L
                body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buf = ByteArray(64 * 1024)
                        var lastEmit = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            output.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            if (read - lastEmit >= PROGRESS_CHUNK) {
                                lastEmit = read
                                emitProgress(read, total)
                            }
                        }
                        output.fd.sync()
                    }
                }
                emitProgress(read, read)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (expected != null && !actual.equals(expected, ignoreCase = true)) {
                part.delete()
                log.error("update.sha_mismatch", "expected=$expected actual=$actual")
                return@withContext DownloadState.Failed("SHA-256 mismatch — download discarded")
            }
            if (target.exists()) target.delete()
            if (!part.renameTo(target)) {
                return@withContext DownloadState.Failed("could not finalize downloaded APK")
            }
            DownloadState.Ready(target)
        } catch (e: Exception) {
            runCatching { part.delete() }
            DownloadState.Failed("${e.javaClass.simpleName}: ${e.message?.take(160)}")
        }
    }

    private fun fetchSha256(url: String): String? = runCatching {
        http.newCall(Request.Builder().url(url).header("User-Agent", "zcode-android").build())
            .execute().use { response ->
                if (!response.isSuccessful) return null
                // A .sha256 asset is "<hex>  <filename>" (sha256sum format).
                response.body.string().trim().split(Regex("\\s+")).first().lowercase()
            }
    }.getOrNull()

    var onProgress: ((Running) -> Unit)? = null

    private fun emitProgress(read: Long, total: Long) {
        onProgress?.invoke(DownloadState.Running(read, total))
    }

    companion object {
        private const val PROGRESS_CHUNK = 256L * 1024
    }
}
