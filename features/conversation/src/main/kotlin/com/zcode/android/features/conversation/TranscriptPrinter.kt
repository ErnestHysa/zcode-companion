package com.zcode.android.features.conversation

/**
 * G56 (vc52 Phase 13): print to PDF — the desktop exports pages to PDF
 * (printPageToPdf); the phone's share shipped text + Markdown only. The
 * pagination half is PURE (lossless: the pages join back to the exact
 * transcript line-for-line; every page carries whole lines, never a split
 * row); the print handoff itself rides the Android Printing framework
 * (PrintDocumentAdapter → PdfDocument) in the app layer.
 */
object TranscriptPrinter {

    /**
     * Splits [text] into pages of at most [linesPerPage] whole lines. Line
     * endings are preserved across the page set: `pages.joinToString("\n")`
     * is byte-identical to the input (the print is never a reflow edit).
     */
    fun paginate(text: String, linesPerPage: Int = 58): List<String> {
        require(linesPerPage > 0) { "linesPerPage must be positive" }
        val lines = text.split("\n")
        if (lines.isEmpty()) return listOf("")
        val pages = ArrayList<String>((lines.size + linesPerPage - 1) / linesPerPage)
        var i = 0
        while (i < lines.size) {
            val end = minOf(i + linesPerPage, lines.size)
            pages.add(lines.subList(i, end).joinToString("\n"))
            i = end
        }
        return pages
    }
}
