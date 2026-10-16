package com.zcode.android.core.ui.components

import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * B3-states (Phase 2C): the shared state family's own pins — one pin per state.
 * Adoption is gated repo-wide by StatesDisciplineTest.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class StatesTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `empty state renders icon, title, message and its action`() {
        rule.setContent {
            ZCodeTheme { EmptyState(title = "No plugins", message = "Nothing installed yet", action = { androidx.compose.material3.Text("Browse") }) }
        }
        rule.onNodeWithText("No plugins").assertExists()
        rule.onNodeWithText("Nothing installed yet").assertExists()
        rule.onNodeWithText("Browse").assertExists()
    }

    @Test
    fun `error state renders title, detail and a retry that fires`() {
        var retries = 0
        rule.setContent {
            ZCodeTheme {
                ErrorState(
                    title = "Catalog unavailable",
                    detail = "relay error code=INTERNAL",
                    onRetry = { retries += 1 },
                    retryTestTag = "viewer_retry",
                )
            }
        }
        rule.onNodeWithText("Catalog unavailable").assertExists()
        rule.onNodeWithText("relay error code=INTERNAL").assertExists()
        rule.onNodeWithTag("viewer_retry").performClick()
        rule.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `loading skeleton announces loading to TalkBack - no redundant Loading text on screen`() {
        rule.setContent {
            ZCodeTheme { LoadingSkeleton(Modifier, rows = 2) }
        }
        rule.onAllNodesWithText("Loading", substring = true).assertCountEquals(0)
        rule.onNodeWithContentDescription("Loading").assertExists()
    }

    @Test
    fun `section header renders its text`() {
        rule.setContent { ZCodeTheme { SectionHeader("Endpoints") } }
        rule.onNodeWithText("Endpoints").assertExists()
    }
}
