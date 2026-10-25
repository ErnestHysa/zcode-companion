package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.domain.state.TaskStateMachine
import com.zcode.android.features.conversation.ui.ConversationScreen
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * U3 (verification pass 2, 2026-10-21, live-found on the rig): the header's Share chip did
 * nothing. `ConversationScreen` declares `onShareTranscript` and the app wires the system
 * share sheet into it (ZCodeApp), but the screen never forwarded it to `Header`, whose
 * default `{}` won — the Phase-5C share-out (A3.16) was unreachable from the phone. The
 * F5-WIRE class, one hop lower. Pinned at the screen boundary: the chip's click must reach
 * the screen's callback.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ShareChipWiringTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun shareChip_reachesTheScreenCallback() {
        var shared = false
        rule.setContent {
            ZCodeTheme {
                ConversationScreen(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = TaskStateMachine.State.IDLE,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                    ),
                    phase = ConnectionPhase.CONNECTED,
                    onBack = {},
                    onLoadOlder = { _, _ -> },
                    onDismissHistoryNotice = {},
                    onRetryOlder = {},
                    onDraftChange = {},
                    onSend = {},
                    onStop = {},
                    onRetrySend = {},
                    onDismissSendNotice = {},
                    onAnswer = { _, _, _, _ -> },
                    onSendQueuedNow = {},
                    onEditQueueItem = { _, _ -> },
                    onShareTranscript = { shared = true },
                )
            }
        }
        rule.onNodeWithTag("conv_share").performClick()
        rule.waitForIdle()
        assertTrue("the Share chip must invoke the screen's onShareTranscript (it was a dead default)", shared)
    }
}
