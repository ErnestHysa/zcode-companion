package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.protocol.relay.MobileSelfReport
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

/**
 * G68 (vc52 Phase 12) pin: the opt-in self-report frames ARRIVE on the desktop
 * (the mock records them — no response exists). The transport's sender drops
 * them when not connected; a connected send records BOTH the view-state update
 * and the diagnostic on the mock side with their wire shapes intact.
 */
class RelayTransportMobileSelfReportTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 21 })
    private val sid = "d_MobileSelfReport01"

    @Test
    fun `connected sends arrive and record on the desktop`() = runBlocking {
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
                        id = "ep-selfrep", kind = EndpointKind.RELAY_ZAI, displayName = "SelfRep",
                        wsUrl = url, workspaceKey = mock.workspace.sessionId, workspacePath = "/synthetic/ws-one",
                        deviceSid = sid, deviceMid = "mock-desktop-mid",
                    ),
                ) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED }
                t.sendMobileSelfReport(
                    MobileSelfReport.viewStateUpdate("synthetic-ws", "task-1", 1_700_000_000_000),
                )
                t.sendMobileSelfReport(
                    MobileSelfReport.diagnostic("socket-close", 1_700_000_000_001, state = "RECONNECTING", closeCode = 4000),
                )
                withTimeout(5_000) {
                    while (mock.selfReportViewStateCount() < 1 || mock.selfReportDiagnosticsCount() < 1) kotlinx.coroutines.delay(50)
                }
                assertEquals(1, mock.selfReportViewStateCount())
                assertEquals(1, mock.selfReportDiagnosticsCount())
            }
        } finally {
            kotlinx.coroutines.runBlocking { t.close("test done") }
            mock.stop()
        }
    }
}
