package com.zcode.android.features.pairing

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.features.pairing.ui.OnboardingPager
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H1.5 (Phase 6B) pins: the short post-pairing pass ("what your phone can do") —
 * four skippable pages over the features that already exist (approvals, live work,
 * automations, usage); Skip and Get-started both finish exactly once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class OnboardingPagerUiTest {

    @get:Rule
    val rule = createComposeRule()

    private val finished = mutableListOf<Int>()

    private fun setContent() {
        finished.clear()
        rule.setContent {
            ZCodeTheme {
                OnboardingPager(onFinish = { finished += 1 })
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun `four pages render with skip always available`() {
        setContent()
        rule.onNodeWithTag("onboarding_page_0").assertIsDisplayed()
        rule.onNodeWithText("Approve from anywhere").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_skip").assertIsDisplayed()
        rule.onAllNodesWithTag("onboarding_done").assertCountEquals(0, "the last-page CTA is not on page 1")
    }

    @Test
    fun `next walks the pages and get-started finishes once`() {
        setContent()
        rule.onNodeWithTag("onboarding_next").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Watch the work live").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_next").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Automations and idle-time tasks").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_next").performClick(); rule.waitForIdle()
        rule.onNodeWithText("Usage and diagnostics").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_done").assertIsDisplayed()
        rule.onNodeWithTag("onboarding_done").performClick(); rule.waitForIdle()
        assertEquals(listOf(1), finished)
    }

    @Test
    fun `skip finishes immediately`() {
        setContent()
        rule.onNodeWithTag("onboarding_skip").performClick(); rule.waitForIdle()
        assertEquals(listOf(1), finished)
    }
}
