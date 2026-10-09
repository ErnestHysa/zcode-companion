package com.zcode.android.core.transport

import com.zcode.android.core.transport.ConnectionStateMachine.ConnectionEvent
import com.zcode.android.core.transport.ConnectionStateMachine.ConnectionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 2.1 acceptance: the machine matches §14.4.1 EXACTLY — every table row, the four
 * illegal transitions, anomaly-rejection of unlisted transitions, and the
 * scenario-driven acquisition-loss extensions (documented in the gate doc).
 */
class ConnectionStateMachineTest {

    private fun fullWalk(): ConnectionStateMachine {
        val m = ConnectionStateMachine()
        m.on(ConnectionEvent.QR_VALIDATED)          // → PAIRING
        m.on(ConnectionEvent.CREDENTIAL_SEALED)     // → AUTHENTICATING
        m.on(ConnectionEvent.AUTH_MATCHED)          // → CONNECTING
        m.on(ConnectionEvent.HELLO_RECEIVED)        // → CONNECTED
        return m
    }

    // ---------------------------------------------------------- §14.4.1 table rows

    @Test
    fun `every legal table row applies`() {
        val m = ConnectionStateMachine()
        assertEquals(ConnectionState.PAIRING, m.on(ConnectionEvent.QR_VALIDATED).to)
        assertEquals(ConnectionState.AUTHENTICATING, m.on(ConnectionEvent.CREDENTIAL_SEALED).to)
        assertEquals(ConnectionState.CONNECTING, m.on(ConnectionEvent.AUTH_MATCHED).to)
        assertEquals(ConnectionState.CONNECTED, m.on(ConnectionEvent.HELLO_RECEIVED).to)

        assertEquals(ConnectionState.RECONNECTING, m.on(ConnectionEvent.SOCKET_LOST).to)
        assertEquals(ConnectionState.RECOVERING, m.on(ConnectionEvent.WS_AUTH_OK).to)
        assertEquals(ConnectionState.CONNECTED, m.on(ConnectionEvent.RESYNC_GAP_FREE).to)

        m.on(ConnectionEvent.SOCKET_LOST)
        m.on(ConnectionEvent.WS_AUTH_OK)
        // epoch mismatch / gap unresolvable → snapshot replace → Connected
        assertEquals(ConnectionState.CONNECTED, m.on(ConnectionEvent.RESYNC_SNAPSHOT_REPLACED).to)

        assertEquals(ConnectionState.DEGRADED, m.on(ConnectionEvent.DESKTOP_DISCONNECTED).to)
        assertEquals(ConnectionState.RECONNECTING, m.on(ConnectionEvent.RETRY_REQUESTED).to)

        val m2 = fullWalk()
        m2.on(ConnectionEvent.SOCKET_LOST)
        assertEquals(ConnectionState.DEGRADED, m2.on(ConnectionEvent.RECONNECT_BUDGET_EXHAUSTED).to)

        val m3 = ConnectionStateMachine()
        m3.on(ConnectionEvent.QR_VALIDATED)
        m3.on(ConnectionEvent.CREDENTIAL_SEALED)
        assertEquals(ConnectionState.FAILED, m3.on(ConnectionEvent.AUTH_FAILED).to)
        assertEquals(ConnectionState.PAIRING, m3.on(ConnectionEvent.RESCAN_REQUESTED).to)
    }

    @Test
    fun `unpair from any live state lands in absorbing Closed`() {
        for (seed in listOf(
            ConnectionState.UNPAIRED, ConnectionState.PAIRING, ConnectionState.AUTHENTICATING,
            ConnectionState.CONNECTING, ConnectionState.CONNECTED, ConnectionState.RECONNECTING,
            ConnectionState.RECOVERING, ConnectionState.DEGRADED, ConnectionState.FAILED,
        )) {
            val m = ConnectionStateMachine()
            m.seed(seed)
            assertEquals(ConnectionState.CLOSED, m.on(ConnectionEvent.UNPAIR_OR_WIPE).to, "from $seed")
            // Closed is absorbing: any further event throws (identity destroyed)
            assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
                m.on(ConnectionEvent.RETRY_REQUESTED)
            }
        }
    }

    // ---------------------------------------------------------- illegal transitions

    @Test
    fun `illegal_ Pairing to Connected must authenticate`() {
        val m = ConnectionStateMachine()
        m.on(ConnectionEvent.QR_VALIDATED)
        val ex = assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.HELLO_RECEIVED)
        }
        assertEquals(ConnectionState.PAIRING, ex.from)
        assertEquals(ConnectionState.PAIRING, m.state) // unchanged after illegal
    }

    @Test
    fun `illegal_ Degraded to Recovering must pass through Reconnecting`() {
        val m = fullWalk()
        m.on(ConnectionEvent.DESKTOP_DISCONNECTED)
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.WS_AUTH_OK)
        }
        assertEquals(ConnectionState.DEGRADED, m.state)
    }

    @Test
    fun `illegal_ Failed to Connected must re-pair`() {
        val m = ConnectionStateMachine()
        m.on(ConnectionEvent.QR_VALIDATED)
        m.on(ConnectionEvent.CREDENTIAL_SEALED)
        m.on(ConnectionEvent.AUTH_FAILED)
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.HELLO_RECEIVED)
        }
        assertEquals(ConnectionState.FAILED, m.state)
    }

    @Test
    fun `illegal_ Closed absorbs everything`() {
        val m = fullWalk()
        m.on(ConnectionEvent.UNPAIR_OR_WIPE)
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.SOCKET_LOST)
        }
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.CREDENTIAL_SEALED)
        }
    }

    // ---------------------------------------------------------- anomaly logging

    @Test
    fun `unlisted transitions are anomaly-logged and rejected`() {
        val anomalies = mutableListOf<Pair<ConnectionState, ConnectionEvent>>()
        val m = ConnectionStateMachine(onAnomaly = { from, event -> anomalies.add(from to event) })
        m.on(ConnectionEvent.QR_VALIDATED)
        // Pairing + AUTH_MATCHED is unlisted (auth happens in Authenticating)
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.AUTH_MATCHED)
        }
        assertEquals(listOf(ConnectionState.PAIRING to ConnectionEvent.AUTH_MATCHED), anomalies)
        assertEquals(ConnectionState.PAIRING, m.state)
    }

    // ---------------------------------------------------------- scenario rows (S1/S4)

    @Test
    fun `S1_ socket loss during acquisition routes to Reconnecting`() {
        for (seed in listOf(ConnectionState.AUTHENTICATING, ConnectionState.CONNECTING)) {
            val m = ConnectionStateMachine()
            m.seed(seed)
            assertEquals(ConnectionState.RECONNECTING, m.on(ConnectionEvent.SOCKET_LOST).to, "from $seed")
        }
    }

    @Test
    fun `S4_ socket loss during recovery returns to Reconnecting`() {
        val m = fullWalk()
        m.on(ConnectionEvent.SOCKET_LOST)
        m.on(ConnectionEvent.WS_AUTH_OK)
        assertEquals(ConnectionState.RECOVERING, m.state)
        assertEquals(ConnectionState.RECONNECTING, m.on(ConnectionEvent.SOCKET_LOST).to)
    }

    @Test
    fun `full happy lifecycle ends connected and closed cleanly`() {
        val m = fullWalk()
        assertEquals(ConnectionState.CONNECTED, m.state)
        m.on(ConnectionEvent.UNPAIR_OR_WIPE)
        assertEquals(ConnectionState.CLOSED, m.state)
    }

    @Test
    fun `duplicate socket loss while already Reconnecting is rejected as anomaly`() {
        val anomalies = mutableListOf<Pair<ConnectionState, ConnectionEvent>>()
        val m = ConnectionStateMachine(onAnomaly = { f, e -> anomalies.add(f to e) })
        m.on(ConnectionEvent.QR_VALIDATED)
        m.on(ConnectionEvent.CREDENTIAL_SEALED)
        m.on(ConnectionEvent.AUTH_MATCHED)
        m.on(ConnectionEvent.HELLO_RECEIVED)
        m.on(ConnectionEvent.SOCKET_LOST)
        assertFailsWith<ConnectionStateMachine.IllegalTransitionException> {
            m.on(ConnectionEvent.SOCKET_LOST)
        }
        assertEquals(ConnectionState.RECONNECTING, m.state)
        assertTrue(anomalies.isNotEmpty())
    }
}
