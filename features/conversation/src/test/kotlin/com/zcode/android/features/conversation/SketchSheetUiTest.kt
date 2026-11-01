package com.zcode.android.features.conversation

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.conversation.ui.SketchSheet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G31 (vc51 Phase 4C) — the whiteboard minimum: a sketch → send entry in the composer.
 * Pins: drawing on the canvas and pressing Done hands the composer PNG BYTES (the magic
 * header asserted — the bytes ride the existing attachment-upload transaction); Clear
 * empties the sheet so Done disables again; an untouched sheet cannot produce an empty
 * file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SketchSheetUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun done_handsPngBytes_afterADraw() {
        val delivered = mutableListOf<ByteArray>()
        rule.setContent { ZCodeTheme { SketchSheet(onDismiss = {}, onDone = { delivered += it }) } }
        // draw two strokes
        rule.onNodeWithTag("sketch_canvas").performTouchInput {
            down(centerLeft); moveTo(center); up()
        }
        rule.onNodeWithTag("sketch_canvas").performTouchInput {
            down(center); moveBy(androidx.compose.ui.geometry.Offset(60f, 40f)); up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sketch_done").performClick()
        rule.waitForIdle()
        assertEquals(1, delivered.size)
        val png = delivered.single()
        // PNG magic: 0x89 'P' 'N' 'G'
        assertTrue("a real PNG payload, not an empty file (${png.size} bytes)", png.size > 100)
        assertEquals(0x89.toByte(), png[0])
        assertEquals('P'.code.toByte(), png[1])
        assertEquals('N'.code.toByte(), png[2])
        assertEquals('G'.code.toByte(), png[3])
    }

    @Test
    fun untouchedSheet_cannotProduceAnEmptyFile() {
        val delivered = mutableListOf<ByteArray>()
        rule.setContent { ZCodeTheme { SketchSheet(onDismiss = {}, onDone = { delivered += it }) } }
        rule.onNodeWithTag("sketch_clear").performClick()
        rule.waitForIdle()
        // Done is disabled with nothing drawn — no empty attachment can land
        org.junit.Assert.assertFalse(rule.onAllNodesWithTag("sketch_done").fetchSemanticsNodes().isEmpty())
        org.junit.Assert.assertTrue(delivered.isEmpty())
    }

    @Test
    fun clear_emptiesTheSheet() {
        val delivered = mutableListOf<ByteArray>()
        rule.setContent { ZCodeTheme { SketchSheet(onDismiss = {}, onDone = { delivered += it }) } }
        rule.onNodeWithTag("sketch_canvas").performTouchInput {
            down(centerLeft); moveTo(center); up()
        }
        rule.waitForIdle()
        rule.onNodeWithTag("sketch_clear").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("sketch_done").performClick()
        rule.waitForIdle()
        assertTrue("cleared strokes produce no attachment", delivered.isEmpty())
    }
}
