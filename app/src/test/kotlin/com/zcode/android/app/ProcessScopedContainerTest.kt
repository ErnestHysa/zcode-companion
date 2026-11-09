package com.zcode.android.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.connect.ConnectionHub
import com.zcode.android.core.notifications.NotificationBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.lang.ref.WeakReference

/**
 * F-33-A (vc52 Phase 8, P2) — the ZOMBIE conversation screen, and the two rows it closes:
 * F-33-B (every recreated Activity + its whole graph retained for the process lifetime,
 * heap-path proven) and F-33-C (a configuration-change stop treated as a BACKGROUND entry).
 *
 * Recorded shape (passes 33/34): `MainActivity` built the WHOLE graph per Activity
 * (`container = AppContainer(this)` — hub, transport, ports) while the conversation VM is
 * retained across configuration changes by design, so after a recreate the screen on
 * display drove the DESTROYED container's port (sends dead / `command.blockedOffline`,
 * status "Connected · Completed"). The hub's scope WAS the destroyed Activity's
 * lifecycleScope. Every onCreate also chained a NEW default uncaught-exception handler
 * closing over `this` — the exact hprof retention path (5 Activities / 5 containers /
 * 5 hubs / 16 OkHttpClients after four recreates).
 *
 * A recreate, as the container sees it, is: the old MainActivity destroyed and a new one
 * created IN THE SAME PROCESS. Robolectric's recreate() additionally drives the new
 * instance visible(), which trips the documented first-pairing NavHost frame-ordering
 * artifact (ScreenshotProtectionTest) — so these pins drive create/destroy directly, and
 * the F-33-C leg sets `mChangingConfigurations` before pause/stop exactly as the
 * platform's relaunch path (ActivityThread.handleRelaunchActivity) and Robolectric 4.17's
 * own recreate() do.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30], qualifiers = "w411dp-h891dp")
class ProcessScopedContainerTest {

    private fun newActivity(): ActivityController<MainActivity> =
        Robolectric.buildActivity(MainActivity::class.java).create()

    /** The hub's private scope — pre-fix it WAS the creating Activity's lifecycleScope. */
    private fun hubScopeActive(hub: ConnectionHub): Boolean {
        val field = ConnectionHub::class.java.getDeclaredField("scope").apply { isAccessible = true }
        return (field.get(hub) as CoroutineScope).isActive
    }

    /** The broker's private foreground flag (the wiring flips it on a background entry). */
    private fun brokerForeground(broker: NotificationBroker): Boolean {
        val field = NotificationBroker::class.java.getDeclaredField("foreground").apply { isAccessible = true }
        return field.getBoolean(broker)
    }

    @Test
    fun `F-33-A - a recreated MainActivity binds the SAME container, hub, transport and conversation port`() {
        var controller = newActivity()
        val containerA = controller.get().container
        // What the retained ConversationVmHolder's VM holds across the recreate:
        val portA = containerA.conversationPort
        val hubA = containerA.hub
        val transportA = containerA.transport
        controller.destroy() // the configuration change: the old instance goes away
        controller = newActivity()
        try {
            val containerB = controller.get().container
            assertSame(
                "a recreate built a SECOND container — the retained conversation VM keeps driving the dead one (the zombie)",
                containerA,
                containerB,
            )
            assertSame("the retained VM's port must be the live screen's port", portA, containerB.conversationPort)
            assertSame("one hub per process", hubA, containerB.hub)
            assertSame("one relay transport per process", transportA, containerB.transport)
            assertTrue(
                "the hub's scope died with the Activity that first built it (sends ride a destroyed transport)",
                hubScopeActive(containerB.hub),
            )
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `F-33-B - four recreates install the crash handler once and retain no destroyed MainActivity`() {
        var controller = newActivity()
        val handler = Thread.getDefaultUncaughtExceptionHandler()
        val containers = mutableSetOf<AppContainer>(controller.get().container)
        val destroyed = mutableListOf<WeakReference<MainActivity>>()
        repeat(4) {
            destroyed += WeakReference(controller.get())
            controller.destroy()
            controller = newActivity()
            containers += controller.get().container
        }
        try {
            assertSame(
                "every onCreate chained a NEW default uncaught-exception handler closing over the Activity " +
                    "(the hprof retention path: handler → lambda → MainActivity#5 → … → #1)",
                handler,
                Thread.getDefaultUncaughtExceptionHandler(),
            )
            assertEquals("exactly ONE container after four recreates (hprof: 5)", 1, containers.size)
            // The hprof shape in-JVM. The paused Robolectric looper queue holds runnables
            // scheduled by the destroyed lifecycles (a real main thread drains them within
            // moments — the receipt's dump is a minutes-later steady state), so drain it
            // first, then full-GC: only LIVE reachability may retain an instance.
            repeat(3) {
                runCatching {
                    org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMinutes(30))
                }
                Thread.sleep(50)
            }
            repeat(20) {
                if (destroyed.all { it.get() == null }) return@repeat
                System.gc()
                System.runFinalization()
                Thread.sleep(25)
            }
            val retained = destroyed.count { it.get() != null }
            assertEquals("destroyed MainActivity instances still reachable after GC (hprof: 4 extra)", 0, retained)
        } finally {
            controller.destroy()
        }
    }

    @Test
    fun `F-33-C - a configuration-change stop is not a background entry - approvals never re-alert in-app`() {
        val controller = newActivity().start().resume()
        val activity = controller.get()
        val broker = activity.container.stores.notificationBroker
        runBlocking { broker.onForegroundEntry() } // known starting state: the app is in use
        Thread.sleep(300) // let onStart's own (async) foreground entry settle first
        try {
            // The platform relaunch path sets mChangingConfigurations BEFORE pause/stop.
            ReflectionHelpers.setField(activity, "mChangingConfigurations", true)
            controller.pause().stop()
            Thread.sleep(500) // the wiring launches the entry on the app scope (Dispatchers.Default)
            assertTrue(
                "a configuration-change stop flipped the broker to BACKGROUND — every open approval " +
                    "re-posts on the HIGH channel while the user is in the app (19 reposts / 17 noisy mutes)",
                brokerForeground(broker),
            )
        } finally {
            ReflectionHelpers.setField(activity, "mChangingConfigurations", false)
            controller.destroy()
            runBlocking { broker.onForegroundEntry() }
        }
    }

    @Test
    fun `F-33-C control - a real background stop IS a background entry`() {
        val controller = newActivity().start().resume()
        val broker = controller.get().container.stores.notificationBroker
        runBlocking { broker.onForegroundEntry() }
        Thread.sleep(300)
        try {
            controller.pause().stop() // HOME: not a configuration change
            var background = false
            repeat(60) {
                if (!brokerForeground(broker)) { background = true; return@repeat }
                Thread.sleep(50)
            }
            assertTrue("a genuine background stop must still hand attention to the shade", background)
        } finally {
            controller.destroy()
            runBlocking { broker.onForegroundEntry() }
        }
        assertFalse(controller.get().isChangingConfigurations)
    }
}
