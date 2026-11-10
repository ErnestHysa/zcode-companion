package com.zcode.android.app.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.AppContainer
import com.zcode.android.app.R
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AppPreferences
import org.junit.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * N-BI-5 (vc52 Phase 10): the "Notification detail" subtitle was STATIC — it always
 * read the Minimal copy while the value itself cycled Minimal ⇄ Task titles per tap,
 * contradicting the row's own current value (found during the animations-off walk:
 * the value flipped twice under the static copy). The subtitle now describes the
 * CURRENT level; pinned both at the selection seam and on the composed screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class NotificationDetailSubtitleTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `the subtitle selection maps each level to its own copy`() {
        assertEquals(
            R.string.settings_notif_detail_sub_min,
            notificationDetailSubtitleRes(AppPreferences.NotificationContentLevel.MINIMAL),
        )
        assertEquals(
            R.string.settings_notif_detail_sub_rich,
            notificationDetailSubtitleRes(AppPreferences.NotificationContentLevel.CONTENT),
        )
    }

    @Test
    fun `the subtitle text is different per level (the two copies are distinct strings)`() {
        val context = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val min = context.getString(R.string.settings_notif_detail_sub_min)
        val rich = context.getString(R.string.settings_notif_detail_sub_rich)
        kotlin.test.assertTrue(min != rich)
        // Each level's copy LEADS with its own name (no contradiction with the row's
        // value) and STILL discloses the other level (the F-33-H privacy contract).
        kotlin.test.assertTrue(min.indexOf("Minimal") < min.indexOf("Task titles"), min)
        kotlin.test.assertTrue(rich.indexOf("Task titles") < rich.indexOf("Minimal"), rich)
        for (copy in listOf(min, rich)) {
            kotlin.test.assertTrue(copy.contains("answered in the app"), copy)
            kotlin.test.assertTrue(copy.contains("command or file"), copy)
            kotlin.test.assertTrue(copy.contains("unlock"), copy)
        }
    }

    @Test
    fun `the composed row's subtitle FLIPS with the value (the recorded face)`() {
        val activity = Robolectric.setupActivity(androidx.activity.ComponentActivity::class.java)
        val container = AppContainer(activity)
        rule.setContent {
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
        rule.onNodeWithTag("settings_notification_content").performScrollTo()
        rule.waitForIdle()
        fun rendered(res: Int): Boolean =
            rule.onAllNodesWithText(activity.getString(res), substring = true).fetchSemanticsNodes().isNotEmpty()
        // Default MINIMAL: the Minimal copy shows. (Existence, not viewport coverage —
        // the subtitle wraps and the pool load clips it; the pinned fact is WHICH copy
        // the row renders, not full-viewport visibility.)
        assertTrue("the Minimal copy renders at default", rendered(R.string.settings_notif_detail_sub_min))
        assertEquals(emptyList(), rule.onAllNodesWithText(activity.getString(R.string.settings_notif_detail_sub_rich), substring = true).fetchSemanticsNodes(), "the rich copy must be absent at default")
        // Tap → CONTENT: the Task-titles copy replaces it. The pref write→collect→
        // recompose round-trip may not have published by the time waitForIdle returns
        // under pool load (the SwitchRowA11yTest race class) — poll for the flip.
        // THE N-BI-5 FACT: the subtitle follows the LEVEL. The flip is driven through
        // the preferences repository directly — a compose click here races the scroll
        // settle (two pool runs recorded flip-back double-clicks; row-tap→pref-write→
        // recompose is separately pinned by SwitchRowA11yTest with its own poll).
        rule.runOnUiThread {
            kotlinx.coroutines.runBlocking {
                container.stores.preferencesRepository.update { p ->
                    p.copy(notificationContentLevel =
                        com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.CONTENT)
                }
            }
        }
        rule.waitForIdle()
        rule.waitUntil(5_000) { rendered(R.string.settings_notif_detail_sub_rich) }
        assertEquals(
            emptyList(),
            rule.onAllNodesWithText(activity.getString(R.string.settings_notif_detail_sub_min), substring = true).fetchSemanticsNodes(),
            "the Minimal copy must be gone once the level is Task titles",
        )
    }
}
