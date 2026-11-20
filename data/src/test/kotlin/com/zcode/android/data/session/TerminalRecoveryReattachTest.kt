package com.zcode.android.data.session

import com.zcode.android.core.transport.AppTransport
import com.zcode.android.core.transport.CapabilitySnapshot
import com.zcode.android.core.transport.EndpointKind
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.v4.HostCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * F-P48-A (pass-0 Phase 9) pins: the terminal channel's recovery half. The pass-48
 * live receipt: ANY large stream dropped the transport ~1 s after attach; the app
 * RECOVERED the transport fully but the terminal channel's onDynamicData/onDynamicExit
 * listens died with the old connection and NEVER re-established (the singleton port
 * state machine had no CONNECTED-transition hook) — 9+ minutes of stale LIVE with the
 * ring frozen, the scheduled exit never rendered, manual Detach→dispose→Attach the
 * only recovery. The fix: on the CONNECTED transition, a Live session re-fires the
 * attach (fresh create + fresh listens). BOUNDED: an attach that loses the transport
 * young (within the survival window) counts toward a cap; the cap exhausts into the
 * honest Refused face (the roadmap's accepted alternative) — never an infinite
 * re-fire loop against a stream class the reader cannot survive.
 */
class TerminalRecoveryReattachTest {

    private val creates = java.util.concurrent.atomic.AtomicInteger()
    private val dataRegistrations = java.util.concurrent.atomic.AtomicInteger()
    private val exitRegistrations = java.util.concurrent.atomic.AtomicInteger()
    private val disposedIds = java.util.Collections.synchronizedList(mutableListOf<String>())
    @Volatile private var exitHandler: ((Int?) -> Unit)? = null

    /** A Proxy-based AppTransport: counts the terminal words, serves a Live-able create. */
    private fun transport(): AppTransport {
        val caps = MutableStateFlow<CapabilitySnapshot?>(
            CapabilitySnapshot(
                connectionId = "conn-recovery-test",
                serverTimeMs = 0.0,
                host = HostCapabilities(
                    nativeDialogs = true,
                    localTerminal = true,
                    binaryFrames = false,
                    compression = "none",
                ),
                desktopAppVersion = "test",
                endpointKind = EndpointKind.RELAY_ZAI,
            ),
        )
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "getCapabilities" -> caps
                "terminalCreate" -> {
                    creates.incrementAndGet()
                    buildJsonObject {
                        put("id", "term-${creates.get()}")
                        put("shell", "/bin/zsh")
                    }
                }
                "terminalListenData" -> { dataRegistrations.incrementAndGet(); dataRegistrations.get() }
                "terminalListenExit" -> {
                    @Suppress("UNCHECKED_CAST")
                    exitHandler = args[1] as ((Int?) -> Unit)
                    exitRegistrations.incrementAndGet(); exitRegistrations.get()
                }
                "terminalDispose" -> { disposedIds.add(args[0] as String); Unit }
                "disposeListener" -> Unit
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            AppTransport::class.java.classLoader, arrayOf(AppTransport::class.java), handler,
        ) as AppTransport
    }

    private fun controller(): TerminalController = TerminalController(transport(), ZLog(sink = {}))

    /** Backdate the recovery bookkeeping (the young-death clock) via the test seam. */
    private fun ageLastAttach(c: TerminalController, ageMs: Long) {
        val field = TerminalController::class.java.getDeclaredField("lastAttachAtMs")
        field.isAccessible = true
        field.setLong(c, System.currentTimeMillis() - ageMs)
    }

    @Test
    fun `the CONNECTED transition re-fires the attach for a Live session - create and both listens re-register`() = runTest {
        val c = controller()
        assertTrue(c.attach() is TerminalController.State.Live)
        assertEquals(1, creates.get())
        assertEquals(1, dataRegistrations.get())
        assertEquals(1, exitRegistrations.get())
        c.setInputOptIn(true)
        c.arm()
        assertTrue(c.armed.value, "precondition: armed")

        c.onTransportConnected()

        assertEquals(2, creates.get(), "the attach must re-fire on the recovered transport")
        assertEquals(2, dataRegistrations.get(), "onDynamicData must re-register (the pass-48 dead-listen defect)")
        assertEquals(2, exitRegistrations.get(), "onDynamicExit must re-register")
        assertTrue(c.state.value is TerminalController.State.Live)
        assertFalse(c.armed.value, "the arm is per-attach (ADR-022) — the recovered session starts disarmed")
    }

    @Test
    fun `Idle never re-fires on the CONNECTED transition`() = runTest {
        val c = controller()
        c.onTransportConnected()
        assertEquals(0, creates.get(), "no spurious create for a never-attached session")
        assertTrue(c.state.value is TerminalController.State.Idle)
    }

    @Test
    fun `the cap exhausts into the honest Refused face - three young re-attaches then the flip`() = runTest {
        val c = controller()
        assertTrue(c.attach() is TerminalController.State.Live)
        // Each CONNECTED transition arrives young (the big-arm class: the stream kills
        // the transport within seconds of the attach). Deaths 1-3 re-fire; the 4th
        // flips the face honestly instead of re-firing the loop forever.
        c.onTransportConnected() // young death 1
        c.onTransportConnected() // young death 2
        c.onTransportConnected() // young death 3
        assertEquals(4, creates.get(), "1 user attach + 3 bounded re-attaches")
        assertTrue(c.state.value is TerminalController.State.Live)
        c.onTransportConnected() // young death 4 → exhausted
        assertEquals(4, creates.get(), "the cap must stop the re-fire loop")
        val refused = c.state.value as? TerminalController.State.Refused
        assertTrue(refused != null, "the honest face wins: state=${c.state.value}")
        assertTrue(
            (refused?.reason ?: "").contains("recovery"),
            "the refusal carries the recovery reason: ${refused?.reason}",
        )
        assertFalse(c.armed.value)
    }

    @Test
    fun `an aged Live recovers with the death counter reset`() = runTest {
        val c = controller()
        assertTrue(c.attach() is TerminalController.State.Live)
        ageLastAttach(c, ageMs = 2 * 60 * 60 * 1000) // the attach survived two hours
        c.onTransportConnected()
        assertEquals(2, creates.get(), "an aged session recovers normally")
        assertTrue(c.state.value is TerminalController.State.Live, "no exhaustion for an aged session")
    }

    @Test
    fun `the exit event disarms the arm (ADR-022 auto-disarm on Exited)`() = runTest {
        val c = controller()
        val live = c.attach() as TerminalController.State.Live
        c.setInputOptIn(true)
        c.arm()
        assertTrue(c.armed.value, "precondition: armed")
        exitHandler.invoke(0)
        assertTrue(c.state.value is TerminalController.State.Exited)
        assertEquals(live.terminalId, (c.state.value as TerminalController.State.Exited).terminalId)
        assertFalse(c.armed.value, "the session's exit ends its arm (the G104 static absence)")
    }

    @Test
    fun `attach while Live early-returns - a user tap never re-creates (recovery is the hook's job)`() = runTest {
        val c = controller()
        assertTrue(c.attach() is TerminalController.State.Live)
        c.attach()
        assertEquals(1, creates.get(), "the user-tap attach stays a no-op while Live")
    }
}
