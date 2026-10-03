package com.zcode.android.features.files.port

import com.zcode.android.domain.model.WorkspaceFileEntry
import com.zcode.android.domain.model.WorkspaceTextSlice
import java.io.File

/**
 * Consumer-side port for the §23.4 read-only workspace file browser (INV-2/INV-6).
 * INV-9: NO write/delete/rename methods exist. INV-16: desktop paths are
 * navigation tokens — the port passes protocol-returned paths through and never
 * constructs one.
 */
interface FilesPort {

    /** Workspace root path (display-only; from the connected endpoint). */
    fun workspaceRoot(): String?

    /** `readdir(path)` — entries for one directory (bounded by the desktop). */
    suspend fun list(path: String): ListingResult

    data class ListingResult(val entries: List<WorkspaceFileEntry>, val error: String?)

    /** `readTextFile` slice (paged by the caller — offset/bytesRead/totalBytes). */
    suspend fun readText(path: String, offset: Long?, length: Long?): TextResult

    data class TextResult(val slice: WorkspaceTextSlice?, val error: String?)

    /**
     * §23.4 download-to-device: chunked `readFileRange` into app cache, then
     * [onShare] (FileProvider share — app-provided) or null when the connection
     * does not support it. Never writes desktop paths.
     */
    suspend fun download(path: String, maxBytes: Long = DEFAULT_DOWNLOAD_CAP): DownloadResult

    data class DownloadResult(val file: File?, val bytes: Long, val error: String?)

    companion object {
        /** Download cap (INV-5/§28.7: bounded fetches, never unbounded). */
        const val DEFAULT_DOWNLOAD_CAP: Long = 8L * 1024 * 1024
    }
}
