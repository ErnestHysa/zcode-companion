package com.zcode.android.app.nav

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P33-A (pass-0 Phase 7) — the generated-artifact preview card's Open affordance
 * was DEAD at HEAD and SILENT in both failure stages:
 *
 *  - **stage-2**: the download SUCCEEDS (FilesPort writes `cacheDir/downloads`,
 *    AppContainer) but `file_paths.xml` declared ONLY `shared/` + `updates/`, so
 *    `FileProvider.getUriForFile` threw IllegalArgumentException and the else branch
 *    was LOG-ONLY (ZCodeApp's preview.open handler). The pass-34 extension: the
 *    attachment chip's external open writes `cacheDir/attachments` — the same IAE
 *    family.
 *  - **stage-1**: an unserved ref faults at readFileRange → the download-null branch
 *    was LOG-ONLY too.
 *
 * Pins: the getUriForFile harness over BOTH cache roots must RESOLVE (the stage-2
 * repair, a real Robolectric harness over the app's own provider config); the typed
 * download-failed notices must exist on BOTH open arms (the stage-1 repair, the
 * source-shape sweep pattern); and the roots must be declared in the xml itself.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class FileProviderArtifactRootsTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val authority get() = context.packageName + ".fileprovider"

    @Test
    fun `stage-2 - a cache-downloads file resolves through the FileProvider`() {
        val dir = java.io.File(context.cacheDir, "downloads").apply { mkdirs() }
        val file = java.io.File(dir, "rig-p33-report.html").apply { writeText("<html>ok</html>") }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        assertNotNull("getUriForFile threw IAE — the downloads/ root is missing from file_paths.xml", uri)
        assertTrue(uri.toString().contains("downloads") || uri.pathSegments.isNotEmpty())
    }

    @Test
    fun `stage-2 - a cache-attachments file resolves through the FileProvider (pass-34 extension)`() {
        val dir = java.io.File(context.cacheDir, "attachments").apply { mkdirs() }
        val file = java.io.File(dir, "synth-task-1-rig-ref.bin").apply { writeBytes(ByteArray(16)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        assertNotNull("getUriForFile threw IAE — the attachments/ root is missing from file_paths.xml", uri)
    }

    @Test
    fun `the shared camera root still resolves (no regression)`() {
        val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
        val file = java.io.File(dir, "camera-1.jpg").apply { writeBytes(ByteArray(8)) }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
        assertNotNull(uri)
    }

    @Test
    fun `the xml declares the downloads and attachments cache roots`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val xml = java.io.File(root, "app/src/main/res/xml/file_paths.xml").readText()
        assertTrue("file_paths.xml must declare the downloads cache root", xml.contains("""name="downloads"""") && xml.contains("""path="downloads/""""))
        assertTrue("file_paths.xml must declare the attachments cache root", xml.contains("""name="attachments"""") && xml.contains("""path="attachments/""""))
    }

    @Test
    fun `stage-1 - both open arms surface the typed download-failed notice (never log-only)`() {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        // The artifact-open arm (onOpenArtifact) and the attachment-chip arm
        // (onOpenAttachmentExternally) — each download-null branch must show the
        // typed notice; the raw-code string must exist in both arms.
        val artifactArm = source.substringAfter("onOpenArtifact = { ref, mime ->")
            .substringBefore("onSlashMenuOpen = vm::onSlashMenuOpen")
        val attachmentArm = source.substringAfter("onOpenAttachmentExternally = { att ->")
            .substringBefore("// Phase 9 (§43.2-9)")
        assertTrue(
            "the artifact-open download-null branch must surface the typed download-failed notice",
            artifactArm.contains("artifact_download_failed") && artifactArm.contains("notifier.show"),
        )
        assertTrue(
            "the attachment-chip external-open download-null branch must surface the typed download-failed notice",
            attachmentArm.contains("artifact_download_failed") && attachmentArm.contains("notifier.show"),
        )
        assertEquals(
            "the string must exist in the conversation resources",
            1,
            Regex("""<string name="artifact_download_failed">""").findAll(conversationStrings()).count(),
        )
    }

    private fun conversationStrings(): String {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        return java.io.File(root, "features/conversation/src/main/res/values/strings.xml").readText()
    }
}
