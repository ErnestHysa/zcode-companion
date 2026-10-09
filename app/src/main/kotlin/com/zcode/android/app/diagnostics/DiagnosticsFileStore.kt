package com.zcode.android.app.diagnostics

import java.io.File
import java.io.IOException

/**
 * Round-7b field-debugging addition: PERSIST the redacted diagnostics ring across
 * process restarts. The in-memory ring dies with the app — and the field workflow
 * ("first launch fails, force-close, reopen works") was exactly the case whose
 * diagnostics were lost. This store appends redacted, pre-formatted log lines to a
 * file in the app-private cacheDir (never backed up, INV-3/INV-15-safe) and keeps
 * the PREVIOUS process's file for the export's "previous session" section.
 *
 * Plain File IO, injectable directory — JVM-testable without Android.
 */
class DiagnosticsFileStore private constructor(private val dir: File) {

    private val current: File = File(dir, FILE_CURRENT)
    private val previous: File = File(dir, FILE_PREVIOUS)

    companion object {
        const val FILE_CURRENT = "diagnostics-current.log"
        const val FILE_PREVIOUS = "diagnostics-previous.log"
        const val FILE_CRASH = "diagnostics-last-crash.log"
        private const val MAX_CURRENT_BYTES = 384L * 1024
        private const val MAX_PREVIOUS_BYTES = 384L * 1024

        fun inDir(dir: File): DiagnosticsFileStore {
            dir.mkdirs()
            return DiagnosticsFileStore(dir)
        }
    }

    /** Called once per process start: rotate current → previous (capped), start fresh. */
    fun rotateOnStartup(nowMs: Long = System.currentTimeMillis()) {
        try {
            if (current.exists()) {
                previous.delete()
                if (!current.renameTo(previous)) current.delete()
            }
            capFile(previous, MAX_PREVIOUS_BYTES)
            current.writeText("=== diagnostics session start (${java.time.Instant.ofEpochMilli(nowMs)}) ===\n")
        } catch (e: IOException) {
            // Diagnostics must never break the app — drop on the floor.
        }
    }

    /**
     * FIELD (2026-09-30, vc12): capture the LAST crash (stack + ring marker) so the
     * export after a crash still carries the evidence — the user could not share
     * diagnostics from a dead process. Overwrites each crash; read by the export.
     */
    fun writeCrash(thread: String, stack: String, nowMs: Long = System.currentTimeMillis()) {
        try {
            File(dir, FILE_CRASH).writeText(
                "=== crash @ ${java.time.Instant.ofEpochMilli(nowMs)} thread=$thread ===\n" +
                    stack.take(96_000),
            )
        } catch (e: IOException) {
        }
    }

    /** The last captured crash (stack + header), or empty. */
    fun readLastCrash(): String = try {
        val f = File(dir, FILE_CRASH)
        if (f.exists()) f.readText() else ""
    } catch (e: IOException) {
        ""
    }

    /** Appends a structured auto-report block (capped). Never throws. */
    fun appendAutoBlock(header: String, body: String) {
        try {
            synchronized(this) {
                current.appendText("\n>>> AUTO $header\n" + body.take(8_000) + "\n<<< END AUTO\n")
                if (current.length() > MAX_CURRENT_BYTES) capFile(current, MAX_CURRENT_BYTES)
            }
        } catch (e: IOException) {
        }
    }

    /** Appends one pre-formatted, pre-redacted line. Never throws. */
    fun append(line: String) {
        try {
            synchronized(this) {
                current.appendText(line + "\n")
                if (current.length() > MAX_CURRENT_BYTES) {
                    capFile(current, MAX_CURRENT_BYTES)
                }
            }
        } catch (e: IOException) {
            // drop
        }
    }

    /** Tail of the PREVIOUS process's log (capped), or empty. */
    fun readPrevious(maxBytes: Long = 192L * 1024): String = try {
        if (previous.exists()) capRead(previous, maxBytes) else ""
    } catch (e: IOException) {
        ""
    }

    /** Tail of the CURRENT process's persisted log (survives even if the ring rolled). */
    fun readCurrent(maxBytes: Long = 192L * 1024): String = try {
        if (current.exists()) capRead(current, maxBytes) else ""
    } catch (e: IOException) {
        ""
    }

    private fun capRead(file: File, maxBytes: Long): String {
        val bytes = file.readBytes()
        val start = if (bytes.size > maxBytes) bytes.size - maxBytes.toInt() else 0
        var s = String(bytes, start, bytes.size - start)
        if (start > 0) s = s.substringAfter('\n', s) // drop the partial line
        return s
    }

    /** Keeps roughly the last [maxBytes] of a file (drop oldest lines). */
    private fun capFile(file: File, maxBytes: Long) {
        if (!file.exists() || file.length() <= maxBytes) return
        val bytes = file.readBytes()
        val keep = bytes.copyOfRange(bytes.size - maxBytes.toInt(), bytes.size)
        val text = String(keep).substringAfter('\n', "") // drop the partial first line
        file.writeText(text)
    }
}
