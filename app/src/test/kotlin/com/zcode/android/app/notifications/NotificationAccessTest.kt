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
 * F-34-E (vc52 Phase 8, P2) — on Android 13+ the app never asked for notification
 * permission. Fresh install: POST_NOTIFICATIONS `granted=false`, importance NONE; pairing,
 * Tasks and the conversation never request it (the only site was the Live-connection
 * toggle) — the broker "re-posted" three approvals into a blocked sink, zero delivered,
 * nothing told the user. The pins: the post-pairing step ASKS (with its one-line
 * rationale), a denial leaves the off-state row, the row deep-links to the system page,
 * a grant flips to enabled; Tasks carries the row while blocked.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class NotificationAccessTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val nm: NotificationManager
        get() = rule.activity.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    /** The fresh API-33+ install: no grant, Android reports notifications off. */
    @Before
    fun freshInstall() {
        shadowOf(nm).setNotificationsEnabled(false)
    }

    @Test
    fun `F-34-E - the off-state row deep-links to the app's system notification page`() {
        rule.setContent { ZCodeTheme { NotificationsOffRow() } }
        rule.waitForIdle()
        rule.onNodeWithTag("notifications_off_turn_on").performClick()
        rule.waitForIdle()
        val started = shadowOf(rule.activity).nextStartedActivity
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, started.action)
        assertEquals(rule.activity.packageName, started.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `F-34-E - the gate follows Android's verdict and builds the system-page intent`() {
        val ctx = rule.activity
        assertTrue(!NotificationsGate.enabled(ctx))
        assertTrue("API 33+ without the grant needs the runtime ask", NotificationsGate.needsRuntimeGrant(ctx))
        shadowOf(nm).setNotificationsEnabled(true)
        assertTrue(NotificationsGate.enabled(ctx))
        val intent = NotificationsGate.settingsIntent(ctx)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, intent.action)
        assertEquals(ctx.packageName, intent.getStringExtra(Settings.EXTRA_APP_PACKAGE))
    }

    @Test
    fun `F-34-E - Tasks carries the off-state row while blocked`() {
        val state = TasksUiState(
            phase = ConnectionPhase.CONNECTED,
            loadState = TasksUiState.Load.READY,
            endpointName = "MockBook",
            groups = listOf(
                TaskGroup(
                    "/ws/demo", "demo",
                    listOf(
                        com.zcode.android.domain.model.Task(
                            taskId = "t1", title = "Synthetic task", status = TaskStatus.RUNNING,
                            workspace = WorkspaceIdentityKey("/ws/demo"), createdAtMs = 1, updatedAtMs = 2,
                        ),
                    ),
                ),
            ),
            taskCount = 1,
        )
        rule.setContent {
            ZCodeTheme {
                TaskListScreen(
                    state = state,
                    onTaskClick = {}, onOpenSettings = {}, onOpenConnection = {}, onRetry = {}, onRefresh = {},
                    notificationsBanner = { NotificationsOffRow() },
                )
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("notifications_off_row").assertExists()
    }

    /**
     * Wiring (the F-27-B lesson — a defaulted slot nobody passes is silently dead): the
     * app's Tasks route fills the banner from Android's live verdict.
     */
    @Test
    fun `F-34-E wiring - the Tasks route passes the off-state banner from the live gate`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "settings.gradle.kts").isFile }
        val nav = File(root, "app/src/main/kotlin/com/zcode/android/app/nav/ZCodeApp.kt").readText()
        assertTrue("TaskListScreen is never handed the off-state banner", "notificationsBanner =" in nav)
        assertTrue("the Tasks route never reads Android's verdict", "rememberNotificationsEnabled()" in nav)
    }
}
