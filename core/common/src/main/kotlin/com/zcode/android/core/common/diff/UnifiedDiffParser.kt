package com.zcode.android.core.common.diff

/**
 * Unified-diff domain model — architectural.md §22 (parser output contract,
 * ADR-010 renderer input). Pure Kotlin, no Android imports.
 *
 * Two producer planes feed the same model (docs/protocol/phase9-channels.md):
 *  - the v4 `fileChanges` plane arrives PRE-PARSED as hunk objects
 *    (`{oldStart, oldLines, newStart, newLines, lines[]}`) → [hunkFromWire];
 *  - the git channel's `getDiff.patch` arrives as unified-diff TEXT →
 *    [UnifiedDiffParser.parse].
 */
data class DiffFile(
    val path: String,
    val oldPath: String? = null,
    val binary: Boolean = false,
    val binaryHint: String? = null,
    val isNew: Boolean = false,
    val isDeleted: Boolean = false,
    val isRenamed: Boolean = false,
    val isModeChangeOnly: Boolean = false,
    val hunks: List<DiffHunk> = emptyList(),
) {
    val addedLines: Long get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADD }.toLong() }
    val removedLines: Long get() = hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.DEL }.toLong() }
    val changedLines: Long get() = addedLines + removedLines
}

data class DiffHunk(
    /** `@@ -a,b +c,d @@`-style header text (rendered verbatim). */
    val header: String,
    val oldStart: Int,
    val oldLines: Int,
    val newStart: Int,
    val newLines: Int,
    val lines: List<DiffLine>,
)

enum class DiffLineType { ADD, DEL, CONTEXT }

data class DiffLine(
    val type: DiffLineType,
    /** Content WITHOUT the leading ` `/`+`/`-` marker (rendering adds it back). */
    val content: String,
    val oldNo: Int?,
    val newNo: Int?,
)

/**
 * §22 parser: small, self-contained, LINEAR — no regex backtracking, no
 * lookahead beyond one line. Handles new/delete/rename headers, mode changes,
 * binary markers (`Binary files … differ` / `GIT binary patch`). Unknown
 * diff-extension lines are tolerated (skipped) — the parser never throws on
 * hostile input, it only ever produces fewer files.
 */
object UnifiedDiffParser {

    /** Guard against accidental full-history loads (INV-5/§28.7 discipline). */
    const val MAX_PARSE_LINES: Int = 200_000

    fun parse(patchText: String): List<DiffFile> {
        if (patchText.isEmpty()) return emptyList()
        val files = ArrayList<DiffFile>()
        var current: Builder? = null
        var hunk: HunkBuilder? = null
        var lines = 0

        fun flushHunk() {
            hunk?.let { current?.hunks?.add(it.build()) }
            hunk = null
        }

        fun flushFile() {
            flushHunk()
            current?.let { files.add(it.build()) }
            current = null
        }

        for (raw in patchText.lineSequence()) {
            if (++lines > MAX_PARSE_LINES) break // bounded parse (INV-5)
            when {
                raw.startsWith("diff --git ") -> {
                    flushFile()
                    current = Builder(pathsFromGitHeader(raw))
                }
                // --- / +++ only meaningful OUTSIDE hunk bodies (a `--- ` line inside
                // a hunk body is content and starts with ' '/'+'/'-'... actually a
                // removed line could be exactly "---": disambiguate by hunk state).
                raw.startsWith("--- ") && hunk == null -> current?.applyOldPath(raw.removePrefix("--- "))
                raw.startsWith("+++ ") && hunk == null -> current?.applyNewPath(raw.removePrefix("+++ "))
                raw.startsWith("new file mode") -> current?.isNew = true
                raw.startsWith("deleted file mode") -> current?.isDeleted = true
                raw.startsWith("rename from ") -> {
                    current?.isRenamed = true
                    current?.applyOldPath(raw.removePrefix("rename from "))
                }
                raw.startsWith("rename to ") -> {
                    current?.isRenamed = true
                    current?.applyNewPath(raw.removePrefix("rename to "))
                }
                raw.startsWith("old mode ") || raw.startsWith("new mode ") -> current?.isModeChangeOnly = true
                raw.startsWith("Binary files ") || raw.startsWith("GIT binary patch") -> {
                    current?.binary = true
                    current?.binaryHint = if (raw.startsWith("Binary files ")) raw else "GIT binary patch"
                }
                raw.startsWith("@@") -> {
                    flushHunk()
                    val counts = hunkHeaderCounts(raw)
                    hunk = HunkBuilder(raw.trim(), counts)
                }
                hunk != null && (raw.isEmpty() || raw[0] == ' ' || raw[0] == '+' || raw[0] == '-' || raw == "\\ No newline at end of file") -> {
                    if (raw.isEmpty()) {
                        hunk?.add(DiffLineType.CONTEXT, "", treatAsContext = true)
                    } else when (raw[0]) {
                        ' ' -> hunk?.add(DiffLineType.CONTEXT, raw.substring(1))
                        '+' -> hunk?.add(DiffLineType.ADD, raw.substring(1))
                        '-' -> hunk?.add(DiffLineType.DEL, raw.substring(1))
                        else -> {} // "\ No newline…" — rendered marker only, no numbering effect
                    }
                }
                raw.startsWith("index ") || raw.startsWith("similarity index ") ||
                    raw.startsWith("copy from ") || raw.startsWith("copy to ") ||
                    raw.startsWith("deleted file") -> Unit // metadata noise between headers
                else -> Unit // unknown extension line: tolerate (skip)
            }
        }
        flushFile()
        return files
    }

    /** Pre-parsed wire hunk (v4 fileChanges `readonlyDiffHunkSchema`) → model. */
    fun hunkFromWire(oldStart: Int, oldLines: Int, newStart: Int, newLines: Int, lines: List<String>): DiffHunk {
        val builder = HunkBuilder("@@ -${oldStart},${oldLines} +${newStart},${newLines} @@", counts(oldStart, oldLines, newStart, newLines))
        for (line in lines) {
            when {
                line.isEmpty() -> builder.add(DiffLineType.CONTEXT, "", treatAsContext = true)
                line[0] == ' ' -> builder.add(DiffLineType.CONTEXT, line.substring(1))
                line[0] == '+' -> builder.add(DiffLineType.ADD, line.substring(1))
                line[0] == '-' -> builder.add(DiffLineType.DEL, line.substring(1))
                else -> builder.add(DiffLineType.CONTEXT, line, treatAsContext = true)
            }
        }
        return builder.build()
    }

    /** `diff --git a/path b/path` — respects quoted paths with spaces. */
    internal fun pathsFromGitHeader(line: String): Pair<String?, String?> {
        val rest = line.removePrefix("diff --git ").trim()
        // Split at the " b/" that begins the second path, honoring quotes.
        if (rest.startsWith("\"")) {
            val first = readQuoted(rest) ?: return rest to null
            val remainder = rest.substring(first.length + 2).trimStart()
            val second = readQuoted(remainder) ?: return first to null
            return first to second
        }
        val idx = rest.indexOf(" b/")
        if (idx <= 0) return rest to null
        val a = rest.substring(0, idx)
        val b = rest.substring(idx + 1)
        val aPath = a.removePrefix("a/")
        val bPath = b.removePrefix("b/")
        return aPath to bPath
    }

    /** Reads a leading quoted string, honoring backslash escapes; null if not quoted/terminated. */
    private fun readQuoted(s: String): String? {
        if (!s.startsWith("\"")) return null
        var i = 1
        val sb = StringBuilder()
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                sb.append(s[i + 1]); i += 2; continue
            }
            if (c == '"') return sb.toString()
            sb.append(c); i++
        }
        return null
    }

    private fun hunkHeaderCounts(header: String): HunkCounts {
        // @@ -oldStart[,oldLines] +newStart[,newLines] @@
        val oldSpan = span(header, '-', '+')
        val newSpan = span(header, '+', '@')
        return counts(
            oldSpan.first, oldSpan.second,
            newSpan.first, newSpan.second,
        )
    }

    private data class HunkCounts(val oldStart: Int, val oldLines: Int, val newStart: Int, val newLines: Int)

    private fun counts(oldStart: Int, oldLines: Int, newStart: Int, newLines: Int) = HunkCounts(oldStart, oldLines, newStart, newLines)

    /** Reads `<marker><num>[,<num>]` after [from] up to [until]. */
    private fun span(header: String, from: Char, until: Char): Pair<Int, Int> {
        val s = header.indexOf(from)
        if (s < 0) return 1 to 0
        var e = s + 1
        while (e < header.length && header[e] != until && header[e] != ',') e++
        val start = header.substring(s + 1, e).trim().toIntOrNull() ?: 1
        var lines = 1
        if (e < header.length && header[e] == ',') {
            var f = e + 1
            while (f < header.length && header[f].isDigit()) f++
            lines = header.substring(e + 1, f).toIntOrNull() ?: 1
        }
        return start to lines
    }

    private class HunkBuilder(val header: String, val counts: HunkCounts) {
        val lines = ArrayList<DiffLine>()
        var oldNo = if (counts.oldLines == 0) null else counts.oldStart
        var newNo = if (counts.newLines == 0) null else counts.newStart

        fun add(type: DiffLineType, content: String, treatAsContext: Boolean = false) {
            val t = if (treatAsContext) DiffLineType.CONTEXT else type
            val o = if (t != DiffLineType.ADD) oldNo?.also { oldNo = it + 1 } else null
            val n = if (t != DiffLineType.DEL) newNo?.also { newNo = it + 1 } else null
            lines.add(DiffLine(t, content, o, n))
        }

        fun build() = DiffHunk(header, counts.oldStart, counts.oldLines, counts.newStart, counts.newLines, lines)
    }

    private class Builder(initialPaths: Pair<String?, String?>) {
        var oldPath: String? = initialPaths.first
        var newPath: String? = initialPaths.second
        var binary = false
        var binaryHint: String? = null
        var isNew = false
        var isDeleted = false
        var isRenamed = false
        var isModeChangeOnly = false
        val hunks = ArrayList<DiffHunk>()

        fun applyOldPath(p: String) {
            // `--- a/path<TAB>timestamp` — the tab (if any) begins the timestamp.
            val body = p.substringBefore('\t')
            oldPath = if (body.startsWith("\"")) readQuoted(body) ?: body else body.removePrefix("a/")
        }

        fun applyNewPath(p: String) {
            val body = p.substringBefore('\t')
            newPath = if (body.startsWith("\"")) readQuoted(body) ?: body else body.removePrefix("b/")
        }

        fun build(): DiffFile {
            val path = (newPath ?: oldPath ?: "").trim()
            return DiffFile(
                path = path,
                oldPath = oldPath?.takeIf { isRenamed && it != path },
                binary = binary,
                binaryHint = binaryHint,
                isNew = isNew,
                isDeleted = isDeleted,
                isRenamed = isRenamed,
                isModeChangeOnly = isModeChangeOnly && hunks.isEmpty(),
                hunks = hunks,
            )
        }
    }
}
