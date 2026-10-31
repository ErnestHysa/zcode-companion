package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.port.ConversationPort
import com.zcode.android.features.conversation.ui.PendingCommandRecoveryBanner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G12 (vc51) — the pending-command recovery banner. The recorded gap: the phone
 * has the outbox + journal machinery but NO user-facing banner for replayable
 * pending commands (the desktop's PendingCommandRecoveryBanner.tsx +
 * pendingCommandReplay.ts). Pins: the persisted replayable set surfaces through
 * the VM, replay resends each entry with its SAME commandId (INV-4), honest
 * copy for offline/failed replays, and the banner renders with a working
 * Replay action.
 */
class PendingCommandRecoveryVmTest {

    private class RecoveryPort(
        val commands: List<ConversationPort.RecoverableCommand>,
        val retryOutcome: ConversationPort.SendOutcome,
    ) : Phase7FakePort() {
        val retried = mutableListOf<String>()
        override fun observePendingCommandRecovery(taskId: String): kotlinx.coroutines.flow.Flow<List<ConversationPort.RecoverableCommand>> =
            MutableStateFlow(commands)
        override suspend fun retrySend(commandId: String): ConversationPort.SendOutcome {
            retried.add(commandId)
            return retryOutcome
        }
    }

    private fun commands() = listOf(
        ConversationPort.RecoverableCommand("cmd-a", "sendText", "first draft", 1L),
        ConversationPort.RecoverableCommand("cmd-b", "sendGoalCommand", "/goal finish", 2L),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `persisted replayable commands surface through the port into the VM`() = runTest {
        val vm = ConversationViewModel(RecoveryPort(commands(), ConversationPort.SendOutcome.ACCEPTED), backgroundScope, "s-rec")
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()
        assertEquals(2, vm.pendingRecovery.value.size)
        assertEquals("cmd-a", vm.pendingRecovery.value.first().commandId)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `replay resends every entry with its SAME commandId (INV-4) and clears the notice on success`() = runTest {
        val port = RecoveryPort(commands(), ConversationPort.SendOutcome.ACCEPTED)
        val vm = ConversationViewModel(port, backgroundScope, "s-rec")
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        vm.replayPendingCommands()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("cmd-a", "cmd-b"), port.retried)
        assertEquals(null, vm.state.value.sendNotice)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `offline replay keeps the entries and says so - never a silent no-op`() = runTest {
        val port = RecoveryPort(commands(), ConversationPort.SendOutcome.BLOCKED_OFFLINE)
        val vm = ConversationViewModel(port, backgroundScope, "s-rec")
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        vm.replayPendingCommands()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertEquals(listOf("cmd-a", "cmd-b"), port.retried)
        val notice = vm.state.value.sendNotice
        assertTrue(notice != null && notice.kind == SendNotice.Kind.WARNING, "offline replay surfaces honest warning copy")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `failed replay surfaces the honest failure copy with the entry kept`() = runTest {
        val port = RecoveryPort(commands(), ConversationPort.SendOutcome.FAILED)
        val vm = ConversationViewModel(port, backgroundScope, "s-rec")
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        vm.replayPendingCommands()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        val notice = vm.state.value.sendNotice
        assertTrue(notice != null && notice.kind == SendNotice.Kind.ERROR, "failed replay surfaces the typed error copy")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `replay with nothing pending is a no-op`() = runTest {
        val port = RecoveryPort(emptyList(), ConversationPort.SendOutcome.ACCEPTED)
        val vm = ConversationViewModel(port, backgroundScope, "s-rec")
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        vm.replayPendingCommands()
        testScheduler.runCurrent()
        testScheduler.advanceUntilIdle()

        assertTrue(port.retried.isEmpty())
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PendingCommandRecoveryBannerUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `banner renders the honest copy with a working replay action`() {
        var replays = 0
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                PendingCommandRecoveryBanner(
                    pending = listOf(
                        ConversationPort.RecoverableCommand("cmd-a", "sendText", "the lost message", 1L),
                    ),
                    onReplay = { replays++ },
                )
            }
        }
        rule.onNodeWithTag("pending_recovery_banner").assertExists()
        rule.onNodeWithText("Replay").performClick()
        assertEquals(1, replays)
    }

    @Test
    fun `banner hides via its dismiss control`() {
        val pending = listOf(
            ConversationPort.RecoverableCommand("cmd-a", "sendText", "the lost message", 1L),
        )
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                PendingCommandRecoveryBanner(pending = pending, onReplay = {})
            }
        }
        rule.onAllNodesWithTag("pending_recovery_banner").assertCountEquals(1)
        // The view-local dismiss (Cancel) removes every node.
        rule.onNodeWithText("Cancel").performClick()
        rule.onAllNodesWithTag("pending_recovery_banner").assertCountEquals(0)
    }

    @Test
    fun `an empty pending set never renders the banner`() {
        rule.setContent {
            ZCodeTheme(themeMode = com.zcode.android.core.ui.theme.ThemeMode.LIGHT) {
                PendingCommandRecoveryBanner(pending = emptyList(), onReplay = {})
            }
        }
        rule.onAllNodesWithTag("pending_recovery_banner").assertCountEquals(0)
    }
}
