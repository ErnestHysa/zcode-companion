package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AutoResolution
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.features.conversation.ui.ApprovalCard
import com.zcode.android.features.conversation.ui.UserInputCard
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G77 (vc52 Phase 7): the desktop's auto-resolution fuse on the phone's cards —
 * the pass-36 finding was that the phone decodes `pendingInteractions` but shows
 * NONE of the fuse. Pins: a live countdown ticks on the card (injected clock for
 * determinism), an expired fuse renders the auto-continued face with NO action
 * buttons, "Keep waiting" is state-aware (absent in hiddenGrace/snoozed/expired),
 * and the snoozed state says so. The notification chronometer is pinned in
 * AndroidNotificationSinkTest; the decode in AutoResolutionDecodeTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AutoResolutionCardUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun interaction(auto: AutoResolution?) = PendingInteraction(
        interactionId = "fuse-1",
        kind = PendingInteraction.Kind.PERMISSION,
        anchorRowId = null,
        createdAtMs = 1_000L,
        permission = PendingInteraction.PermissionPayload(
            toolCallId = null,
            toolName = "Bash",
            summary = "Run the build?",
            detail = buildJsonObject { put("command", "echo synthetic") },
            freeText = null,
            options = listOf(PermissionOption("allowOnce"), PermissionOption("deny")),
            raw = buildJsonObject { },
        ),
        userInput = null,
        autoResolution = auto,
        raw = buildJsonObject { put("interactionId", "fuse-1") },
    )

    private val now = 1_794_000_000_000L

    private fun setContent(interaction: PendingInteraction, snooze: Boolean = true) {
        rule.setContent {
            ZCodeTheme {
                ApprovalCard(
                    interaction = interaction,
                    busy = false,
                    onAnswer = { _, _ -> },
                    onSnooze = if (snooze) ({}) else null,
                    clockNow = { now },
                )
            }
        }
    }

    @Test
    fun `a live fuse renders the countdown and Keep waiting`() {
        val live = AutoResolution(
            state = AutoResolution.State.VISIBLE_COUNTDOWN,
            startedAtMs = now - 1_000,
            visibleAtMs = now - 1_000,
            deadlineAtMs = now + 42_500, // 42.5s → 42 whole seconds
            snoozedAtMs = null,
        )
        setContent(interaction(live))
        rule.onNodeWithText("Auto-continues in 42s").assertIsDisplayed()
        rule.onNodeWithTag("interaction_countdown_fuse-1").assertIsDisplayed()
        rule.onNodeWithTag("interaction_snooze_fuse-1").assertIsDisplayed()
        // The actions are still live while the fuse runs.
        rule.onAllNodesWithTag("approval_allowOnce").assertCountEquals(1)
    }

    @Test
    fun `an expired fuse renders the auto-continued face with NO action buttons`() {
        val expired = AutoResolution(
            state = AutoResolution.State.VISIBLE_COUNTDOWN,
            startedAtMs = now - 400_000,
            visibleAtMs = now - 400_000,
            deadlineAtMs = now - 60_000, // fired a minute ago
            snoozedAtMs = null,
        )
        setContent(interaction(expired))
        rule.onNodeWithText("No answer; continued automatically").assertIsDisplayed()
        rule.onNodeWithTag("interaction_expired_fuse-1").assertIsDisplayed()
        rule.onAllNodesWithTag("approval_allowOnce").assertCountEquals(0)
        rule.onAllNodesWithTag("approval_deny").assertCountEquals(0)
        rule.onAllNodesWithTag("interaction_snooze_fuse-1").assertCountEquals(0)
    }

    @Test
    fun `hiddenGrace shows nothing - no countdown, no Keep waiting (the desktop hides it too)`() {
        val grace = AutoResolution(
            state = AutoResolution.State.HIDDEN_GRACE,
            startedAtMs = now - 1_000,
            visibleAtMs = now + 30_000,
            deadlineAtMs = now + 300_000,
            snoozedAtMs = null,
        )
        setContent(interaction(grace))
        rule.onAllNodesWithTag("interaction_countdown_fuse-1").assertCountEquals(0)
        rule.onAllNodesWithTag("interaction_expired_fuse-1").assertCountEquals(0)
        rule.onAllNodesWithTag("interaction_snooze_fuse-1").assertCountEquals(0)
        // The card itself is still actionable.
        rule.onAllNodesWithTag("approval_allowOnce").assertCountEquals(1)
    }

    @Test
    fun `a snoozed fuse says so - and Keep waiting is done`() {
        val snoozed = AutoResolution(
            state = AutoResolution.State.SNOOZED,
            startedAtMs = now - 100_000,
            visibleAtMs = null,
            deadlineAtMs = null,
            snoozedAtMs = now - 1_000,
        )
        setContent(interaction(snoozed))
        rule.onNodeWithTag("interaction_snoozed_fuse-1").assertIsDisplayed()
        rule.onAllNodesWithTag("interaction_snooze_fuse-1").assertCountEquals(0)
        rule.onAllNodesWithTag("interaction_countdown_fuse-1").assertCountEquals(0)
        rule.onAllNodesWithTag("approval_allowOnce").assertCountEquals(1)
    }

    @Test
    fun `the question card respects the same faces`() {
        val expired = AutoResolution(
            state = AutoResolution.State.VISIBLE_COUNTDOWN,
            startedAtMs = now - 400_000,
            visibleAtMs = now - 400_000,
            deadlineAtMs = now - 60_000,
            snoozedAtMs = null,
        )
        val question = PendingInteraction(
            interactionId = "q-fuse",
            kind = PendingInteraction.Kind.USER_INPUT,
            anchorRowId = null,
            createdAtMs = 1_000L,
            permission = null,
            userInput = PendingInteraction.UserInputPayload(
                prompt = "Which channel?",
                freeText = false,
                sensitive = null,
                options = listOf(PermissionOption("tag"), PermissionOption("branch")),
                questions = emptyList(),
                toolName = null,
                raw = buildJsonObject { },
            ),
            autoResolution = expired,
            raw = buildJsonObject { },
        )
        rule.setContent {
            ZCodeTheme {
                UserInputCard(
                    interaction = question,
                    busy = false,
                    onAnswer = { _, _ -> },
                    onSnooze = {},
                    clockNow = { now },
                )
            }
        }
        rule.onNodeWithText("No answer; continued automatically").assertIsDisplayed()
        rule.onAllNodesWithTag("userinput_option_tag").assertCountEquals(0)
        rule.onAllNodesWithTag("interaction_snooze_q-fuse").assertCountEquals(0)
    }

    @Test
    fun `countdownSecondsRemaining - whole seconds, never negative`() {
        assertEquals(42L, com.zcode.android.features.conversation.ui.countdownSecondsRemaining(now + 42_500, now))
        assertEquals(0L, com.zcode.android.features.conversation.ui.countdownSecondsRemaining(now - 1, now))
        assertEquals(0L, com.zcode.android.features.conversation.ui.countdownSecondsRemaining(now, now))
        assertEquals(1L, com.zcode.android.features.conversation.ui.countdownSecondsRemaining(now + 1_000, now))
    }
}
