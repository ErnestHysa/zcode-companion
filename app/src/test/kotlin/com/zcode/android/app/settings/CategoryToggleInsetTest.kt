package com.zcode.android.app.settings

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/**
 * U5 (verification pass 2, 2026-10-21, live-found on the rig): the three per-category
 * notification switches ("Approval requests" / "Task finished / failed" / "Connection
 * lost") rendered flush against BOTH screen edges — labels at x=0, the switch's track
 * touching the right edge — while every neighbouring Settings row carries the 16dp list
 * inset. `CategoryToggle` padded vertically only. Pinned on the real SettingsScreen: each
 * toggle row keeps the list's horizontal inset on both sides.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class CategoryToggleInsetTest {

    class HostActivity : FragmentActivity()

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `notification category toggles keep the 16dp list inset on both edges`() {
        val activity = Robolectric.setupActivity(HostActivity::class.java)
        val container = AppContainer(activity)
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            ZCodeTheme {
                SettingsScreen(
                    container = container,
                    endpointName = "MockBook",
                    desktopVersion = "3.14.5",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val inset = 16f * density
        for (tag in listOf("settings_notif_cat_approvals", "settings_notif_cat_tasks", "settings_notif_cat_connection")) {
            rule.onNodeWithTag(tag).performScrollTo()
            rule.waitForIdle()
            val row = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag starts at x=${row.left}px — flush against the left edge (inset ${inset}px)", row.left >= inset - 1f)
            assertTrue("$tag ends at x=${row.right}px of ${root.right}px — the switch touches the right edge", row.right <= root.right - inset + 1f)
        }
    }
}
