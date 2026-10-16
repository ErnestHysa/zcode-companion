package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * M2 (gaps-and-polish Phase 2G, P1): zero width discipline — prose ran 915dp in
 * landscape. The window width class is provided at the app root; reader content caps
 * at the 600dp reading ceiling and centers on wider windows.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w1200dp-h900dp")
class WidthDisciplineTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `reader content caps at 600dp on a wide window`() {
        rule.setContent {
            ZCodeTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthSizeClass.Expanded) {
                    Box(Modifier.fillMaxWidth()) {
                        Box(Modifier.readerWidth().size(width = 1200.dp, height = 40.dp).testTag("reader"))
                    }
                }
            }
        }
        val density = rule.density.density
        val w = rule.onNodeWithTag("reader").fetchSemanticsNode2().boundsInRoot.width / density
        assertTrue("reader width ${w}dp must cap at ${ReaderMaxWidth.value}dp", w <= ReaderMaxWidth.value + 1f)
    }

    @Test
    fun `below the ceiling the reader is a no-op`() {
        rule.setContent {
            ZCodeTheme {
                CompositionLocalProvider(LocalWindowWidthClass provides WindowWidthSizeClass.Compact) {
                    androidx.compose.foundation.layout.Column {
                        Box(Modifier.fillMaxWidth().testTag("host")) {
                            Box(Modifier.readerWidth().fillMaxWidth().testTag("reader"))
                        }
                    }
                }
            }
        }
        assertEquals(
            rule.onNodeWithTag("host").fetchSemanticsNode2().boundsInRoot.width,
            rule.onNodeWithTag("reader").fetchSemanticsNode2().boundsInRoot.width,
            1f,
        )
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.fetchSemanticsNode2() = fetchSemanticsNode()
