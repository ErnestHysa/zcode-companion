package com.zcode.android.spike

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * Phase 1 spike driver. Stages map to §43.2-1.3…1.6 and the §46 open questions.
 *
 * Probe material (QR link) is supplied via SPIKE_QR_URL (env) or --qr — never stored
 * in the repository. Captures go to --capture-dir (must be outside the repo).
 */
fun main(args: Array<String>) {
    val opts = mutableMapOf<String, String>()
    var i = 0
    while (i < args.size - 1) {
        if (args[i].startsWith("--")) opts[args[i].removePrefix("--")] = args[i + 1]
        i++
    }
    val stage = opts["stage"] ?: "auth"
    val qrUrl = opts["qr"] ?: System.getenv("SPIKE_QR_URL")
    val relayUrl = opts["relay"] ?: "wss://zcode.z.ai/ws"
    val captureDir = opts["capture-dir"] ?: System.getenv("SPIKE_CAPTURE_DIR")
        ?: error("capture-dir required (keep raw captures outside the repository)")
    val captureId = opts["capture-id"] ?: "capture-" + System.currentTimeMillis()
    val loops = opts["loops"]?.toIntOrNull() ?: 10

    if (qrUrl.isNullOrBlank()) {
        System.err.println("SPIKE_QR_URL env or --qr required (fresh QR from the desktop dialog)")
        kotlin.system.exitProcess(2)
    }

    val params = try {
        PairingParams.parse(qrUrl, nowMs = System.currentTimeMillis())
    } catch (e: Exception) {
        System.err.println("QR rejected: ${e.message}")
        kotlin.system.exitProcess(2)
    }
    println("pairing params: sid=${params.deviceSid.take(4)}…${params.deviceSid.takeLast(4)} origin=${params.endpointOrigin} route=${params.route} app_version=${params.appVersion}")
    if (PairingParams.isPossiblyStale(params, nowMs = System.currentTimeMillis())) println("WARNING: QR timestamp older than 10 min (stale heuristic)")
    if (params.routeIsLegacyV3) println("WARNING: legacy /remote/v3 route (compatibility warning per §12.2)")

    val capture = CaptureSink(File(captureDir, "$captureId.jsonl"))
    val report = StringBuilder()

    fun log(line: String) {
        println("[spike] $line")
        report.appendLine(line)
    }

    when (stage) {
        "auth" -> {
            val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
            try {
                val status = client.connectAndAuthenticate(deviceName = opts["name"] ?: "zcode-android-spike")
                log("RESULT auth: pairStatus=$status closeCode=${client.lastCloseCode}")
                log("EVENTS ${client.events}")
            } finally {
                client.close()
            }
        }
        "negative-proof" -> {
            // Bounded single attempt with a deliberately wrong proof (Q3 evidence).
            val badSigner = CredentialSigner(params.passHash + "corrupted")
            val client = RelayAuthClient(relayUrl, params, badSigner, capture, ::log)
            try {
                client.connectAndAuthenticate()
                log("RESULT negative-proof: UNEXPECTED — bad proof accepted (pairStatus=${client.pairStatus})")
            } catch (e: SpikeAuthException) {
                log("RESULT negative-proof: rejected with code=${e.code}")
            } catch (e: Exception) {
                log("RESULT negative-proof: failed: ${e.message}")
            } finally {
                client.close()
            }
        }
        "discover" -> {
            val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
            try {
                client.connectAndAuthenticate()
                client.sendBootstrapRequest(requestId = "spike-bootstrap-1")
                val bootstrap = client.awaitControl("bootstrap-response", 15_000)
                log("RESULT bootstrap-response: ${bootstrap?.toString()?.take(2000) ?: "TIMEOUT"}")
                client.sendWorkspaceListRequest(requestId = "spike-workspace-list-1")
                val list = client.awaitControl("workspace-list-response", 15_000)
                log("RESULT workspace-list-response: ${list?.toString()?.take(2000) ?: "TIMEOUT"}")
                val firstWorkspace = list ?: bootstrap
                val workspaceKey = firstWorkspace?.get("result")?.let { r ->
                    (r as? kotlinx.serialization.json.JsonObject)?.get("workspaces")?.let { arr ->
                        (arr as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { w ->
                            ((w as? kotlinx.serialization.json.JsonObject)?.get("workspacePath")
                                ?: (w as? kotlinx.serialization.json.JsonObject)?.get("label"))?.toString()
                        }
                    }
                }
                log("workspace candidate key: $workspaceKey")
                if (workspaceKey != null) {
                    client.sendWorkspaceBridgeOpen(requestId = "spike-bridge-1", workspaceKey = workspaceKey.trim('"'))
                    val ready = client.awaitControl("workspace-bridge-ready", 15_000)
                    log("RESULT workspace-bridge-ready: ${ready?.toString()?.take(1500) ?: "TIMEOUT"}")
                    val error = client.awaitControl("workspace-bridge-error", 2_000)
                    if (error != null) log("RESULT workspace-bridge-error: $error")
                }
                log("EVENTS ${client.events}")
            } finally {
                client.close()
            }
        }
        "recover" -> {
            // §43.2-1.6: scripted disconnect/reconnect cycles; recoveryId resubscribe.
            var ok = 0
            val recoveryId = RelayBridge.recoveryId()
            repeat(loops) { attempt ->
                val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
                try {
                    client.connectAndAuthenticate()
                    client.overrideRecovery(recoveryId)
                    val baseline = client.rpcMessageCount()
                    client.sendWorkspaceBridgeOpen(
                        requestId = "spike-bridge-r$attempt",
                        workspaceKey = opts["workspace-key"] ?: "synthetic-ws-one",
                    )
                    val ready = client.awaitControl("workspace-bridge-ready", 10_000)
                    val frameBefore = client.awaitRpcMessageSince(baseline, 5_000)
                    client.forceKill()
                    // reconnect: new socket, same credential, same bridge identity (recoveryId)
                    val client2 = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
                    try {
                        client2.connectAndAuthenticate()
                        client2.overrideRecovery(recoveryId)
                        val baseline2 = client2.rpcMessageCount()
                        client2.sendWorkspaceBridgeOpen(
                            requestId = "spike-bridge-r${attempt}b",
                            workspaceKey = opts["workspace-key"] ?: "synthetic-ws-one",
                        )
                        val ready2 = client2.awaitControl("workspace-bridge-ready", 10_000)
                        val frameAfter = client2.awaitRpcMessageSince(baseline2, 5_000)
                        val recovered = frameBefore != null && frameAfter != null
                        if (recovered) ok += 1
                        log("recover attempt $attempt: ready=${ready != null}/${ready2 != null} frames=${frameBefore != null}/${frameAfter != null} -> ${if (recovered) "OK" else "FAIL"}")
                    } finally {
                        client2.close()
                    }
                } catch (e: Exception) {
                    log("recover attempt $attempt: ERROR ${e.message}")
                } finally {
                    client.close()
                }
            }
            log("RESULT recover: $ok/$loops recovery cycles correct")
        }
        "double-terminal" -> {
            // Q10: connect client A (first, expect matched), then client B; observe which
            // gets matched/kicked. Bounded, and both disconnect immediately after.
            val a = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
            val statusA = a.connectAndAuthenticate(deviceName = "spike-A")
            log("RESULT first terminal pairStatus=$statusA")
            val b = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
            val statusB = try {
                b.connectAndAuthenticate(deviceName = "spike-B")
            } catch (e: Exception) {
                "failed: ${e.message}"
            }
            log("RESULT second terminal pairStatus=$statusB")
            Thread.sleep(12_000) // let a heartbeat cycle reveal a KICKED on A if any
            log("RESULT first terminal after-second: pairStatus=${a.pairStatus} closeCode=${a.lastCloseCode} events=${a.events}")
            a.close()
            b.close()
        }
        else -> {
            System.err.println("unknown stage: $stage (auth|negative-proof|discover|subscribe|recover|double-terminal)")
            kotlin.system.exitProcess(2)
        }
    }
    File(captureDir, "$captureId.report.log").writeText(report.toString())
    println("report written to ${File(captureDir, "$captureId.report.log").absolutePath}")
}
