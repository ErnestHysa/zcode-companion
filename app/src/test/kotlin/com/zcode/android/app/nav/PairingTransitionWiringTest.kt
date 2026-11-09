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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-34-E (rig-caught) — the wiring sweep + the hazard control (see PairingTransitionNavTest).
 * Both compile against the pre-fix tree: the wiring pin RED-runs behaviorally there.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class PairingTransitionWiringTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `F-34-E control - a re-evaluated start destination is exactly the hazard (the library resets the stack)`() {
        var paired by mutableStateOf(false)
        var routeSeenByTheEffect: String? = null
        rule.setContent {
            val nav = rememberNavController()
            LaunchedEffect(paired) { if (paired) routeSeenByTheEffect = nav.currentDestination?.route }
            NavHost(nav, startDestination = if (paired) Routes.TASKS else Routes.PAIRING) {
                composable(Routes.PAIRING) {}
                composable(Routes.TASKS) {}
            }
        }
        rule.waitForIdle()
        rule.runOnIdle { paired = true }
        rule.waitForIdle()
        assertEquals("the pre-fix inline expression: the effect only ever sees TASKS", Routes.TASKS, routeSeenByTheEffect)
    }

    @Test
    fun `F-34-E wiring - the production NavHost decides its start destination once`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val nav = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        val host = nav.substringAfter("    NavHost(").substringBefore("enterTransition")
        assertTrue(
            "the NavHost start destination is re-evaluated per composition (it resets the stack on pairing)",
            "startDestination = rememberStartDestination(" in host,
        )
    }
}
