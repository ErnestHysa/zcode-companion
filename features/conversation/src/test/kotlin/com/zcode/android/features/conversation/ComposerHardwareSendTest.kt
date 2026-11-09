package com.zcode.android.features.conversation

import androidx.compose.ui.input.key.KeyEvent
import com.zcode.android.features.conversation.ui.handleComposerSendKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * N-BE-3 (vc52 Phase 10): no hardware-keyboard send — the IME Send action works
 * (GAP-A9) but a hardware keyboard's Enter inserts a newline and there is no
 * Ctrl/Cmd+Enter, so a DeX/tablet/Chromebook keyboard cannot send (the desktop's
 * `composerSend` binding). The fix ports the binding: Ctrl/Cmd+Enter dispatches,
 * plain Enter keeps its newline. The wiring is one onPreviewKeyEvent on the composer
 * field delegating to this handler (pinned here with real native KeyEvents); the
 * LIVE face is the exit-gate leg (`adb shell input keycombination 113 66` — a real
 * hardware-shaped Ctrl+Enter through the input pipeline).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ComposerHardwareSendTest {

    private fun nativeEvent(code: Int, action: Int, meta: Int): KeyEvent =
        KeyEvent(android.view.KeyEvent(0L, 0L, action, code, 0, meta))

    private val DOWN = android.view.KeyEvent.ACTION_DOWN
    private val UP = android.view.KeyEvent.ACTION_UP
    private val ENTER = android.view.KeyEvent.KEYCODE_ENTER
    private val NUMPAD = android.view.KeyEvent.KEYCODE_NUMPAD_ENTER
    private val CTRL = android.view.KeyEvent.META_CTRL_ON
    private val META = android.view.KeyEvent.META_META_ON

    @Test
    fun `ctrl-enter keydown dispatches and consumes`() {
        var sent = 0
        val consumed = handleComposerSendKey(nativeEvent(ENTER, DOWN, CTRL)) { sent += 1 }
        assertTrue("the handler consumes the binding so the field never inserts a newline", consumed)
        assertEquals(1, sent)
    }

    @Test
    fun `meta-enter keydown dispatches (the Cmd binding)`() {
        var sent = 0
        val consumed = handleComposerSendKey(nativeEvent(ENTER, DOWN, META)) { sent += 1 }
        assertTrue(consumed)
        assertEquals(1, sent)
    }

    @Test
    fun `plain enter keydown is NOT consumed - the newline is kept`() {
        var sent = 0
        val consumed = handleComposerSendKey(nativeEvent(ENTER, DOWN, 0)) { sent += 1 }
        assertFalse(consumed)
        assertEquals(0, sent)
    }

    @Test
    fun `a ctrl key that is not enter does nothing`() {
        var sent = 0
        val consumed = handleComposerSendKey(
            nativeEvent(android.view.KeyEvent.KEYCODE_A, DOWN, CTRL),
        ) { sent += 1 }
        assertFalse(consumed)
        assertEquals(0, sent)
    }

    @Test
    fun `ctrl-enter keyUP never re-dispatches (no double send)`() {
        var sent = 0
        val consumed = handleComposerSendKey(nativeEvent(ENTER, UP, CTRL)) { sent += 1 }
        assertFalse(consumed)
        assertEquals(0, sent)
    }

    @Test
    fun `numpad-enter with ctrl dispatches (the DeX numpad)`() {
        var sent = 0
        val consumed = handleComposerSendKey(nativeEvent(NUMPAD, DOWN, CTRL)) { sent += 1 }
        assertTrue(consumed)
        assertEquals(1, sent)
    }
}
