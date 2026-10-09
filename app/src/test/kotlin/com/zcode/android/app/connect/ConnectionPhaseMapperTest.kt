package com.zcode.android.app.connect

import com.zcode.android.core.transport.ConnectionStateMachine
import com.zcode.android.domain.state.ConnectionPhase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §14.4.1 machine → UI mirror mapping is TOTAL (6.5: every machine state visible honestly). */
class ConnectionPhaseMapperTest {

    @Test
    fun `every machine state maps 1-1`() {
        val all = ConnectionStateMachine.ConnectionState.entries
        val mapped = all.associateWith { connectionPhaseOf(it) }
        assertEquals(all.size, mapped.size)
        // No two machine states collapse into one UI phase.
        assertEquals(all.size, mapped.values.toSet().size)
        assertEquals(
            setOf(
                ConnectionPhase.UNPAIRED, ConnectionPhase.PAIRING, ConnectionPhase.AUTHENTICATING,
                ConnectionPhase.CONNECTING, ConnectionPhase.CONNECTED, ConnectionPhase.RECONNECTING,
                ConnectionPhase.RECOVERING, ConnectionPhase.DEGRADED, ConnectionPhase.FAILED, ConnectionPhase.CLOSED,
            ),
            mapped.values.toSet(),
        )
    }

    @Test
    fun `spot checks match the machine table`() {
        assertEquals(ConnectionPhase.CONNECTED, connectionPhaseOf(ConnectionStateMachine.ConnectionState.CONNECTED))
        assertEquals(ConnectionPhase.RECONNECTING, connectionPhaseOf(ConnectionStateMachine.ConnectionState.RECONNECTING))
        assertEquals(ConnectionPhase.FAILED, connectionPhaseOf(ConnectionStateMachine.ConnectionState.FAILED))
        assertTrue(connectionPhaseOf(ConnectionStateMachine.ConnectionState.DEGRADED) == ConnectionPhase.DEGRADED)
    }
}
