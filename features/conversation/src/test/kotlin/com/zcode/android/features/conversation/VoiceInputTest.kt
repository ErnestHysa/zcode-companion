package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ComposerBar
import com.zcode.android.features.conversation.voice.VoiceInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * §43.2-11.5 voice input experiment: the composer affordance is availability-
 * gated, drives the draft through the injected port, and surfaces the listening
 * state. The port abstraction keeps the UI testable without a recognizer service.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class VoiceInputTest {

    @get:Rule
    val rule = createComposeRule()

    private class FakeVoice : VoiceInputPort {
        override var available = true
        // B2.2 contract: `listening` MUST be observable state — the composer's tint
        // and "Listening…" label read it in composition.
        @androidx.compose.runtime.getValue
        @androidx.compose.runtime.setValue
        override var listening by androidx.compose.runtime.mutableStateOf(false)
        var onPartial: ((String) -> Unit)? = null
        var onFinal: ((String) -> Unit)? = null
        var onError: ((Int) -> Unit)? = null
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Int) -> Unit) {
            listening = true
            this.onPartial = onPartial
            this.onFinal = onFinal
            this.onError = onError
        }

        override fun stop() {
            listening = false
        }
    }

    private fun assertExistsFallback(tag: String) {
        org.junit.Assert.assertTrue(
            "expected node with tag $tag",
            rule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty(),
        )
    }

    private fun state(draft: String = "") = ConversationUiState(
        rows = emptyList(),
        loading = false,
        taskState = com.zcode.android.domain.state.TaskStateMachine.State.RUNNING,
        phase = ConnectionPhase.CONNECTED,
        draft = draft,
        sessionState = null,
    )

    @Test
    fun voiceButton_hiddenWhenUnavailable() {
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {}, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = com.zcode.android.features.conversation.voice.NoVoiceInput(),
                )
            }
        }
        rule.waitForIdle()
        rule.onAllNodesWithTag("composer_voice").assertCountEquals(0)
    }

    @Test
    fun voiceButton_startsListening_andFinalResultAppendsDraft() {
        val fake = FakeVoice()
        var draft = ""
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = { draft = it }, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = fake,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_voice").performClick()
        rule.runOnIdle { assertTrue(fake.listening) }
        // The recognizer lands a final result mid-listening — draft appends.
        rule.runOnIdle { fake.onFinal?.invoke("fix the login flow") }
        rule.runOnIdle { assertEquals("fix the login flow", draft) }
    }

    @Test
    fun voiceError_surfacesHonestCopy_notSilent() {
        val fake = FakeVoice()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {}, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = fake,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_voice").performClick()
        rule.runOnIdle { fake.onError?.invoke(6 /* ERROR_INSUFFICIENT_PERMISSIONS */) }
        rule.onNodeWithTag("composer_voice_error").assertExists()
    }

    @Test
    fun `listening state recomposes - the label and tint follow the port`() {
        val fake = FakeVoice()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {}, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = fake,
                )
            }
        }
        rule.waitForIdle()
        // RED (B2.2): the port's plain getter never recomposed the label/tint.
        rule.onNodeWithTag("composer_voice").performClick()
        rule.waitForIdle()
        assertExistsFallback("composer_voice_listening")

        // A terminal callback flips the port off — the label must go away.
        rule.runOnIdle { fake.onFinal?.invoke("done") }
        rule.waitForIdle()
        rule.onAllNodesWithTag("composer_voice_listening").assertCountEquals(0)
    }

    @Test
    fun `onFinal merges into the CURRENT draft - text typed while listening is kept`() {
        val fake = FakeVoice()
        val draft = androidx.compose.runtime.mutableStateOf("")
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(draft = draft.value),
                    onDraftChange = { draft.value = it }, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = fake,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_voice").performClick()
        rule.waitForIdle()

        // The user TYPES while listening (RED: the callback captured the click-time
        // draft and clobbered it).
        rule.runOnIdle { draft.value = "typed while listening " }
        rule.waitForIdle()
        rule.runOnIdle { fake.onFinal?.invoke("and spoken") }
        rule.waitForIdle()

        org.junit.Assert.assertEquals(
            "dictation must merge into the CURRENT field value",
            "typed while listening and spoken",
            draft.value,
        )
    }

    @Test
    fun voiceButton_visibleByDefault_whenPlatformReportsAvailable_viaOverride() {
        val fake = FakeVoice()
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = state(),
                    onDraftChange = {}, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = fake,
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("composer_voice").assertExists()
        rule.onAllNodesWithTag("composer_voice_listening").assertCountEquals(0)
    }
}
