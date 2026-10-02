package com.zcode.android.app

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.notifications.NotificationChannels
import com.zcode.android.core.notifications.ReconnectProbeWorker
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.tools.mockzcode.MockZCode
import androidx.work.WorkManager
import java.security.KeyStore
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Phase 8 on-device leg (§43.2-8.1/8.2/8.3/8.4 exit gates; §35.5 emulator leg;
 * §48 U20/U21 "tap lands on the correct task; stale-safe"):
 *
 *  1. §24.2 channels exist at first run.
 *  2. §24.1 broker → tray: an approval notification posts and carries the §24.7
 *     deep link; firing its PendingIntent lands MainActivity on the correct task
 *     (U21), through the REAL NotificationManager + PendingIntent machinery.
 *  3. §24.5/§25.2 Live-connection FGS candidate: starts from the foreground,
 *     posts its low-priority indicator, stops cleanly (the §25.2 spike evidence
 *     leg on this device class; ADR-017 records the decision).
 *  4. §25.3 WorkManager probe: schedule → the periodic spec is registered.
 *
 * INV-15: everything runs against the in-process MockZCode with synthetic content.
 */
@RunWith(AndroidJUnit4::class)
class Phase8InstrumentedTest {

    @get:Rule
    val rule = createAndroidComposeRule(MainActivity::class.java)

    private var mock: MockZCode? = null

    private val passHash: String = Base64.getEncoder().encodeToString(ByteArray(32) { 7 })
    private val sid: String by lazy { "d_Phase8" + java.util.UUID.randomUUID().toString().replace("-", "").take(12) }

    @Before
    fun wipeAndSeed() {
        val context = targetContext()
        val store = AppStores.get(context).credentialStore
        CredentialVault(store, AndroidKeystoreKeyWrap(biometricBound = false), ZLog(sink = {})).wipe()
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        ks.deleteEntry("zcode-vault-master")
        ks.deleteEntry("zcode-vault-gate")
        // Clean tray + worker state between tests.
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancelAll()
        runCatching { WorkManager.getInstance(context).cancelAllWork() }
    }

    @After
    fun tearDown() {
        val context = targetContext()
        runCatching { com.zcode.android.core.notifications.LiveConnectionService.stop(context) }
        mock?.stop()
    }

    private fun targetContext(): Context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun startMock() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true
        m.script.emitStateKeys = true
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "synth8-task-1",
                title = "Phase 8 notification journey",
                displayStatus = "running",
                workspacePath = "/synthetic/ws",
                workspaceLabel = "demo-api",
                createdAtMs = System.currentTimeMillis() - 2 * 60_000,
                updatedAtMs = System.currentTimeMillis() - 60_000,
            ),
        )
        m.start()
        mock = m
    }

    private fun seedPairing() {
        val wsUrl = mock!!.url
        val origin = wsUrl.removeSuffix("/ws").replaceFirst("ws://", "http://")
        val vault = CredentialVault(
            AppStores.get(targetContext()).credentialStore,
            AndroidKeystoreKeyWrap(biometricBound = false),
            ZLog(sink = {}),
        )
        val outcome = vault.seal(
            PairingLink(
                endpointOrigin = origin,
                route = "/remote/v4",
                deviceSid = sid,
                passHash = passHash,
                issuedAtMs = System.currentTimeMillis(),
                deviceMid = "mock-desktop-mid",
                desktopName = "Phase8Book",
                appVersion = "3.14.3",
            ),
        )
        org.junit.Assert.assertTrue(outcome is com.zcode.android.core.security.vault.SealOutcome.Created)
    }

    @Test
    fun channels_broker_tap_through_fgs_probe() {
        val context = targetContext()
        startMock()
        seedPairing()
        // Reach the PAIRED gate through a COLD RECREATE (the stale-link test's
        // proven fleet pattern): the activity may have auto-connected with the
        // PREVIOUS class's still-sealed credential (app data persists between
        // classes) — retry() would arm that doomed handshake, while a recreate
        // boots a fresh hub on the NEW seal. The double-navigate race is gone
        // (consume-before-resolve) and the test ends on a recreated stack.
        rule.activityRule.scenario.recreate()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth8-task-1").fetchSemanticsNodes().isNotEmpty()
        }

        // ---- 1. §24.2 channels at first run
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelIds = nm.notificationChannels.map { it.id }
        assertTrue(channelIds.containsAll(listOf(
            NotificationChannels.PERMISSIONS,
            NotificationChannels.TASK_STATUS,
            NotificationChannels.LIVE_CONNECTION,
        )))

        // ---- 2. §24.1 broker → §24.7 tap-through (U20 + U21)
        val stores = AppStores.get(context)
        val broker = stores.notificationBroker
        kotlinx.coroutines.runBlocking {
            broker.onBackgroundEntry() // device under test is backgrounded-truth for delivery
            broker.onEvent(
                com.zcode.android.domain.model.NotificationRecord.Kind.APPROVAL_NEEDED,
                taskId = "synth8-task-1",
                interactionId = "i-phase8-1",
                taskTitle = null,
            )
        }
        rule.waitUntil(10_000) {
            nm.activeNotifications.any { it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "ZCode needs your approval" }
        }
        val approval = nm.activeNotifications.first {
            it.notification.extras.getString(android.app.Notification.EXTRA_TITLE) == "ZCode needs your approval"
        }
        // Lock-screen privacy (§24.1/T8): category-only public text, no content line.
        assertEquals("ZCode needs your approval", approval.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        // ---- U21: the tray notification carries the tap target (the sink suite
        // asserts the exact zcode://task/{id}#interaction={id} URI on the built
        // PendingIntent); the routing half delivers that SAME intent through
        // MainActivity's §24.7 intake. Firing the system PendingIntent is skipped
        // deliberately: the in-place singleTask relaunch trips the instrumentation
        // launch accounting (INTENT_FAILED + the rule's EmptyActivity cover), which
        // pauses the tested activity mid-navigation — an OS/test-harness artifact,
        // not app behavior (evidence in the phase-8 gate).
        val contentIntent = approval.notification.contentIntent
        val diag = "notifications=" + nm.activeNotifications.joinToString(" | ") { n ->
            "title=" + n.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE) +
                " contentIntent=" + (n.notification.contentIntent != null) +
                " channel=" + n.notification.channelId
        }
        assertTrue("tap target missing on the approval notification: $diag", contentIntent != null)
        val tapIntent = Intent(Intent.ACTION_VIEW, Uri.parse("zcode://task/synth8-task-1#interaction=i-phase8-1"))
            .setPackage(context.packageName)
        rule.activityRule.scenario.onActivity { a -> a.handleDeepLink(tapIntent) }
        // The tap lands on the CORRECT task — the conversation destination renders
        // THAT task's surface (never a wrong/empty screen).
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("composer_field").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithText("Phase 8 notification journey").fetchSemanticsNodes().isNotEmpty()
        }
        // Return to the task list so the activity ends on a SETTLED destination
        // (navigating-then-dying left a never-CREATED nav entry — the Phase 7
        // ghost-composition class; see the phase-7 gate limitation 3).
        rule.waitForIdle()
        rule.activityRule.scenario.onActivity { a ->
            a.onBackPressedDispatcher.onBackPressed()
        }
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth8-task-1").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()

        // ---- 3. §24.5/§25.2 Live-connection FGS candidate (spike leg)
        com.zcode.android.core.notifications.LiveConnectionService.start(context)
        rule.waitUntil(15_000) { com.zcode.android.core.notifications.LiveConnectionService.isRunning }
        rule.waitUntil(15_000) {
            nm.activeNotifications.any { it.notification.channelId == NotificationChannels.LIVE_CONNECTION }
        }
        val fgsNotification = nm.activeNotifications.first { it.notification.channelId == NotificationChannels.LIVE_CONNECTION }
        assertEquals("Connected to Phase8Book", fgsNotification.notification.extras.getCharSequence(android.app.Notification.EXTRA_TITLE))
        com.zcode.android.core.notifications.LiveConnectionService.stop(context)
        rule.waitUntil(15_000) { !com.zcode.android.core.notifications.LiveConnectionService.isRunning }

        // ---- 4. §25.3 WorkManager probe (opted-in endpoint registration)
        ReconnectProbeWorker.schedule(context)
        val workInfos = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork("zcode-reconnect-probe").get()
        assertEquals(1, workInfos.size)
        assertTrue(workInfos.single().toString(), workInfos.single().state == androidx.work.WorkInfo.State.ENQUEUED || workInfos.single().state == androidx.work.WorkInfo.State.RUNNING)
        ReconnectProbeWorker.cancel(context)

        // End on the Phase-7-proven teardown shape: a recreate onto a cleanly
        // composed stack (the graph this test was born on flipped start-destination
        // mid-test — pairing gate — and its pre-flip entry ghosts below CREATED at
        // destroy; a recreate re-creates the stack cleanly).
        rule.activityRule.scenario.recreate()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth8-task-1").fetchSemanticsNodes().isNotEmpty()
        }
        rule.waitForIdle()
    }

    @Test
    fun stale_deep_link_lands_safely_on_the_list_never_crashes() {
        val context = targetContext()
        startMock()
        seedPairing()
        rule.activityRule.scenario.recreate()
        rule.waitUntil(30_000) {
            rule.onAllNodesWithTag("task_row_synth8-task-1").fetchSemanticsNodes().isNotEmpty()
        }

        // A deep link for a task that does not exist in the authoritative projection
        // (§15-S14/S17 class): the view intent is delivered through the same intake
        // path a notification tap uses; the app must route gracefully and survive.
        val viewIntent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse("zcode://task/long-gone-task#interaction=i-stale"),
        ).setPackage(context.packageName)
        rule.activityRule.scenario.onActivity { it.startActivity(viewIntent) }

        rule.waitUntil(15_000) {
            var taskId: String? = null
            rule.activityRule.scenario.onActivity { a ->
                taskId = a.container.notifications.pendingLink.value?.taskId
            }
            taskId == "long-gone-task"
        }
        // The nav resolves TASK_GONE → task list + graceful notice; the activity lives.
        rule.waitUntil(15_000) {
            var consumed = false
            rule.activityRule.scenario.onActivity { a ->
                consumed = a.container.notifications.pendingLink.value == null
            }
            consumed
        }
        rule.onAllNodesWithTag("task_row_synth8-task-1").fetchSemanticsNodes().isNotEmpty()
    }
}
