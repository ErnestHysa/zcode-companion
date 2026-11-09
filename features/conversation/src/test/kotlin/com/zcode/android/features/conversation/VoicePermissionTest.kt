package com.zcode.android.features.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.conversation.ui.ComposerBar
import com.zcode.android.features.conversation.voice.VoiceInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-34-D (vc52 Phase 8, P2) — voice dictation was dead on EVERY device and its error
 * blamed the device. Dictate showed whenever a recognizer existed; `startListening` was
 * rejected by the recognition service ("received from a caller without permission
 * android.permission.RECORD_AUDIO") because the app neither declared nor requested the
 * microphone, and onError rendered "Voice input isn't available on this device" — false.
 *
 * These pins drive the REAL permission launcher on a real ComponentActivity: the grant is
 * asked AT the Dictate tap; a denial (or the recognizer's own ERROR_INSUFFICIENT_PERMISSIONS)
 * names the microphone with a Settings way out — never "not available on this device".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class VoicePermissionTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** A port with the platform recognizer's real dependency: it needs RECORD_AUDIO. */
    private class MicVoice : VoiceInputPort {
        override val available = true
        override val requiresMicPermission = true
        override var listening by androidx.compose.runtime.mutableStateOf(false)
        var starts = 0
        var onError: ((Int) -> Unit)? = null
        override fun start(onPartial: (String) -> Unit, onFinal: (String) -> Unit, onError: (Int) -> Unit) {
            starts++
            listening = true
            this.onError = onError
        }
        override fun stop() { listening = false }
    }

    private val unavailableCopy = "Voice input isn't available on this device"
    private val permissionCopy = "Microphone permission needed for dictation"

    private fun compose(voice: VoiceInputPort) {
        rule.setContent {
            ZCodeTheme {
                ComposerBar(
                    state = ConversationUiState(
                        rows = emptyList(),
                        loading = false,
                        taskState = com.zcode.android.domain.state.TaskStateMachine.State.RUNNING,
                        phase = ConnectionPhase.CONNECTED,
                        draft = "",
                        sessionState = null,
                    ),
                    onDraftChange = {}, onSend = {}, onStop = {}, onRetrySend = {},
                    onDismissNotice = {},
                    voiceInputOverride = voice,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun tapDictate() {
        rule.onNodeWithTag("composer_attach").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("composer_voice_menu").performClick()
        rule.waitForIdle()
    }

    private fun answerPermission(granted: Boolean) {
        val request = shadowOf(rule.activity).lastRequestedPermission
        assertNotNull("Dictate never asked for the microphone", request)
        assertEquals(listOf(Manifest.permission.RECORD_AUDIO), request!!.requestedPermissions.toList())
        if (granted) shadowOf(rule.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        rule.runOnIdle {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
            )
        }
        rule.waitForIdle()
    }

    @Test
    fun `F-34-D - Dictate without the grant asks for the microphone at the tap - it never starts blind`() {
        val voice = MicVoice()
        compose(voice)
        tapDictate()
        assertEquals("the recognizer was started without RECORD_AUDIO (the service rejects it)", 0, voice.starts)
        assertNotNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun `F-34-D - a denied grant names the microphone with a Settings link - never blames the device`() {
        val voice = MicVoice()
        compose(voice)
        tapDictate()
        answerPermission(granted = false)
        rule.onNodeWithText(permissionCopy).assertExists()
        rule.onNodeWithTag("composer_voice_settings").assertExists()
        rule.onAllNodesWithText(unavailableCopy).assertCountEquals(0)
        assertFalse(voice.listening)
    }

    @Test
    fun `F-34-D - granting at the prompt starts the dictation`() {
        val voice = MicVoice()
        compose(voice)
        tapDictate()
        answerPermission(granted = true)
        assertEquals(1, voice.starts)
        assertTrue(voice.listening)
    }

    @Test
    fun `F-34-D - an already-granted microphone starts directly without a prompt`() {
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val voice = MicVoice()
        compose(voice)
        tapDictate()
        assertEquals(1, voice.starts)
        assertNull("no prompt when the grant already exists", shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun `F-34-D - the recognizer's own permission refusal maps to the permission copy`() {
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        val voice = MicVoice()
        compose(voice)
        tapDictate()
        rule.runOnIdle { voice.onError?.invoke(VoiceInputPort.ERROR_INSUFFICIENT_PERMISSIONS) }
        rule.waitForIdle()
        rule.onNodeWithText(permissionCopy).assertExists()
        rule.onAllNodesWithText(unavailableCopy).assertCountEquals(0)
    }

    @Test
    fun `F-34-D - the platform adapter declares its microphone dependency`() {
        val platform = com.zcode.android.features.conversation.voice.PlatformVoiceInput(rule.activity)
        assertTrue("the platform recognizer needs RECORD_AUDIO", platform.requiresMicPermission)
        assertEquals(android.speech.SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS, VoiceInputPort.ERROR_INSUFFICIENT_PERMISSIONS)
    }
}
