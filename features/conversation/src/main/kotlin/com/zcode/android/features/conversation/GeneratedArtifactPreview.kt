package com.zcode.android.features.conversation

import com.zcode.android.domain.model.ConversationRow

/**
 * I2.8 (Phase 6A): generated-artifact preview cards (desktop `chat.previewCards.*`).
 *
 * The kind table is the desktop's own (renderer `lZe`/`sZe`): extension → card kind,
 * cross-checked against the artifact kind vocabulary (`tZe` = audio/document/
 * presentation/video/workbook) when one is present — a mismatch makes NO card. The
 * text detector mirrors the desktop's four families (file:// URLs, markdown links,
 * quoted paths, bare workspace paths) with its overlap guard: a span already claimed
 * by an earlier family is never re-matched. A missed mention degrades to the existing
 * prose — never a wrong card (INV-8 direction).
 */
object GeneratedArtifactPreview {

    enum class Kind { MARKDOWN, HTML, DOCX, XLSX, PPTX, PDF, AUDIO, VIDEO, TEXT }

    private val EXTENSIONS: Map<String, Kind> = buildMap {
        put("md", Kind.MARKDOWN)
        put("html", Kind.HTML)
        put("htm", Kind.HTML)
        put("docx", Kind.DOCX)
        put("xlsx", Kind.XLSX)
        put("pptx", Kind.PPTX)
        put("pdf", Kind.PDF)
        put("txt", Kind.TEXT)
        for (ext in listOf("mp4", "mov", "webm", "m4v")) put(ext, Kind.VIDEO)
        for (ext in listOf("mp3", "wav", "m4a", "ogg", "opus", "flac", "weba")) put(ext, Kind.AUDIO)
    }

    /** Desktop `tZe` — artifact-kind word → card kind (agreement required). */
    private val ARTIFACT_KINDS: Map<String, Kind> = mapOf(
        "audio" to Kind.AUDIO,
        "document" to Kind.DOCX,
        "presentation" to Kind.PPTX,
        "video" to Kind.VIDEO,
        "workbook" to Kind.XLSX,
    )

    private val EXT_ALTERNATION = EXTENSIONS.keys.joinToString("|")

    data class DetectedCard(val path: String, val kind: Kind)

    fun kindFor(path: String, artifactKind: String? = null): Kind? {
        val name = path.substringAfterLast('/').substringAfterLast('\\')
        val ext = name.substringAfterLast('.', "").lowercase()
        val byExtension = EXTENSIONS[ext] ?: return null
        if (artifactKind == null) return byExtension
        val mapped = ARTIFACT_KINDS[artifactKind.trim().lowercase()] ?: return null
        return if (mapped == byExtension) byExtension else null
    }

    private val MARKDOWN_LINK = Regex("\\[([^\\]\\n]*)\\]\\(([^)\\n]+)\\)")
    private val FILE_URL = Regex("file://[^\\s<>()\\]`'\"]+")
    private val QUOTED_PATH = Regex(
        "[\"'“”‘’]([^\"'“”‘’\\r\\n]+?\\.(?:$EXT_ALTERNATION))(?:[:?#][^\"'“”‘’]*)?[\"'“”‘’]",
        RegexOption.IGNORE_CASE,
    )
    private val BARE_PATH = Regex(
        "[A-Za-z0-9_.@/\\\\()\\-]+\\.(?:$EXT_ALTERNATION)\\b",
        RegexOption.IGNORE_CASE,
    )

    /** The four desktop families, ordered by occurrence, overlap-guarded, deduplicated. */
    fun detectCards(text: String): List<DetectedCard> {
        if (text.isBlank()) return emptyList()
        data class Candidate(val start: Int, val end: Int, val path: String)

        val candidates = ArrayList<Candidate>()
        fun claim(range: IntRange, path: String) {
            if (candidates.none { range.first < it.end && it.start < range.last + 1 }) {
                candidates.add(Candidate(range.first, range.last + 1, path))
            }
        }
        for (m in MARKDOWN_LINK.findAll(text)) {
            val target = m.groupValues[2]
            if (kindFor(target) != null) {
                val targetStart = m.range.first + 1 + m.groupValues[1].length + 2 // "]" + "("
                claim(targetStart until targetStart + target.length, target)
            }
        }
        for (m in FILE_URL.findAll(text)) {
            val base = m.value.removePrefix("file://").trimStart('/').substringAfterLast('/')
            if (kindFor(base) != null) claim(m.range, base)
        }
        for (m in QUOTED_PATH.findAll(text)) {
            kindFor(m.groupValues[1])?.let { claim(m.range, m.groupValues[1]) }
        }
        for (m in BARE_PATH.findAll(text)) {
            kindFor(m.value)?.let { claim(m.range, m.value.trim()) }
        }
        return candidates
            .sortedBy { it.start }
            .distinctBy { it.path }
            .map { DetectedCard(it.path, kindFor(it.path)!!) }
    }

    /** The `artifact` row face: the display name's extension decides (INV-8 fallback null). */
    fun forArtifactRow(row: ConversationRow.Artifact): Kind? = kindFor(row.displayName.ifBlank { row.logicalArtifactKey })

    /** Open-external MIME (the system chooser filters candidate apps by it). */
    fun mimeFor(kind: Kind): String = when (kind) {
        Kind.MARKDOWN -> "text/markdown"
        Kind.HTML -> "text/html"
        Kind.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        Kind.XLSX -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        Kind.PPTX -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        Kind.PDF -> "application/pdf"
        Kind.AUDIO -> "audio/*"
        Kind.VIDEO -> "video/*"
        Kind.TEXT -> "text/plain"
    }
}
