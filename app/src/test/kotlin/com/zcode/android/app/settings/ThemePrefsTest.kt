package com.zcode.android.app.settings

import com.zcode.android.core.ui.theme.ThemeMode
import com.zcode.android.domain.model.AppPreferences
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * H1.4-phase1 (gaps-and-polish Phase 4F): the PREF MODEL mapping — the proto
 * round-trip is additive-first (unset → SYSTEM / all ON) and the theme enum
 * maps onto the core-ui ThemeMode 1:1 (the MainActivity switch table).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThemePrefsTest {

    @Test
    fun `defaults keep the pre-4F behavior — system theme, all categories on`() {
        val p = AppPreferences()
        assertEquals(AppPreferences.ThemeMode.SYSTEM, p.themeMode)
        assertTrue(p.notifyApprovals)
        assertTrue(p.notifyTaskOutcomes)
        assertTrue(p.notifyConnection)
    }

    @Test
    fun `the theme enum maps onto the core-ui ThemeMode one to one`() {
        assertEquals(
            ThemeMode.entries.toList(),
            AppPreferences.ThemeMode.entries.map {
                when (it) {
                    AppPreferences.ThemeMode.LIGHT -> ThemeMode.LIGHT
                    AppPreferences.ThemeMode.DARK -> ThemeMode.DARK
                    AppPreferences.ThemeMode.SYSTEM -> ThemeMode.SYSTEM
                }
            },
        )
    }

    @Test
    fun `a copy override changes exactly the requested field`() = runTest(StandardTestDispatcher()) {
        val p = AppPreferences().copy(themeMode = AppPreferences.ThemeMode.DARK, notifyConnection = false)
        assertEquals(AppPreferences.ThemeMode.DARK, p.themeMode)
        assertFalse(p.notifyConnection)
        assertTrue(p.notifyApprovals, "untouched fields stay at default")
        advanceUntilIdle()
    }
}
