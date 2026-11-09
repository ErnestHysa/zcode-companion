package com.zcode.android.app.nav

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * F-34-E (vc52 Phase 8, rig-caught on the exit-gate leg): the post-pairing transition was
 * DEAD on a real pairing. ZCodeApp's `NavHost(startDestination = if (paired != null) TASKS
 * else PAIRING)` re-evaluated on every composition: when pairing completed, the start
 * destination flipped, Navigation rebuilt the graph and RESET the back stack to TASKS during
 * composition — before `LaunchedEffect(paired)` ran — so the effect saw TASKS, skipped its
 * `onPairing` branch, and neither the new notification ask nor the first-run onboarding (H1.5)
 * was ever reached (device log: `nav.screen route=pairing` → `route=tasks`, nothing between).
 * The start destination is decided ONCE; the effect owns every paired-driven transition.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PairingTransitionNavTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `F-34-E - the pairing flip leaves the back stack on PAIRING so the transition effect routes onward`() {
        var paired by mutableStateOf(false)
        var routeSeenByTheEffect: String? = null
        rule.setContent {
            val nav = rememberNavController()
            // Declared before the NavHost, exactly like ZCodeApp's LaunchedEffect(paired).
            LaunchedEffect(paired) { if (paired) routeSeenByTheEffect = nav.currentDestination?.route }
            NavHost(nav, startDestination = rememberStartDestination(paired)) {
                composable(Routes.PAIRING) {}
                composable(Routes.TASKS) {}
                composable(Routes.NOTIFY_SETUP) {}
                composable(Routes.ONBOARDING) {}
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { paired = true } // pairing completed
        rule.waitForIdle()
        assertEquals(
            "the start-destination flip reset the stack before the transition effect ran (no ask, no onboarding)",
            Routes.PAIRING,
            routeSeenByTheEffect,
        )
    }

    @Test
    fun `F-34-E - a cold start with an existing pairing still starts on Tasks`() {
        var start: String? = null
        rule.setContent { start = rememberStartDestination(paired = true) }
        rule.waitForIdle()
        assertEquals(Routes.TASKS, start)
    }
}
