package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * G43-P13 (vc52 Phase 11): the reconnect ladder RESYNCs the sessions-index and the
 * workspace-config. The phone subscribed both but never called the desktop's
 * resyncSessionsIndexV4/resyncWorkspaceConfigV4 — post-reconnect freshness relied on
 * the next pushed patch (one hub-lifetime subscriptions). The ladder now RESYNC
 * FIRST (preserves an owned subscription); an unowned record (a relay RESTART — the
 * recorded scenario) faults and falls back to the re-subscribe, so a relay restart
 * fires BOTH resyncs on the wire AND lands freshness either way.
 */
class RelayTransportIndexConfigResyncTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 21 })
    private val sid = "d_IdxResyncSynthetic1"

    /** The abort's ladder is asynchronous — poll the wire ledger for the verb (10 s cap). */
    private suspend fun awaitLedger(mock: MockZCode, names: Set<String>): List<Pair<String, kotlinx.serialization.json.JsonObject>> {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val ledger = mock.v4CallsForTest()
            if (names.all { n -> ledger.any { it.first == n } }) return ledger
            kotlinx.coroutines.delay(50)
        }
        return mock.v4CallsForTest()
    }

    @Test
    fun `a relay restart fires both resyncs and lands freshness via the fallback`() = runBlocking {
        val mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-idxresync", kind = EndpointKind.RELAY_ZAI, displayName = "IdxResync",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                // The two hub-lifetime subscriptions (GAP-A3's index pair + the config).
                t.subscribeSessionsIndex("/synthetic/ws-one", null)
                t.subscribeWorkspaceConfig("/synthetic/ws-one", null)
                val subscribeLedger = mock.v4CallsForTest().count { it.first == "subscribeSessionsIndexV4" || it.first == "subscribeWorkspaceConfigV4" }
                assertTrue(subscribeLedger >= 2, "both subscriptions registered first")
                mock.resetCallLedgerForTest()

                // The recorded scenario: a RELAY RESTART — a connection loss drives the
                // recovery ladder; the new relay session owns nothing, so the resync
                // faults notOwned and the ladder falls back to the re-subscribe. BOTH
                // resync verbs still FIRE on the wire.
                mock.script.resyncIndexFault = "fault.subscription.notOwned"
                t.abortConnectionForTest() // relay.lost — the ladder runs automatically
                val ledger = awaitLedger(mock, setOf("resyncSessionsIndexV4", "resyncWorkspaceConfigV4"))
                assertTrue(
                    ledger.any { it.first == "resyncSessionsIndexV4" },
                    "the ladder fired resyncSessionsIndexV4; saw ${ledger.map { it.first }}",
                )
                assertTrue(
                    ledger.any { it.first == "resyncWorkspaceConfigV4" },
                    "the ladder fired resyncWorkspaceConfigV4; saw ${ledger.map { it.first }}",
                )
                assertTrue(
                    ledger.any { it.first == "subscribeSessionsIndexV4" },
                    "the not-owned resync fell back to the re-subscribe (freshness either way)",
                )
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
        Unit
    }

    @Test
    fun `an owned subscription resyncs without re-subscribing`() = runBlocking {
        val mock = MockZCode(passHash = passHash, expectedSid = sid)
        val url = mock.start()
        val t = RelayTransport(
            log = ZLog(sink = {}),
            config = RelayTransport.Config(
                connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
                channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
                heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
                v4CallTimeoutMs = 5_000,
            ),
        )
        try {
            withTimeout(30_000) {
                t.connect(
                    EndpointDescriptor(
                        id = "ep-idxresync2", kind = EndpointKind.RELAY_ZAI, displayName = "IdxResync2",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                t.subscribeSessionsIndex("/synthetic/ws-one", null)
                t.subscribeWorkspaceConfig("/synthetic/ws-one", null)
                mock.resetCallLedgerForTest()
                // The same relay session still owns the subscriptions (the mock keeps
                // ownership across the abort — no fault scripted) → the resync succeeds
                // and NO re-subscribe runs (no re-ownership churn).
                t.abortConnectionForTest()
                val ledger = awaitLedger(mock, setOf("resyncSessionsIndexV4", "resyncWorkspaceConfigV4"))
                assertTrue(ledger.any { it.first == "resyncSessionsIndexV4" }, "index resync fired")
                assertTrue(ledger.any { it.first == "resyncWorkspaceConfigV4" }, "config resync fired")
                assertEquals(
                    0,
                    ledger.count { it.first == "subscribeSessionsIndexV4" || it.first == "subscribeWorkspaceConfigV4" },
                    "an owned subscription never re-subscribes",
                )
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
        Unit
    }
}
