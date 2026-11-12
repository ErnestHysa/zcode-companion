package com.zcode.android.features.conversation

import android.content.ActivityNotFoundException

/**
 * G57 (vc52 Phase 13) — the DATED BOUNDARY slice (decision recorded 2026-11-12,
 * vc52 Phase 13): the phone keeps the SYSTEM-VIEWER handoff for PDF/Office/PPTX
 * artifacts (the Android-native equivalent); the desktop's wasm viewers
 * (xlsx/legacy-doc/docx/pptx engines, 1.1–6 MB payloads each) stay desktop-side —
 * bundle size + form factor; the honest mobile order PDF → PPTX images → Office
 * read-only stays OPEN, with PDF already served (G28 zoom + the system viewer).
 *
 * The slice THIS phase enforces: NO DEAD PREVIEW CARDS. The artifact handoff's
 * launch can fail when NO app claims the MIME ("no viewer installed") — that
 * classifies as the TYPED fallback notice (artifact_no_viewer, naming the file),
 * never the silent dead tap. Every other launch fault keeps its generic face.
 */
object ArtifactOpenFallback {

    fun isNoViewerActivityLaunch(t: Throwable): Boolean = t is ActivityNotFoundException

    /** The notice's kind word — the SAME vocabulary the preview cards render. */
    fun labelFor(kind: GeneratedArtifactPreview.Kind): Int = when (kind) {
        GeneratedArtifactPreview.Kind.MARKDOWN -> com.zcode.android.features.conversation.R.string.preview_markdown
        GeneratedArtifactPreview.Kind.HTML -> com.zcode.android.features.conversation.R.string.preview_html
        GeneratedArtifactPreview.Kind.DOCX -> com.zcode.android.features.conversation.R.string.preview_docx
        GeneratedArtifactPreview.Kind.XLSX -> com.zcode.android.features.conversation.R.string.preview_xlsx
        GeneratedArtifactPreview.Kind.PPTX -> com.zcode.android.features.conversation.R.string.preview_pptx
        GeneratedArtifactPreview.Kind.PDF -> com.zcode.android.features.conversation.R.string.preview_pdf
        GeneratedArtifactPreview.Kind.AUDIO -> com.zcode.android.features.conversation.R.string.preview_audio
        GeneratedArtifactPreview.Kind.VIDEO -> com.zcode.android.features.conversation.R.string.preview_video
        GeneratedArtifactPreview.Kind.TEXT -> com.zcode.android.features.conversation.R.string.preview_text
    }
}
