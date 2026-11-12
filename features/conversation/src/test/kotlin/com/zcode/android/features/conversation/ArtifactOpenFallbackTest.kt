package com.zcode.android.features.conversation

import android.content.ActivityNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith
import org.junit.Test

/**
 * G57 (vc52 Phase 13) — the DATED BOUNDARY slice. Decision: the phone keeps the
 * SYSTEM-VIEWER handoff for PDF/Office/PPTX artifacts (the Android-native
 * equivalent; the desktop's wasm viewers — xlsx/legacy-doc/docx/pptx engines,
 * 1.1–6 MB payloads each — stay desktop-side; bundle size + form factor; the
 * honest mobile order PDF → PPTX images → Office read-only stays OPEN with PDF
 * already served by G28's zoom + the system viewer). The slice THIS phase pins:
 * NO DEAD PREVIEW CARDS — a system handoff with no installed viewer
 * (ActivityNotFoundException) degrades to a TYPED in-app notice, never the
 * silent dead tap the audit's F-27 family made famous.
 */
@RunWith(androidx.test.ext.junit.runners.AndroidJUnit4::class)
@org.robolectric.annotation.Config(sdk = [30])
class ArtifactOpenFallbackTest {

    @Test
    fun `a no-viewer launch classifies as the typed fallback`() {
        assertTrue(ArtifactOpenFallback.isNoViewerActivityLaunch(ActivityNotFoundException("no handler")))
    }

    @Test
    fun `other launch faults stay generic (SecurityException, IO, anything else)`() {
        assertFalse(ArtifactOpenFallback.isNoViewerActivityLaunch(SecurityException("denied")))
        assertFalse(ArtifactOpenFallback.isNoViewerActivityLaunch(java.io.IOException("disk")))
        assertFalse(ArtifactOpenFallback.isNoViewerActivityLaunch(IllegalStateException("x")))
        assertFalse(ArtifactOpenFallback.isNoViewerActivityLaunch(RuntimeException()))
    }

    @Test
    fun `the fallback notice names the artifact kind - honest copy in en and zh`() {
        val context = androidx.test.core.app.ApplicationProvider
            .getApplicationContext<android.content.Context>()
        val en = context.createConfigurationContext(
            android.content.res.Configuration().apply { setLocale(java.util.Locale.ENGLISH) },
        ).getString(com.zcode.android.features.conversation.R.string.artifact_no_viewer, "presentation.pptx")
        val zh = context.createConfigurationContext(
            android.content.res.Configuration().apply {
                setLocale(java.util.Locale.forLanguageTag("zh-CN"))
            },
        ).getString(com.zcode.android.features.conversation.R.string.artifact_no_viewer, "presentation.pptx")
        assertTrue(en.contains("presentation.pptx"))
        assertTrue(en.contains("viewer") || en.contains("app"))
        assertTrue(zh.isNotBlank() && zh != en)
    }

    @Test
    fun `the artifact-kind word for the notice - PDF and Office names resolve`() {
        // The notice uses the same kind vocabulary the cards already render —
        // a PPTX card and a DOCX card phrase their own no-viewer copy.
        assertEquals(
            com.zcode.android.features.conversation.R.string.preview_pptx,
            ArtifactOpenFallback.labelFor(GeneratedArtifactPreview.Kind.PPTX),
        )
        assertEquals(
            com.zcode.android.features.conversation.R.string.preview_docx,
            ArtifactOpenFallback.labelFor(GeneratedArtifactPreview.Kind.DOCX),
        )
        assertEquals(
            com.zcode.android.features.conversation.R.string.preview_pdf,
            ArtifactOpenFallback.labelFor(GeneratedArtifactPreview.Kind.PDF),
        )
    }
}
