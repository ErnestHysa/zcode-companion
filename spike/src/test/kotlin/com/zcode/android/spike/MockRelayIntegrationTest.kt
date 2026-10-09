package com.zcode.android.spike

import com.zcode.android.core.protocol.relay.RelayFrameCodec
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 1 E2E against the mock relay (S35.4 incremental rule — Phase-1 scenarios only):
 * terminal-role handshake → bootstrap → workspace list → bridge open → rpc-frame decode.
 * Skips (reported, never silently) when node is unavailable.
 */
class MockRelayIntegrationTest {

    private val passHash = "c3ludGhldGljLXBhc3NoYXNoLWtleQ==" // base64("synthetic-passhash-key")
    private val sid = "d_syntheticdevice000001"

    @Test
    fun `handshake bootstrap bridge and frame decode against mock relay`() {
        val node = listOf("/usr/bin/env", "node").let { listOf("/usr/bin/env", "node") }
        val mockScript = sequenceOf(File(".."), File("."))
            .map { it.resolve("tools/mockrelay/mock-relay.mjs") }
            .firstOrNull { it.isFile } ?: error("mock-relay.mjs not found")
        val nodeAvailable = kotlin.runCatching {
            val p = ProcessBuilder("node", "--version").start()
            p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0
        }.getOrDefault(false)
        if (!nodeAvailable) {
            System.err.println("SKIP: node unavailable — mock-relay integration test not executed")
            return
        }
        val port = 18090 + (0..999).random()
        val proc = ProcessBuilder(
            "node", mockScript.absolutePath,
            "--port", port.toString(), "--pass-hash", passHash, "--sid", sid,
        ).redirectErrorStream(true).start()
        try {
            // wait for the mock to listen
            val deadline = System.currentTimeMillis() + 10_000
            var up = false
            while (System.currentTimeMillis() < deadline && !up) {
                runCatching {
                    java.net.Socket("127.0.0.1", port).close()
                    up = true
                }
                if (!up) Thread.sleep(100)
            }
            assertTrue(up, "mock relay did not start")

            val qr = "https://zcode.z.ai/remote/v4?sid=$sid&hash=$passHash&t=${System.currentTimeMillis()}&mid=mockmid&name=Mock&app_version=3.14.3"
            val params = PairingParams.parse(qr, nowMs = System.currentTimeMillis())
            val captureDir = kotlin.io.path.createTempDirectory("spike-it").toFile()
            val client = RelayAuthClient(
                "ws://127.0.0.1:$port", params, CredentialSigner(passHash),
                CaptureSink(File(captureDir, "it.jsonl")),
            ) { }
            try {
                val status = client.connectAndAuthenticate()
                assertTrue(status == "matched", "expected matched, got $status")
                val baseline = client.rpcMessageCount()

                client.sendBootstrapRequest("it-bootstrap")
                val bootstrap = client.awaitControl("bootstrap-response", 10_000)
                assertNotNull(bootstrap, "no bootstrap-response")
                assertTrue(bootstrap.toString().contains("windowControlSessionId"))

                client.sendWorkspaceListRequest("it-list")
                val list = client.awaitControl("workspace-list-response", 10_000)
                assertNotNull(list, "no workspace-list-response")
                assertTrue(list.toString().contains("workspaces"))

                client.sendWorkspaceBridgeOpen("it-bridge", workspaceKey = "synthetic-ws-one")
                val ready = client.awaitControl("workspace-bridge-ready", 10_000)
                assertNotNull(ready, "no workspace-bridge-ready")
                val frame = client.awaitRpcMessageSince(baseline, 10_000)
                assertNotNull(frame, "no rpc logical frame decoded")
            } finally {
                client.close()
            }
        } finally {
            proc.destroy()
            proc.waitFor(3, TimeUnit.SECONDS)
        }
    }
}
