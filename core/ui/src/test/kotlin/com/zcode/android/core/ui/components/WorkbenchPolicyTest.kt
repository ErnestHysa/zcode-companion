package com.zcode.android.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G61 (vc52 Phase 13): no adaptive large-screen layout — at 1280 dp everything
 * stretched full width and no list-detail pane existed for tablets/foldables/DeX.
 *
 * Pins: the breakpoint is ≥600 dp exactly (411 dp phones stay single-pane); the
 * scaffold renders BOTH panes with the divider; a null detail renders the honest
 * empty pane (never a blank hole); the empty pane's open affordance fires.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WorkbenchPolicyTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the breakpoint is 600dp inclusive`() {
        assertFalse("phone (411 dp) stays single-pane", WorkbenchPolicy.useListDetail(411))
        assertFalse("just under the breakpoint", WorkbenchPolicy.useListDetail(599))
        assertTrue("at the breakpoint", WorkbenchPolicy.useListDetail(600))
        assertTrue("tablet/DeX (1280 dp)", WorkbenchPolicy.useListDetail(1280))
        assertTrue("foldable unfolded", WorkbenchPolicy.useListDetail(840))
    }

    @Test
    fun `the scaffold renders both panes and the divider`() {
        rule.setContent {
            ZCodeTheme {
                WorkbenchPolicy.WorkbenchScaffold(
                    list = { Box(Modifier.fillMaxSize().testTag("pane_list")) },
                    detail = { Box(Modifier.fillMaxSize().testTag("pane_detail")) },
                )
            }
        }
        rule.onNodeWithTag("pane_list").assertExists()
        rule.onNodeWithTag("pane_detail").assertExists()
        rule.onNodeWithTag("workbench_divider").assertExists()
    }

    @Test
    fun `a null detail renders the honest empty pane whose open affordance fires`() {
        var opened = false
        rule.setContent {
            ZCodeTheme {
                WorkbenchPolicy.WorkbenchScaffold(
                    list = { Box(Modifier.fillMaxSize().testTag("pane_list")) },
                    detail = null,
                    onOpenFromEmpty = { opened = true },
                )
            }
        }
        rule.onNodeWithTag("workbench_detail_empty").assertExists()
        rule.onNodeWithTag("workbench_detail_empty").performClick()
        assertTrue(opened)
    }
}
