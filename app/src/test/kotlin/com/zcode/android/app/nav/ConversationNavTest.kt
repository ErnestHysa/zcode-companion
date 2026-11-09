package com.zcode.android.app.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-34-A (vc52 Phase 8, P2) — the nav half. With synth-task-1 open, a notification tap
 * delivered `zcode://task/synth-task-1#interaction=…` and ZCodeApp ran
 * `nav.navigate(Routes.conversation(link.taskId))` — no same-task check — so a SECOND
 * conversation entry for the same task stacked on the first (the predictive-back preview
 * showed the identical conversation underneath). Popping it tore down the session the
 * remaining screen still displayed (the hub half: ConnectionHubIntegrationTest).
 *
 * `launchSingleTop` alone is NOT the fix: Navigation matches the destination PATTERN
 * (`conversation/{taskId}`), not the arguments, so a link for task B while A is on top
 * would reuse A's entry — and A's backstack-scoped VM. The contract is the same TASK:
 * already on top → no new entry; deeper in the stack → pop back to it; otherwise push.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationNavTest {

    @get:Rule
    val rule = createComposeRule()

    private lateinit var nav: NavHostController

    private fun host() {
        rule.setContent {
            val controller = rememberNavController()
            nav = controller
            NavHost(controller, startDestination = Routes.TASKS) {
                composable(Routes.TASKS) {}
                composable(Routes.CONVERSATION) {}
                composable(Routes.FILES) {}
                composable(Routes.APPROVALS) {}
            }
        }
        rule.waitForIdle()
    }

    private fun conversationEntries(): List<String?> = nav.currentBackStack.value
        .filter { it.destination.route == Routes.CONVERSATION }
        .map { it.arguments?.getString("taskId") }

    @Test
    fun `F-34-A - a deep link to the conversation already on top leaves exactly ONE entry`() {
        host()
        rule.runOnIdle { nav.openConversation("synth-task-1") }
        val before = rule.runOnIdle { nav.currentBackStackEntry }
        rule.runOnIdle { nav.openConversation("synth-task-1") } // the notification tap
        rule.waitForIdle()
        assertEquals("a second entry for the open task was stacked", listOf("synth-task-1"), conversationEntries())
        assertTrue("the visible entry (and its retained VM) is the SAME one", before === nav.currentBackStackEntry)
    }

    @Test
    fun `F-34-A - a link to a task open deeper in the stack pops back to it - never a duplicate`() {
        host()
        rule.runOnIdle { nav.openConversation("synth-task-1") }
        val original = rule.runOnIdle { nav.currentBackStackEntry }
        rule.runOnIdle { nav.navigate(Routes.FILES) } // the user went on from the conversation
        rule.runOnIdle { nav.openConversation("synth-task-1") }
        rule.waitForIdle()
        assertEquals(listOf("synth-task-1"), conversationEntries())
        assertTrue("landed on the ORIGINAL entry", original === nav.currentBackStackEntry)
        assertEquals(Routes.CONVERSATION, nav.currentDestination?.route)
    }

    @Test
    fun `F-34-A - a different task still opens its own entry - singleTop's pattern match would have reused A`() {
        host()
        rule.runOnIdle { nav.openConversation("synth-task-1") }
        rule.runOnIdle { nav.openConversation("synth-task-2") }
        rule.waitForIdle()
        assertEquals(listOf("synth-task-1", "synth-task-2"), conversationEntries())
        // Back from B lands on A's (still-live) entry.
        rule.runOnIdle { nav.popBackStack() }
        rule.waitForIdle()
        assertEquals(listOf("synth-task-1"), conversationEntries())
    }
}
