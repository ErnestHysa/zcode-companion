package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import java.io.File

/**
 * AAP-5 prerequisite — the Mac live canary as ONE scripted stage (report §14.2 T2:
 * "the existing one-session canary drill (pair → subscribe → stream → recover →
 * diagnostics export), automated as a script").
 *
 * vc34 cold-resume guards (normative — the drill must never fork tasks on the live
 * desktop):
 *  1. FIXED workspace resolution: the workspace is matched by --workspace-match
 *     against the bootstrap list — 0 or >1 matches REFUSE the run (no
 *     first-workspace guessing, ever).
 *  2. DISPOSABLE SYNTHETIC TASK: the subscribe target must match --task-match
 *     (e.g. a task titled "canary-*"); no match refuses the run.
 *  3. INV-15: operator-machine ritual — tools/canary/live-canary.sh wraps it;
 *     CI never runs it.
 *
 * With --mock the v4 channel handshake legs (hello/initialize/subscribe calls)
 * are skipped — the MockRelay rehearsal answers pair/bridge/pushed-frame/recovery
 * but not channel calls; the report records exactly which legs ran.
 *
 * Emits `<captureId>.canary.json` next to the capture:
 * `{stage, verdict, mode, legs:{pair,workspaceGuard,discover,stream,v4Handshake,recover}, detail}`.
 */
fun runCanaryStage(
    opts: Map<String, String>,
    relayUrl: String,
    params: PairingParams,
    capture: CaptureSink,
    log: (String) -> Unit,
) {
    val workspaceMatch = opts["workspace-match"]
        ?: error("canary: --workspace-match required (vc34 guard: fixed workspace resolution)")
    val taskMatch = opts["task-match"]
        ?: error("canary: --task-match required (vc34 guard: disposable synthetic task)")
    val mockMode = opts.containsKey("mock")
    val captureFile = File(opts["capture-dir"]!!, "${opts["capture-id"] ?: "capture"}.jsonl")
    val reportFile = File(captureFile.parentFile, captureFile.nameWithoutExtension + ".canary.json")

    class Legs {
        var pair = false
        var workspaceGuard = "not-run"
        var discover = false
        var stream = false
        var v4Handshake = false
        var recover = false
    }
    val legs = Legs()

    fun writeReport(verdict: String, detail: String) {
        // Hand-built JSON (machine-readable envelope consumed by live-canary.sh);
        // all values are simple strings/booleans — quote + escape the free-text one.
        val esc = detail.replace("\\", "\\\\").replace("\"", "\\\"")
        val mode = if (mockMode) "mock-rehearsal" else "live"
        reportFile.writeText(
            "{\"stage\":\"canary\",\"verdict\":\"$verdict\",\"mode\":\"$mode\"," +
                "\"legs\":{\"pair\":${legs.pair},\"workspaceGuard\":\"${legs.workspaceGuard}\"," +
                "\"discover\":${legs.discover},\"stream\":${legs.stream}," +
                "\"v4Handshake\":${legs.v4Handshake},\"recover\":${legs.recover}}," +
                "\"detail\":\"$esc\"}",
        )
        log("CANARY-REPORT $reportFile verdict=$verdict")
    }

    var workspaceKey: String? = null
    var sessionId: String? = null

    // ── legs 1+2: pair + discover under the vc34 guards ─────────────────────
    val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, log)
    try {
        client.connectAndAuthenticate(deviceName = opts["name"] ?: "zcode-android-canary")
        legs.pair = true
        log("canary pair: ok (pairStatus=${client.pairStatus})")

        val bootstrap = canaryRequest(client, "bootstrap-response") {
            client.sendBootstrapRequest(requestId = "spike-canary-boot")
        }
        val result = bootstrap?.get("result") as? kotlinx.serialization.json.JsonObject
        val workspaces = result?.get("workspaces") as? kotlinx.serialization.json.JsonArray
        if (workspaces == null) {
            writeReport("red", "bootstrap returned no workspace list")
            return
        }
        val matches = workspaces.mapNotNull { w ->
            val o = w as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
            val path = (o["workspacePath"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            val label = (o["label"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            if (workspaceMatch in path || workspaceMatch in label) o else null
        }
        if (matches.isEmpty()) {
            legs.workspaceGuard = "refused:no-match"
            writeReport("red", "vc34 guard: no workspace matches '$workspaceMatch' — refusing (never guess)")
            return
        }
        if (matches.size > 1) {
            legs.workspaceGuard = "refused:ambiguous(" + matches.size + ")"
            writeReport("red", "vc34 guard: " + matches.size + " workspaces match '$workspaceMatch' — refusing (must be exactly one)")
            return
        }
        legs.workspaceGuard = "ok"
        legs.discover = true
        val ws = matches.first()
        val wsPath = (ws["workspacePath"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val wsIdent = (ws["workspaceIdentity"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.ifEmpty { null }
        workspaceKey = wsIdent ?: wsPath
        log("canary workspace: $wsPath (fixed resolution ok)")

        // Disposable synthetic task guard.
        val tasks = result["tasks"] as? kotlinx.serialization.json.JsonArray
        val taskObj = tasks?.mapNotNull { it as? kotlinx.serialization.json.JsonObject }
            ?.firstOrNull { t ->
                val id = (t["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                val title = (t["title"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                taskMatch in id || taskMatch in title
            }
        if (taskObj == null) {
            writeReport("red", "vc34 guard: no disposable task matches '$taskMatch' — refusing")
            return
        }
        sessionId = (taskObj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content
        log("canary task: $sessionId (synthetic match ok)")

        // ── leg 3: bridge + stream (first pushed frame) ─────────────────────
        // Stream evidence = a pushed rpc-frame arriving after the bridge-open,
        // counted by the client's per-direction decoded-message counter — the
        // same signal the recover stage uses. (A Channel EventListen id-match is
        // NOT usable here: the mock fires a fixed id and the live desktop
        // assigns its own; the counter is transport-truth for both.)
        val rpcBaseline = client.rpcMessageCount()
        try {
            if (!mockMode) client.awaitChannelReady(15_000)
            val ready = canaryRequest(client, "workspace-bridge-ready") {
                client.sendWorkspaceBridgeOpen(requestId = "spike-canary-bridge", workspaceKey = workspaceKey!!, taskId = sessionId)
            }
            if (ready == null) {
                writeReport("red", "workspace-bridge-ready timed out")
                return
            }
            if (!mockMode) {
                // ── leg 4: the v4 handshake (live only; the mock answers no channel calls)
                val hello = client.channel.call("zcode-agent", "helloConversationV4", RpcValue.Arr(emptyList()))
                awaitPromise(hello, 10_000)
                val clientHello = kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("clientHello"))
                    put("protocolVersion", kotlinx.serialization.json.JsonPrimitive(3))
                    put("clientId", kotlinx.serialization.json.JsonPrimitive("zcode-android-canary-" + java.util.UUID.randomUUID().toString().take(8)))
                    put("clientKind", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
                    put("appVersion", kotlinx.serialization.json.JsonPrimitive("canary"))
                    put("capabilities", kotlinx.serialization.json.buildJsonObject { })
                }
                val init = client.channel.call("zcode-agent", "initializeConversationV4", RpcValue.Arr(listOf(RpcValue.Obj(clientHello))))
                awaitPromise(init, 10_000)
                val subscribeParams = kotlinx.serialization.json.buildJsonObject {
                    put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
                    wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId!!))
                    put("visibility", kotlinx.serialization.json.JsonPrimitive("foreground"))
                }
                val sub = client.channel.call("zcode-agent", "subscribeConversationV4", RpcValue.Arr(listOf(RpcValue.Obj(subscribeParams))))
                awaitPromise(sub, 15_000)
                legs.v4Handshake = hello.isSuccess && sub.isSuccess
                if (!legs.v4Handshake) {
                    writeReport("red", "v4 handshake failed (hello=${hello.isSuccess} subscribe=${sub.isSuccess})")
                    return
                }
            }
            // Stream evidence: a real pushed frame within 20s (the mock pushes one at
            // bridge-open; the live desktop pushes on subscribe).
            legs.stream = client.awaitRpcMessageSince(rpcBaseline, 20_000) != null
            if (!legs.stream) {
                writeReport("red", "no pushed frame within 20s")
                return
            }
        } catch (e: Exception) {
            writeReport("red", "stream leg exception: ${e.javaClass.simpleName}: ${e.message?.take(160)}")
            return
        }
        client.close()
    } catch (e: Exception) {
        writeReport("red", "exception: ${e.javaClass.simpleName}: ${e.message?.take(200)}")
        runCatching { client.close() }
        return
    }

    // ── leg 5: recover — same bridge identity across a forced disconnect ────
    try {
        val key = workspaceKey
        if (key != null) {
            val recoveryId = RelayBridge.recoveryId()
            val c1 = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, log)
            c1.connectAndAuthenticate()
            c1.overrideRecovery(recoveryId)
            val ready1 = canaryRequest(c1, "workspace-bridge-ready") {
                c1.sendWorkspaceBridgeOpen(requestId = "spike-canary-r1", workspaceKey = key)
            }
            c1.forceKill()
            val c2 = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, log)
            c2.connectAndAuthenticate()
            c2.overrideRecovery(recoveryId)
            val ready2 = canaryRequest(c2, "workspace-bridge-ready") {
                c2.sendWorkspaceBridgeOpen(requestId = "spike-canary-r2", workspaceKey = key)
            }
            c2.close()
            legs.recover = ready1 != null && ready2 != null
        }
    } catch (e: Exception) {
        log("canary recover leg error: ${e.message?.take(160)}")
    }

    // ── leg 6: diagnostics export = the capture + this report (the wrapper
    // redacts + validates before anything leaves $TMPDIR; INV-16). ────────────
    val verdict = if (legs.pair && legs.workspaceGuard == "ok" && legs.discover && legs.stream && legs.recover) "green" else "red"
    writeReport(verdict, "drill complete")
}

/** Mirrors SpikeRunner's local requestWithRetry: resend with the SAME requestId
 *  until answered (idempotent request/response; the device re-pair race). */
private fun canaryRequest(
    client: RelayAuthClient,
    zcodeType: String,
    attempts: Int = 6,
    perAttemptMs: Long = 3_000,
    send: () -> Unit,
): kotlinx.serialization.json.JsonObject? {
    repeat(attempts) { i ->
        send()
        val answered = client.awaitControl(zcodeType, perAttemptMs)
        if (answered != null) {
            if (i > 0) println("[spike] canary: $zcodeType answered on retry $i")
            return answered
        }
    }
    return null
}
