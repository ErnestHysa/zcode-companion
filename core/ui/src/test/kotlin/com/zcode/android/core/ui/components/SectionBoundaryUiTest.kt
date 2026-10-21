package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * L7 (Phase 6C) pins: a section whose render-preparation crashes renders the
 * section-scoped error card (desktop `appError.*` copy) with the throwable's message
 * as the detail and Retry wired; the crash reaches the onCrash seam (the app wires it
 * to the diagnostics store); SIBLING sections are untouched; retry with a recovered
 * preparation brings the section back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class SectionBoundaryUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val crashes = mutableListOf<Throwable>()

    private fun setContent(failFirstPrepare: Boolean) {
        crashes.clear()
        var calls = 0
        rule.setContent {
            ZCodeTheme {
                Column {
                    SectionBoundary(
                        key = "crashing",
                        prepare = {
                            calls += 1
                            if (failFirstPrepare && calls == 1) throw IllegalStateException("bad projection")
                            "rendered"
                        },
                        onCrash = { crashes += it },
                        modifier = Modifier.testTag("boundary_a"),
                    ) { value ->
                        androidx.compose.material3.Text(
                            "section A: $value",
                            modifier = Modifier.testTag("content_a"),
                        )
                    }
                    SectionBoundary(
                        key = "healthy",
                        prepare = { "fine" },
                        onCrash = { crashes += it },
                        modifier = Modifier.testTag("boundary_b"),
                    ) { value ->
                        androidx.compose.material3.Text(
                            "section B: $value",
                            modifier = Modifier.testTag("content_b"),
                        )
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `a crashing section renders the error card - its sibling keeps rendering`() {
        setContent(failFirstPrepare = true)
        rule.onNodeWithTag("boundary_a").assertExistsFor("the crashing section renders (the boundary card)")
        rule.onNodeWithText("This area broke").assertExistsFor("the desktop appError title")
        rule.onNodeWithText("Other areas keep working — retry this section.").assertExistsFor("the body")
        rule.onNodeWithText("bad projection").assertExistsFor("the throwable's message is the detail")
        rule.onAllNodesWithTag("content_b").assertCountEquals(1)
        org.junit.Assert.assertEquals(0, rule.onAllNodesWithTag("content_a").fetchSemanticsNodes().size)
        assertEquals("the crash reached the diagnostics seam", 1, crashes.size)
        assertTrue("the crash is the projection failure", crashes.single() is IllegalStateException)
    }

    @Test
    fun `retry with a recovered preparation brings the section back`() {
        setContent(failFirstPrepare = true)
        val retry = rule.onAllNodesWithTag("section_boundary_retry").fetchSemanticsNodes()
        org.junit.Assert.assertTrue("the retry affordance renders", retry.isNotEmpty())
        rule.onAllNodesWithTag("section_boundary_retry").onFirst().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("content_a").assertExistsFor("the section recovered")
        rule.onAllNodesWithTag("content_b").assertCountEquals(1)
    }

    @Test
    fun `a healthy section never shows the boundary`() {
        setContent(failFirstPrepare = false)
        rule.onAllNodesWithTag("content_a").assertCountEquals(1)
        rule.onAllNodesWithTag("content_b").assertCountEquals(1)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertExistsFor(message: String): androidx.compose.ui.test.SemanticsNodeInteraction {
    try {
        assertExists()
    } catch (e: AssertionError) {
        throw AssertionError("$message — $e")
    }
    return this
}
