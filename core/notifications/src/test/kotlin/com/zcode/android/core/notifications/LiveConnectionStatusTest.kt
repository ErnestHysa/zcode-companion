package com.zcode.android.core.notifications

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.os.Looper
import com.zcode.android.domain.state.ConnectionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * F-34-F (vc52 Phase 8, P3, honesty) — the Live-connection notification said "Connected"
 * while the app was disconnected. `LiveConnectionService` built its text ONCE at service
 * start ("Connected to MockBook — Live connection active — tap to disconnect"); through
 * 14:44–14:54 of pass 34 the transport was DEGRADED (in-app "Disconnected") and the shade
 * kept the false all-clear — exactly the user who enabled the Live connection to receive
 * approvals is the one misled. The service must follow the live phase: Connected /
 * Reconnecting / Disconnected — tap to retry, on ONE stable notification id, re-posting
 * only when the visible status changes (no post spam).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class LiveConnectionStatusTest {

    private val phase = MutableStateFlow(ConnectionPhase.CONNECTED)

    @After
    fun tearDown() {
        LiveConnectionService.phaseSource = null
        LiveConnectionService.endpointLabelProvider = { it.getString(R.string.fgs_default_desktop) }
    }

    private fun foreground(service: Service): Notification = shadowOf(service).lastForegroundNotification

    private fun title(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    private fun text(n: Notification): String = n.extras.getCharSequence(Notification.EXTRA_TEXT).toString()

    private fun settle() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50))

    @Test
    fun `F-34-F - the notification follows the live phase through all three states`() {
        LiveConnectionService.phaseSource = phase
        LiveConnectionService.endpointLabelProvider = { "MockBook" }
        val service = Robolectric.buildService(LiveConnectionService::class.java).create().startCommand(0, 1).get()
        settle()
        assertEquals("Connected to MockBook", title(foreground(service)))

        phase.value = ConnectionPhase.RECONNECTING // the relay was killed
        settle()
        assertEquals(
            "the shade kept the start-time 'Connected' through a lost link",
            "Reconnecting to MockBook…",
            title(foreground(service)),
        )

        phase.value = ConnectionPhase.DEGRADED // the budget ran out — user action needed
        settle()
        assertEquals("Disconnected from MockBook", title(foreground(service)))
        assertEquals("Tap to retry", text(foreground(service)))

        phase.value = ConnectionPhase.CONNECTED // reconnect restores it
        settle()
        assertEquals("Connected to MockBook", title(foreground(service)))
        assertEquals("Live connection active — tap to disconnect", text(foreground(service)))
    }

    @Test
    fun `F-34-F - one stable id and no re-post while the visible status is unchanged`() {
        LiveConnectionService.phaseSource = phase
        LiveConnectionService.endpointLabelProvider = { "MockBook" }
        val service = Robolectric.buildService(LiveConnectionService::class.java).create().startCommand(0, 1).get()
        settle()
        phase.value = ConnectionPhase.RECONNECTING
        settle()
        val reconnecting = foreground(service)
        // RECONNECTING → RECOVERING → CONNECTING → RECONNECTING: the same user-visible
        // status — a re-post per transport micro-state would be shade spam.
        phase.value = ConnectionPhase.RECOVERING; settle()
        phase.value = ConnectionPhase.CONNECTING; settle()
        phase.value = ConnectionPhase.RECONNECTING; settle()
        assertSame("micro-state churn re-posted the notification", reconnecting, foreground(service))
        assertEquals(LiveConnectionService.NOTIFICATION_ID, shadowOf(service).lastForegroundNotificationId)
        val nm = RuntimeEnvironment.getApplication().getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        assertTrue(
            "every post rides the ONE Live-connection id",
            shadowOf(nm).allNotifications.size <= 1,
        )
    }

    @Test
    fun `F-34-F - the phase vocabulary maps onto three honest states`() {
        val s = LiveConnectionService.Companion
        assertEquals(LiveConnectionService.LinkStatus.CONNECTED, s.statusFor(ConnectionPhase.CONNECTED))
        for (p in listOf(
            ConnectionPhase.PAIRING, ConnectionPhase.AUTHENTICATING, ConnectionPhase.CONNECTING,
            ConnectionPhase.RECONNECTING, ConnectionPhase.RECOVERING,
        )) assertEquals(p.name, LiveConnectionService.LinkStatus.RECONNECTING, s.statusFor(p))
        for (p in listOf(ConnectionPhase.DEGRADED, ConnectionPhase.FAILED, ConnectionPhase.CLOSED, ConnectionPhase.UNPAIRED)) {
            assertEquals(p.name, LiveConnectionService.LinkStatus.DISCONNECTED, s.statusFor(p))
        }
        // Exhaustive: a new phase constant must be classified here, not fall through silently.
        assertEquals(10, ConnectionPhase.entries.size)
    }

    @Test
    fun `F-34-F - a service-first start with no phase source keeps the start status`() {
        LiveConnectionService.phaseSource = null
        val service = Robolectric.buildService(LiveConnectionService::class.java).create().startCommand(0, 1).get()
        settle()
        assertTrue(title(foreground(service)).startsWith("Connected to"))
    }
}
