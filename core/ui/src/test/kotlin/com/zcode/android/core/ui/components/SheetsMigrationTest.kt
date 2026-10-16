package com.zcode.android.core.ui.components

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B3-sheets (gaps-and-polish Phase 2C, P1; re-confirmed in pixels N5): pickers and
 * menus lived in stacked AlertDialogs. The four named flows — the composer model
 * picker, the row-action menu, the queue item edit, and the message edit → confirm —
 * become ModalBottomSheets (`ZCodeModalBottomSheet`), so a dialog stack on create-task
 * drops to ONE dialog (the sheet is chrome, not a stacked dialog).
 */
class SheetsMigrationTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "repo root not found" }
    }

    private fun read(rel: String): String = File(root, rel).readText()

    @Test
    fun `the four flows are sheets - the conversation module hosts at least four`() {
        val s = read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/WriteComponents.kt") +
            read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/RowRenderers.kt") +
            read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/Phase9Components.kt") +
            read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/EditUserQueryDialog.kt")
        val count = Regex("""ZCodeModalBottomSheet\(""").findAll(s).count()
        assertTrue("expected >=4 sheet flows, found $count", count >= 4)
    }

    @Test
    fun `the row-action menu is a sheet - RowRenderers hosts no AlertDialog`() {
        assertTrue(
            "AlertDialog(" !in read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/RowRenderers.kt"),
        )
    }

    @Test
    fun `message edit is a sheet - EditUserQueryDialog hosts no AlertDialog`() {
        assertTrue(
            "AlertDialog(" !in read("features/conversation/src/main/kotlin/com/zcode/android/features/conversation/ui/EditUserQueryDialog.kt"),
        )
    }

    @Test
    fun `the sheet wrapper exists in core ui`() {
        assertTrue(File(root, "core/ui/src/main/kotlin/com/zcode/android/core/ui/components/ZCodeModalBottomSheet.kt").isFile)
    }
}
