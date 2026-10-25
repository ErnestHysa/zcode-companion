package com.zcode.android.data.session

import com.zcode.android.core.transport.AppTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * ADR-022 (Phase 5D) controller pins: the four-layer gate — arm requires BOTH the input
 * opt-in AND a Live session; `write`/`resize` throw (the typed IllegalStateException gate)
 * while disarmed with ZERO wire calls; every write re-arms (the idle window extends);
 * disarm clears; disabling layer 3 disarms.
 */
class TerminalArmGateTest {

    private val recordedWrites = mutableListOf<String>()
    private val recordedResizes = mutableListOf<Pair<Int, Int>>()

    /** A Proxy-based AppTransport that records ONLY the ADR-022 words. */
    private fun recordingTransport(): AppTransport {
        val handler = java.lang.reflect.InvocationHandler { _, method, args ->
            when (method.name) {
                "terminalWrite" -> { recordedWrites += args[1] as String; JsonObject(emptyMap()) }
                "terminalResize" -> { recordedResizes += (args[1] as Int) to (args[2] as Int); JsonObject(emptyMap()) }
                "terminalCreate" -> JsonObject(emptyMap())
                else -> null
            }
        }
        return java.lang.reflect.Proxy.newProxyInstance(
            AppTransport::class.java.classLoader, arrayOf(AppTransport::class.java), handler,
        ) as AppTransport
    }

    private fun controller(live: Boolean): TerminalController {
        val c = TerminalController(recordingTransport())
        if (live) {
            runTest { c.setInputOptIn(true) }
            TerminalControllers.setLiveForTest(c, "term-1")
        }
        return c
    }

    @Test
    fun `write and resize throw while disarmed with zero wire calls`() {
        val c = controller(live = true)
        val e = assertFailsWith<IllegalStateException> { kotlinx.coroutines.runBlocking { c.write("ls\n") } }
        assertTrue((e.message ?: "").contains("not armed"))
        assertTrue(recordedWrites.isEmpty(), "ZERO wire calls while disarmed (the ADR pin)")
        assertFailsWith<IllegalStateException> { kotlinx.coroutines.runBlocking { c.resize(120, 32) } }
        assertTrue(recordedResizes.isEmpty())
    }

    @Test
    fun `arm requires the input opt-in`() {
        val c = controller(live = false)
        kotlinx.coroutines.runBlocking {
            c.setInputOptIn(false)
            c.arm()
        }
        assertFalse(c.armed.value, "opt-in off → arm is a no-op")
    }

    @Test
    fun `an armed write rides the wire re-arms and disarm clears`() {
        val c = controller(live = true)
        kotlinx.coroutines.runBlocking {
            c.arm()
            assertTrue(c.armed.value)
            c.write("ls\n")
        }
        assertEquals(listOf("ls\n"), recordedWrites)
        assertTrue(c.armed.value, "the write re-arms (idle window extends)")
        c.disarm()
        assertFalse(c.armed.value)
        assertFailsWith<IllegalStateException> { kotlinx.coroutines.runBlocking { c.write("rm -rf /\n") } }
        assertEquals(listOf("ls\n"), recordedWrites, "post-disarm writes never reach the wire")
    }

    @Test
    fun `disabling the input opt-in disarms`() {
        val c = controller(live = true)
        kotlinx.coroutines.runBlocking {
            c.arm()
            assertTrue(c.armed.value)
            c.setInputOptIn(false)
        }
        assertFalse(c.armed.value, "layer 3 off → disarm")
    }
}

/** Test seam: the controller's state flow is driven by attach; tests seed Live directly. */
object TerminalControllers {
    fun setLiveForTest(c: TerminalController, terminalId: String) {
        val field = TerminalController::class.java.getDeclaredField("_state")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val flow = field.get(c) as kotlinx.coroutines.flow.MutableStateFlow<TerminalController.State>
        flow.value = TerminalController.State.Live(terminalId, shell = "/bin/zsh")
    }
}
