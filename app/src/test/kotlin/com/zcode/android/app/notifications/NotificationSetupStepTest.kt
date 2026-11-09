package com.zcode.android.app.notifications

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.ui.theme.ZCodeTheme
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.TaskStatus
import com.zcode.android.domain.state.ConnectionPhase
import com.zcode.android.features.tasks.TaskGroup
import com.zcode.android.features.tasks.TasksUiState
import com.zcode.android.features.tasks.ui.TaskListScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-34-E (vc52 Phase 8, P2) — the ASK (2/2). — on Android 13+ the app never asked for notification
 * permission. Fresh install: POST_NOTIFICATIONS `granted=false`, importance NONE; pairing,
 * Tasks and the conversation never request it (the only site was the Live-connection
 * toggle) — the broker "re-posted" three approvals into a blocked sink, zero delivered,
 * nothing told the user. The pins: the post-pairing step ASKS (with its one-line
 * rationale), a denial leaves the off-state row, the row deep-links to the system page,
 * a grant flips to enabled; Tasks carries the row while blocked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationSetupStepTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val nm: NotificationManager
        get() = rule.activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The fresh API-33+ install: no grant, Android reports notifications off. */
    @Before
    fun freshInstall() {
        shadowOf(nm).setNotificationsEnabled(false)
    }

    private var continued = 0
    private var liveStarts = 0

    private fun setupStep() {
        rule.setContent {
            ZCodeTheme {
                NotificationSetupScreen(
                    onContinue = { continued++ },
                    onStartLiveConnection = { liveStarts++ },
                    liveConnectionRunning = false,
                )
            }
        }
        rule.waitForIdle()
    }

    private fun answer(granted: Boolean) {
        val request = shadowOf(rule.activity).lastRequestedPermission
        assertNotNull("the post-pairing step never asked for POST_NOTIFICATIONS", request)
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), request!!.requestedPermissions.toList())
        if (granted) {
            shadowOf(rule.activity.application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
            shadowOf(nm).setNotificationsEnabled(true)
        }
        rule.runOnIdle {
            @Suppress("DEPRECATION")
            rule.activity.onRequestPermissionsResult(
                request.requestCode,
                request.requestedPermissions,
                intArrayOf(if (granted) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED),
            )
        }
        rule.waitForIdle()
    }


    @Test
    fun `F-34-E - the post-pairing step asks for notifications with a one-line rationale`() {
        setupStep()
        rule.onNodeWithTag("notification_setup_rationale").assertExists()
        rule.onNodeWithTag("notification_setup_allow").performClick()
        rule.waitForIdle()
        assertNotNull(shadowOf(rule.activity).lastRequestedPermission)
    }

    @Test
    fun `F-34-E - deny leaves the off-state row, whose deep link opens the system notification page`() {
        setupStep()
        rule.onNodeWithTag("notification_setup_allow").performClick()
        rule.waitForIdle()
        answer(granted = false)
        rule.onNodeWithTag("notifications_off_row").assertExists()
        rule.onNodeWithTag("notifications_off_turn_on").performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(rule.activity.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
        // The Live connection is honest about needing notifications first.
        rule.onNodeWithTag("notification_setup_live").assertExists()
        assertEquals(0, liveStarts)
    }

    @Test
    fun `F-34-E - grant flips the step to enabled, the Live connection becomes offerable`() {
        setupStep()
        rule.onNodeWithTag("notification_setup_allow").performClick()
        rule.waitForIdle()
        answer(granted = true)
        rule.onNodeWithTag("notification_setup_enabled").assertExists()
        rule.onAllNodesWithTag("notifications_off_row").assertCountEquals(0)
        rule.onNodeWithTag("notification_setup_live").performClick()
        rule.waitForIdle()
        assertEquals("the Live connection is offered in the same step", 1, liveStarts)
        rule.onNodeWithTag("notification_setup_continue").performClick()
        assertEquals(1, continued)
    }

    /** Wiring: the END of pairing routes through the step while Android reports notifications off. */
    @Test
    fun `F-34-E wiring - the pairing transition routes through the notification ask`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val nav = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        val pairingBranch = nav.substringAfter("onPairing -> {").substringBefore("else -> container.hub.onPairingCompleted()")
        assertTrue(
            "the end of pairing does not route through the notification ask",
            "NotificationsGate.enabled" in pairingBranch && "Routes.NOTIFY_SETUP" in pairingBranch,
        )
        assertTrue("the step has no destination", "composable(Routes.NOTIFY_SETUP)" in nav)
    }
}
