package com.zcode.android.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** F-34-A (vc52 Phase 8) — the nav-graph wiring sweep (see ConversationNavTest for the contract). */
class ConversationNavWiringTest {

    /**
     * Wiring pin (the F-27-B sweep pattern): EVERY conversation navigation in the app's
     * nav graph goes through [openConversation] — a raw `navigate(Routes.conversation(…))`
     * anywhere re-opens the duplicate-entry class (the deep link, the approvals inbox's
     * "open task", the fork child, the task list).
     */
    @Test
    fun `F-34-A wiring - no raw conversation navigate remains in the nav graph`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val source = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        val raw = Regex("""\.navigate\(\s*Routes\.conversation\(""").findAll(source).count()
        assertEquals(
            "raw nav.navigate(Routes.conversation(…)) call sites stack duplicate entries for an open task",
            0,
            raw,
        )
        assertTrue(
            "the conversation routes ride openConversation",
            Regex("""\.openConversation\(""").findAll(source).count() >= 5,
        )
    }
}
