package com.zcode.android.app.settings

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.model.AppPreferences
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * G37/G38/G39/THEME-ZAI (vc51 Phase 4D) picker pins — the extracted testable surfaces:
 * the Language picker offers System/English/中文 and dispatches the picked locale; the
 * Text-size picker offers 0.85/1.0/1.15; the auto-archive picker offers 0/3/7/14/30 with
 * Off first; the Theme picker surfaces the two zai brand variants beside the base modes
 * (it iterates ThemeMode.entries — this pin owns that contract).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AppearancePrefsUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `language picker offers all three and dispatches the picked locale`() {
        val picked = mutableListOf<AppPreferences.AppLocale>()
        rule.setContent {
            ZCodeTheme {
                LocalePickerDialog(current = AppPreferences.AppLocale.SYSTEM, onDismiss = {}, onPick = { picked += it })
            }
        }
        rule.onNodeWithTag("settings_locale_SYSTEM").assertIsDisplayed()
        rule.onNodeWithTag("settings_locale_EN").assertIsDisplayed()
        rule.onNodeWithTag("settings_locale_ZH_RCN").assertIsDisplayed()
        rule.onNodeWithText("中文").assertIsDisplayed()
        rule.onNodeWithTag("settings_locale_ZH_RCN").performClick()
        assertEquals(listOf(AppPreferences.AppLocale.ZH_RCN), picked)
    }

    @Test
    fun `text size picker offers 085 100 and 115 and dispatches`() {
        val picked = mutableListOf<Float>()
        rule.setContent {
            ZCodeTheme {
                FontScalePickerDialog(current = 1f, onDismiss = {}, onPick = { picked += it })
            }
        }
        rule.onNodeWithTag("settings_fontscale_850").assertIsDisplayed()
        rule.onNodeWithTag("settings_fontscale_1000").assertIsDisplayed()
        rule.onNodeWithTag("settings_fontscale_1150").assertIsDisplayed()
        rule.onNodeWithTag("settings_fontscale_850").performClick()
        assertEquals(listOf(0.85f), picked)
    }

    @Test
    fun `auto archive picker offers off and the aging buckets and dispatches`() {
        val picked = mutableListOf<Int>()
        rule.setContent {
            ZCodeTheme {
                AutoArchivePickerDialog(current = 0, onDismiss = {}, onPick = { picked += it })
            }
        }
        rule.onNodeWithTag("settings_autoarchive_0").assertIsDisplayed()
        rule.onNodeWithTag("settings_autoarchive_3").assertIsDisplayed()
        rule.onNodeWithTag("settings_autoarchive_30").assertIsDisplayed()
        rule.onNodeWithText("Off (manual only)").assertIsDisplayed()
        rule.onNodeWithTag("settings_autoarchive_7").performClick()
        assertEquals(listOf(7), picked)
    }

    @Test
    fun `theme picker surfaces the zai variants beside the base modes`() {
        val picked = mutableListOf<AppPreferences.ThemeMode>()
        rule.setContent {
            ZCodeTheme {
                ThemePickerDialog(current = AppPreferences.ThemeMode.SYSTEM, onDismiss = {}, onPick = { picked += it })
            }
        }
        rule.onNodeWithTag("settings_theme_ZAI_LIGHT").assertIsDisplayed()
        rule.onNodeWithTag("settings_theme_ZAI_DARK").assertIsDisplayed()
        rule.onNodeWithTag("settings_theme_LIGHT").assertIsDisplayed()
        rule.onNodeWithTag("settings_theme_DARK").assertIsDisplayed()
        rule.onNodeWithTag("settings_theme_SYSTEM").assertIsDisplayed()
        rule.onNodeWithText("zai light").assertIsDisplayed()
        rule.onNodeWithText("zai dark").assertIsDisplayed()
        rule.onNodeWithTag("settings_theme_ZAI_DARK").performClick()
        assertEquals(listOf(AppPreferences.ThemeMode.ZAI_DARK), picked)
    }
}
