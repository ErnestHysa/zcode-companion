package com.zcode.android.core.ui.components

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * B3-chrome (Phase 2C, P1): the ONE header component — pinned: a single title node,
 * a back affordance with the shared `action_back` description and per-screen tag,
 * and SCROLL ELEVATION (the pinned bar tints to the J5-mapped surfaceContainer while
 * content scrolls under it — pixel-verified).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ZCodeTopBarTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `one title, back semantics, subtitle slot`() {
        rule.setContent {
            ZCodeTheme {
                Scaffold(topBar = {
                    ZCodeTopBar(
                        title = "Workspace files",
                        subtitle = "MockBook",
                        onBack = {},
                        backTestTag = "usage_back",
                    )
                }) { inner -> Text("content", Modifier.testTag("content")) }
            }
        }
        rule.onAllNodesWithText("Workspace files").assertCountEquals(1)
        rule.onAllNodesWithText("MockBook").assertCountEquals(1)
        rule.onNodeWithTag("usage_back")
            .assertExists()
            .assertHasClickAction()
        rule.onNodeWithContentDescription("Back").assertExists()
    }

    // Night: the designed dark pair makes the elevation legible in pixels (light's
    // bg F4F6FA vs container F0F3FA differ by 4 units — not pixel-discriminable).
    @Config(sdk = [30], qualifiers = "w411dp-h891dp-night")
    @Test
    fun `pixel pin - the pinned bar elevates when content scrolls under it`() {
        var container = 0
        var scrolled = 0
        var behavior: androidx.compose.material3.TopAppBarScrollBehavior? = null
        rule.setContent {
            ZCodeTheme {
                container = MaterialTheme.colorScheme.background.toArgb()
                scrolled = MaterialTheme.colorScheme.surfaceContainer.toArgb()
                val scroll = rememberZCodeTopBarScroll()
                behavior = scroll
                Scaffold(
                    modifier = Modifier.nestedScroll(scroll.nestedScrollConnection),
                    topBar = {
                        ZCodeTopBar(title = "Elevation", onBack = null, scrollBehavior = scroll, modifier = Modifier.testTag("bar"))
                    },
                ) { inner ->
                    LazyColumn(Modifier.testTag("list")) {
                        items(count = 200, key = { it }) { i ->
                            Text("row $i", modifier = Modifier.testTag("row-$i"))
                        }
                    }
                }
            }
        }
        val restX = 4
        val restY = 16
        val before = rule.onNodeWithTag("bar").captureToImage().toPixelMap()[restX, restY].toArgb()
        assertEquals("at rest the bar sits on the app background", container, before)
        // A real swipe: the scroll behavior listens to NESTED scroll, which a programmatic
        // scrollToIndex never dispatches.
        rule.onNodeWithTag("list").performTouchInput { swipeUp() }
        rule.waitForIdle()
        println("PROBE contentOffset=${behavior!!.state.contentOffset} overlapped=${behavior!!.state.overlappedFraction}")
        rule.onAllNodesWithTag("row-0").assertCountEquals(0)
        val after = rule.onNodeWithTag("bar").captureToImage().toPixelMap()[restX, restY].toArgb()
        assertNotEquals("the bar must tint when content scrolls under it", before, after)
        assertEquals("the elevated color is the designed surfaceContainer (J5 pair)", scrolled, after)
    }
}
