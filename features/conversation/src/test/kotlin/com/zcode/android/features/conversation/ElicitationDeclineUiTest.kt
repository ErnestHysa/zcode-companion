package com.zcode.android.features.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.PendingInteraction
import com.zcode.android.domain.model.UserInputRequest
import com.zcode.android.features.conversation.ui.UserInputCard
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * A3.14 (Phase 5B) UI pins: the question card carries the decline affordance ("Skip —
 * continue without my answer") beside the answers; a tap fires the decline callback ONCE
 * (never silently dropped); a card without the decline affordance (null callback) keeps
 * the Phase-7 pin shape.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ElicitationDeclineUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val payload: PendingInteraction = PendingInteraction.decode(
        Json.parseToJsonElement(
            """{"interactionId":"q-1","kind":"userInput","anchorRowId":null,"createdAt":1,
                "payload":{"kind":"userInput","prompt":"How should I ship this?","questions":[
                  {"question":"Which channel?","header":"Channel","multiSelect":false,
                   "options":[{"value":"tag","label":"Tag the release"},{"value":"branch","label":"Cut a branch"}]}]}}""",
        ).jsonObject,
    )

    private fun card(onDecline: (() -> Unit)? = null) {
        rule.setContent {
            ZCodeTheme {
                UserInputCard(
                    interaction = payload,
                    busy = false,
                    onAnswer = { _, _ -> },
                    onAnswerQuestions = { },
                    onDecline = onDecline,
                )
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `the decline affordance renders beside the answers and fires once`() {
        val declined = mutableListOf<String>()
        card(onDecline = { declined += "q-1" })
        rule.onNodeWithTag("userinput_decline").assertIsDisplayed()
        rule.onNodeWithText("Skip — continue without my answer").assertIsDisplayed()
        rule.onNodeWithTag("userinput_decline").performClick(); rule.waitForIdle()
        assertEquals(listOf("q-1"), declined)
    }

    @Test
    fun `a card without the decline callback keeps the Phase-7 shape`() {
        card(onDecline = null)
        rule.onAllNodesWithTag("userinput_decline").assertCountEquals(0)
    }
}
