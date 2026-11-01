package com.zcode.android.app.update

import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import com.zcode.android.domain.state.ConnectionPhase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LEDGER-SMALLS (c, vc51 Phase 4A): the app-open update check must not fire on the
 * vault-SEED emission that races the connect bootstrap (the recorded first-launch
 * `update.check desktop=null` shape). Paired+settled fires; paired+pre-bootstrap
 * skips; unpaired (no endpoint) fires immediately.
 */
class UpdateCheckGateTest {

    private fun endpoint(version: String?) = Endpoint(
        endpointId = EndpointId("ep-1"),
        kind = EndpointKind.RELAY_ZAI,
        displayName = "Desk",
        wsUrl = "wss://relay",
        pairedAtMs = 0L,
        desktopAppVersion = version,
    )

    @Test
    fun `seeded paired endpoint before the bootstrap settles does not fire (the race shape)`() {
        assertFalse(UpdateCheckGate.shouldFire(endpoint(null), ConnectionPhase.CONNECTING))
        assertFalse(UpdateCheckGate.shouldFire(endpoint(null), ConnectionPhase.RECONNECTING))
        assertFalse(UpdateCheckGate.shouldFire(endpoint(null), ConnectionPhase.DEGRADED))
    }

    @Test
    fun `settled paired endpoint fires (with or without a patched version)`() {
        assertTrue(UpdateCheckGate.shouldFire(endpoint("3.14.5"), ConnectionPhase.CONNECTED))
        assertTrue(UpdateCheckGate.shouldFire(endpoint(null), ConnectionPhase.CONNECTED))
    }

    @Test
    fun `unpaired app-open fires immediately (2026-10-12 every-app-open contract)`() {
        assertTrue(UpdateCheckGate.shouldFire(null, ConnectionPhase.UNPAIRED))
        assertTrue(UpdateCheckGate.shouldFire(null, ConnectionPhase.CONNECTING))
    }
}
