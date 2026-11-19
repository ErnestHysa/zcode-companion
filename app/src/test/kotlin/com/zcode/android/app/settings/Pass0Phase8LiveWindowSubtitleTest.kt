package com.zcode.android.app.settings

import android.view.ViewGroup
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.core.notifications.LiveConnectionService
import com.zcode.android.core.ui.theme.ZCodeTheme
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-P11-B (pass-0 Phase 8; pass-11 receipt N.A2 s36/s37/s40/s41/s44): the
 * Live-connection window picker's selection never reflected in the row subtitle
 * in-session — three receipted cycles where the write PERSISTED to disk
 * (preferences.pb field 8) and a screen re-entry rendered it, but the in-session
 * subtitle never recomposed. The pin: pick "60 minutes" in the dialog and assert the
 * row subtitle reflects it WITHOUT leaving the screen (the receipted stale-projection
 * face is a regression guard either way the current projection behaves).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class Pass0Phase8LiveWindowSubtitleTest {

    @get:Rule
    val rule = createAndroidComposeRule<FragmentActivity>()

    /** The service must be "running" or the row is disabled (no clickable — the pick is unreachable). */
    private fun markServiceRunning() {
        val companion = LiveConnectionService::class.java.getDeclaredField("Companion").apply { isAccessible = true }.get(null)
        val field = companion.javaClass.getDeclaredField("_isRunning").apply { isAccessible = true }
        (field.get(companion) as MutableStateFlow<Boolean>).value = true
    }

    private fun rowText(): String {
        val node: SemanticsNode = rule.onNodeWithTag("settings_live_window").fetchSemanticsNode()
        return textDeep(node)
    }

    private fun textDeep(node: SemanticsNode): String {
        val own = node.config.getOrElse(SemanticsProperties.Text) { emptyList() }
        return own.joinToString(" ") { it.text }.ifBlank {
            node.children.joinToString(" ") { textDeep(it) }
        }
    }

    @Test
    fun `F-P11-B - the window pick reflects in the row subtitle WITHOUT re-entry`() {
        markServiceRunning()
        val container = AppContainer(rule.activity)
        rule.setContent {
            ZCodeTheme {
                SettingsScreen(
                    container = container,
                    endpointName = "MockBook",
                    desktopVersion = "3.18.1",
                    onBack = {},
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("settings_live_window").performClick()
        rule.waitForIdle()
        // The picker dialog: choose 60 minutes.
        rule.onNodeWithText("60 minutes").performClick()
        rule.waitForIdle()
        // THE receipted face: the subtitle must recompose in-session (no re-entry).
        rule.waitUntil(10_000) { rowText().contains("60 minutes") }
        assertTrue(
            "F-P11-B: the subtitle never recomposed after the pick: \"${rowText()}\"",
            rowText().contains("60 minutes"),
        )
    }
}
