package com.zcode.android.features.conversation

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * F-P8-F (pass-0 Phase 7) — the Take-photo intent's DENIED/failed launch surfaced
 * NOTHING in the attachment flow. The A8 guard covers the SYNCHRONOUS launch throw
 * (SecurityException/ActivityNotFoundException → the inline camera-unavailable copy),
 * but the ASYNC verdict — the TakePicture ActivityResult arriving `ok=false` when the
 * camera app denies/fails the capture (the API-36 class) — was silently dropped: the
 * user tapped Take-photo and the composer just sat there.
 *
 * The pin (the A8 source-shape pattern): the camera callback must arm the typed
 * honest face — `composer_camera_no_photo` — never fall through silently.
 */
class ComposerCameraNoPhotoFaceTest {

    @Test
    fun `F-P8-F - the camera callback arms the typed no-photo face instead of dropping the verdict`() {
        val composer = composerSource()
        assertTrue(
            Regex("""cameraLauncher[\s\S]{0,400}ok ->[\s\S]{0,600}cameraNoPhoto""").containsMatchIn(composer),
            "the TakePicture callback must arm the honest no-photo face on ok=false (the silent-drop shape)",
        )
        assertTrue(
            "composer_camera_no_photo" in composer,
            "the no-photo face must render with its own testTag",
        )
    }

    @Test
    fun `F-P8-F - the no-photo copy exists and is honest`() {
        val strings = stringsXml()
        val entry = Regex("""<string name="composer_camera_no_photo">([^<]*)</string>""").find(strings)
        val copy = entry?.groupValues?.get(1)
        assertTrue(copy != null, "composer_camera_no_photo must exist in strings.xml")
        assertTrue(
            copy!!.contains("no photo", ignoreCase = true) || copy.contains("didn't return", ignoreCase = true) || copy.contains("did not return", ignoreCase = true),
            "the copy must not claim the camera app is unavailable (that is the A8 launch-throw face)",
        )
        assertTrue(
            copy.contains("denied", ignoreCase = true) || copy.contains("may have been", ignoreCase = true) || copy.contains("captured", ignoreCase = true),
            "the copy must hint at the denial family",
        )
    }

    @Test
    fun `F-P8-F - the A8 synchronous guard still stands (no regression)`() {
        val composer = composerSource()
        assertTrue("launchPick { cameraLauncher.launch" in composer)
        assertTrue("composer_attach_error" in composer)
    }

    private fun composerSource(): String {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        val source = java.io.File(
            root,
            "features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt",
        ).readText()
        return source.substringAfter("fun ComposerBar(").substringBefore("fun PendingSendsStrip(")
    }

    private fun stringsXml(): String {
        val root = generateSequence(java.io.File(System.getProperty("user.dir"))) { it.parentFile }
            .first { java.io.File(it, "settings.gradle.kts").isFile }
        return java.io.File(root, "features/conversation/src/main/res/values/strings.xml").readText()
    }
}
