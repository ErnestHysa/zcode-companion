package com.zcode.android.core.transport

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.tools.mockzcode.MockZCode
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.Test
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After

/**
 * FIELD regression (2026-10-04, vc33 Samsung S24 — "task duplicated into another
 * workspace"): the desktop's bootstrap `initialViewState.activeTaskId` and
 * `activeWorkspaceKey` are INDEPENDENT fields — the active task can live in a
 * different workspace than the active workspace key. The old bridge-open sent
 * exactly that mismatched pair (field log: `bridge.open ws=…/android-native
 * task=sess_53e16b04` where the task lived in …/pragma), and the desktop's resume
 * semantics FORKED the task into the wrong workspace (the fork sat idle; the
 * original kept running).
 *
 * The fix: the bridged workspace is resolved FROM the bridged task (§7.11 identity
 * rule — the pair is consistent by construction). This test scripts the exact
 * mismatch and asserts the bridge-open carries the TASK's workspace.
 */
class RelayTransportBridgePairConsistencyTest {

    private val passHash = Base64.getEncoder().encodeToString(ByteArray(32) { 43 })
    private val sid = "d_BridgePairSyn1"
    private var mock: MockZCode? = null

    @After
    fun tearDown() {
        mock?.stop()
    }

    private fun transport(): RelayTransport = RelayTransport(
        log = ZLog(sink = {}),
        config = RelayTransport.Config(
            connectTimeoutMs = 5_000, authTimeoutMs = 5_000, bridgeTimeoutMs = 5_000,
            channelInitTimeoutMs = 5_000, controlRequestTimeoutMs = 5_000,
            heartbeatIntervalMs = 200, heartbeatWatchdogMs = 1_500, ackResendMs = 250,
            v4CallTimeoutMs = 5_000,
        ),
    )

    private fun endpoint(url: String) = EndpointDescriptor(
        id = "ep-bridge-pair", kind = EndpointKind.RELAY_ZAI, displayName = "Pair",
        wsUrl = url, deviceSid = sid, deviceMid = "mock-desktop-mid", desktopAppVersion = "3.14.3",
    )

    @Test
    fun `bridge-open resolves the workspace from the active task, never the mismatched view key`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true
        // THE FIELD MISMATCH: the desktop's active WORKSPACE key is android-native,
        // but its active TASK lives in pragma (bootstrap tasks carry per-task
        // workspacePath). The old code bridged (android-native, pragma-task).
        m.script.bootstrapActiveWorkspaceKey = "/synthetic/android-native"
        m.script.bootstrapActiveTaskId = "task-in-pragma"
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "task-in-pragma",
                title = "Running task",
                workspacePath = "/Users/ernest/Desktop/pragma",
            ),
        )
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                // The pair must be CONSISTENT: the task's own workspace, not the
                // desktop's unrelated activeWorkspaceKey.
                assertEquals("task-in-pragma", m.lastBridgeTaskId)
                assertEquals(
                    "/Users/ernest/Desktop/pragma",
                    m.lastBridgeWorkspaceKey,
                    "bridge workspace must come FROM the task (§7.11), not the mismatched view key",
                )
            } finally {
                t.close("test done")
            }
        }
    }

    @Test
    fun `named active task missing from bootstrap list keeps the desktop-named pair (round-5 contract)`() {
        val m = MockZCode(passHash = passHash, expectedSid = sid)
        m.script.bridgeRequireTaskId = true
        // The named active task is NOT in the bootstrap list (the list can be
        // filtered/paged) — its workspace is unverifiable, but the desktop NAMED it
        // (round-5 verified contract): bridge the named pair as-is. The consistent-
        // pair override applies ONLY when the list proves a mismatch.
        m.script.bootstrapActiveWorkspaceKey = "/synthetic/android-native"
        m.script.bootstrapActiveTaskId = "task-not-listed"
        m.script.bootstrapTasks = listOf(
            MockZCode.MockTask(
                taskId = "first-listed",
                title = "First",
                workspacePath = "/Users/ernest/Desktop/first-ws",
            ),
        )
        mock = m
        val url = m.start()
        val t = transport()
        runBlocking {
            try {
                t.connect(endpoint(url)) { nonce, role, s -> MockZCode.hmacProof(passHash, nonce, role, s) }
                withTimeout(20_000) { t.states.first { it == ConnectionStateMachine.ConnectionState.CONNECTED } }
                assertEquals("task-not-listed", m.lastBridgeTaskId)
                assertEquals("/synthetic/android-native", m.lastBridgeWorkspaceKey)
            } finally {
                t.close("test done")
            }
        }
    }
}
