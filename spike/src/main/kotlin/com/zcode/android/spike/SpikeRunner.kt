package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
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
fun awaitPromise(p: com.zcode.android.core.protocol.channel.ChannelClient.Promise, timeoutMs: Long) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline && !p.isDone) Thread.sleep(50)
}

/** Redaction-aware summaries for console/report output — shapes and counts only. */
fun summary(obj: kotlinx.serialization.json.JsonObject): String {
    val zt = (obj["zcode_type"] as? kotlinx.serialization.json.JsonPrimitive)?.content
    val keys = obj.keys.joinToString(",")
    val result = obj["result"] as? kotlinx.serialization.json.JsonObject
    val wsCount = (result?.get("workspaces") as? kotlinx.serialization.json.JsonArray)?.size
    val taskCount = (result?.get("tasks") as? kotlinx.serialization.json.JsonArray)?.size
    return buildString {
        append("zcode_type=").append(zt).append(" keys=[").append(keys).append("]")
        if (wsCount != null) append(" workspaces=").append(wsCount)
        if (taskCount != null) append(" tasks=").append(taskCount)
    }
}

fun helloResultSummary(result: com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue?): String {
    val json = (result as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json
        as? kotlinx.serialization.json.JsonObject ?: return result?.javaClass?.simpleName ?: "null"
    val caps = json["capabilities"] as? kotlinx.serialization.json.JsonObject
    return "keys=[${json.keys.joinToString(",")}] protocolVersion=${(json["protocolVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content} deliveryProfile=${(json["deliveryProfile"] as? kotlinx.serialization.json.JsonPrimitive)?.content} capabilities=[${caps?.keys?.joinToString(",")}] binaryFrames=${(caps?.get("binaryFrames") as? kotlinx.serialization.json.JsonPrimitive)?.content} connectionId-present=${json["connectionId"] != null}"
}

fun rowsResultSummary(result: com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue?): String {
    val json = (result as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json
        as? kotlinx.serialization.json.JsonObject ?: return result?.javaClass?.simpleName ?: "null"
    val rows = json["rows"] as? kotlinx.serialization.json.JsonArray
    val kinds = rows?.mapNotNull { ((it as? kotlinx.serialization.json.JsonObject)?.get("kind") as? kotlinx.serialization.json.JsonPrimitive)?.content }?.groupingBy { it }?.eachCount()
    return "keys=[${json.keys.joinToString(",")}] rowCount=${rows?.size} kinds=$kinds"
}

fun bootstrapSummary(obj: kotlinx.serialization.json.JsonObject): String {
    val r = obj["result"] as? kotlinx.serialization.json.JsonObject ?: return "no result"
    val workspaces = r["workspaces"] as? kotlinx.serialization.json.JsonArray
    val tasks = r["tasks"] as? kotlinx.serialization.json.JsonArray
    val versions = workspaces?.mapNotNull { (it as? kotlinx.serialization.json.JsonObject)?.get("kind")?.toString() }
    return "windowControlSessionId=${r["windowControlSessionId"] != null} desktopAppVersion=${(r["desktopAppVersion"] as? kotlinx.serialization.json.JsonPrimitive)?.content} workspaces=${workspaces?.size} (kinds=$versions) tasks=${tasks?.size} hasInitialViewState=${r["initialViewState"] != null}"
}

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

    // The desktop's payload handler drops everything while its device socket is
    // un-paired; after a terminal connect the device re-pairs on its NEXT heartbeat
    // (~10s). The real SPA retries bootstrap with recoverTimeout — mirror that:
    // resend with the SAME requestId until answered (idempotent request/response).
    fun requestWithRetry(
        client: RelayAuthClient,
        send: () -> Unit,
        zcodeType: String,
        attempts: Int = 6,
        perAttemptMs: Long = 3_000,
    ): kotlinx.serialization.json.JsonObject? {
        var result: kotlinx.serialization.json.JsonObject? = null
        repeat(attempts) { i ->
            send()
            result = client.awaitControl(zcodeType, perAttemptMs)
            if (result != null) {
                if (i > 0) log("$zcodeType answered on retry $i (device re-pair race confirmed)")
                return result
            }
        }
        return result
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
                val bootstrap = requestWithRetry(client, { client.sendBootstrapRequest(requestId = "spike-bootstrap-1") }, "bootstrap-response")
                log("RESULT bootstrap-response: ${bootstrap?.let { summary(it) } ?: "TIMEOUT"}")
                val list = requestWithRetry(client, { client.sendWorkspaceListRequest(requestId = "spike-workspace-list-1") }, "workspace-list-response")
                log("RESULT workspace-list-response: ${list?.let { summary(it) } ?: "TIMEOUT"}")
                val firstWorkspace = list ?: bootstrap
                val workspaceKey = firstWorkspace?.get("result")?.let { r ->
                    (r as? kotlinx.serialization.json.JsonObject)?.get("workspaces")?.let { arr ->
                        (arr as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.let { w ->
                            ((w as? kotlinx.serialization.json.JsonObject)?.get("workspacePath")
                                ?: (w as? kotlinx.serialization.json.JsonObject)?.get("label"))?.toString()
                        }
                    }
                }
                log("workspace candidate key: present=${workspaceKey != null}")
                if (bootstrap != null) log("RESULT bootstrap summary: ${bootstrapSummary(bootstrap)}")
                if (workspaceKey != null) {
                    val key = workspaceKey.trim('"')
                    val ready = requestWithRetry(client, { client.sendWorkspaceBridgeOpen(requestId = "spike-bridge-1", workspaceKey = key) }, "workspace-bridge-ready")
                    log("RESULT workspace-bridge-ready: ${ready?.let { summary(it) } ?: "TIMEOUT"}")
                    val error = client.awaitControl("workspace-bridge-error", 2_000)
                    if (error != null) log("RESULT workspace-bridge-error: ${summary(error)}")
                }
                log("EVENTS ${client.events}")
            } finally {
                client.close()
            }
        }
        "subscribe" -> {
            // §43.2-1.5: bridge → v4 hello/clientHello/subscribe → first REAL frame + rowsRange.
            val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
            try {
                client.connectAndAuthenticate()
                // device re-pair race: give the desktop side a moment, then bootstrap
                val bootstrap = requestWithRetry(client, { client.sendBootstrapRequest(requestId = "spike-boot-sub") }, "bootstrap-response")
                  ?: error("no bootstrap-response")
                val result = bootstrap["result"] as? kotlinx.serialization.json.JsonObject ?: error("no result")
                val workspaces = result["workspaces"] as? kotlinx.serialization.json.JsonArray ?: error("no workspaces")
                val ws = workspaces.firstOrNull() as? kotlinx.serialization.json.JsonObject ?: error("empty workspaces")
                val wsPath = (ws["workspacePath"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: error("no path")
                val wsIdent = (ws["workspaceIdentity"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                val workspaceKey = wsIdent?.trim()?.ifEmpty { null } ?: wsPath
                val tasks = result["tasks"] as? kotlinx.serialization.json.JsonArray ?: error("no tasks")
                val firstTask = tasks.firstOrNull() as? kotlinx.serialization.json.JsonObject
                val sessionId = (firstTask?.get("id") ?: firstTask?.get("sessionId") ?: firstTask?.get("taskId"))
                    ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                log("subscribe target: workspace present=${wsPath.isNotEmpty()} sessionIdPresent=${sessionId != null} totalTasks=${tasks.size}")

                val ready = requestWithRetry(client, { client.sendWorkspaceBridgeOpen(requestId = "spike-bridge-sub", workspaceKey = workspaceKey, taskId = sessionId) }, "workspace-bridge-ready")
                log("bridge ready: ${ready != null}")
                if (sessionId == null) {
                    log("RESULT subscribe: SKIP (no task id in task list)")
                    client.close()
                    File(captureDir, "$captureId.report.log").writeText(report.toString())
                    return
                }

                client.awaitChannelReady(15_000)
                // v4 plane over the Channel layer
                var eventCount = 0L
                var firstFrameSummary: String? = null
                val listenId = client.channel.listen("zcode-agent", "onDynamicConversationFrame",
                    RpcValue.Obj(kotlinx.serialization.json.buildJsonObject {
                        put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
                        wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
                    })
                ) { data ->
                    eventCount += 1
                    if (firstFrameSummary == null) {
                        firstFrameSummary = when (data) {
                            is RpcValue.Obj -> {
                                val obj = data.json as? kotlinx.serialization.json.JsonObject
                                val topic = (obj?.get("topic") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                val frame = obj?.get("frame") as? kotlinx.serialization.json.JsonObject
                                val kind = (frame?.get("kind") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                val delivery = (frame?.get("deliveryKind") as? kotlinx.serialization.json.JsonPrimitive)?.content
                                val seq = frame?.get("eventSeq") ?: frame?.get("seq")
                                "topic-present=${topic != null} kind=$kind delivery=$delivery seq=$seq"
                            }
                            else -> data.javaClass.simpleName
                        }
                    }
                }
                Thread.sleep(200)
                val hello = client.channel.call("zcode-agent", "helloConversationV4", RpcValue.Arr(emptyList()))
                awaitPromise(hello, 10_000)
                log("RESULT helloConversationV4: done=${hello.isDone} success=${hello.isSuccess}")
                if (hello.isSuccess) log("RESULT hello summary: ${helloResultSummary(hello.result)}")
                val clientHello = kotlinx.serialization.json.buildJsonObject {
                    put("kind", kotlinx.serialization.json.JsonPrimitive("clientHello"))
                    put("protocolVersion", kotlinx.serialization.json.JsonPrimitive(3))
                    put("clientId", kotlinx.serialization.json.JsonPrimitive("zcode-android-spike-" + java.util.UUID.randomUUID().toString().take(8)))
                    put("clientKind", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
                    put("appVersion", kotlinx.serialization.json.JsonPrimitive("0.1.0-phase1"))
                    put("capabilities", kotlinx.serialization.json.buildJsonObject { })
                }
                val init = client.channel.call("zcode-agent", "initializeConversationV4",
                    RpcValue.Arr(listOf(RpcValue.Obj(clientHello))))
                awaitPromise(init, 10_000)
                log("RESULT initializeConversationV4: done=${init.isDone} success=${init.isSuccess}")
                if (!init.isSuccess) log("RESULT initialize error: ${init.error?.detail?.take(300)}")
                val subscribeParams = kotlinx.serialization.json.buildJsonObject {
                    put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
                    wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                    put("visibility", kotlinx.serialization.json.JsonPrimitive("foreground"))
                }
                val sub = client.channel.call("zcode-agent", "subscribeConversationV4",
                    RpcValue.Arr(listOf(RpcValue.Obj(subscribeParams))))
                awaitPromise(sub, 15_000)
                log("RESULT subscribeConversationV4: done=${sub.isDone} success=${sub.isSuccess}")
                if (!sub.isSuccess) log("RESULT subscribe error summary: ${sub.error?.detail?.take(160)}")
                // wait for the first real frame
                val deadline = System.currentTimeMillis() + 20_000
                while (System.currentTimeMillis() < deadline && firstFrameSummary == null) Thread.sleep(100)
                log("RESULT first real frame: ${firstFrameSummary ?: "NONE within 20s"} events=$eventCount")
                // rowsRange ≤ 200
                val rowsParams = kotlinx.serialization.json.buildJsonObject {
                    put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
                    wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
                    put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId))
                    put("limit", kotlinx.serialization.json.JsonPrimitive(200))
                }
                val rows = client.channel.call("zcode-agent", "conversationRowsRangeV4",
                    RpcValue.Arr(listOf(RpcValue.Obj(rowsParams))))
                awaitPromise(rows, 15_000)
                log("RESULT rowsRange(200): done=${rows.isDone} success=${rows.isSuccess}")
                if (rows.isSuccess) log("RESULT rowsRange summary: ${rowsResultSummary(rows.result)}")
                if (!rows.isSuccess) log("RESULT rowsRange error summary: ${rows.error?.detail?.take(160)}")
                client.channel.disposeListener(listenId)
                log("EVENTS-FULL ${client.events.joinToString(" | ")}")
            } finally {
                client.close()
            }
        }
        "recover" -> {
            // §43.2-1.6: scripted disconnect/reconnect cycles; recoveryId resubscribe.
            var ok = 0
            val recoveryId = RelayBridge.recoveryId()
            // Live runs need a REAL workspace key: fetch it once from a bootstrap probe.
            var liveWorkspaceKey = opts["workspace-key"]
            if (liveWorkspaceKey == null) {
                val probe = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
                try {
                    probe.connectAndAuthenticate()
                    val boot = requestWithRetry(probe, { probe.sendBootstrapRequest(requestId = "spike-boot-recover") }, "bootstrap-response")
                    val r = boot?.get("result") as? kotlinx.serialization.json.JsonObject
                    val wsArr = r?.get("workspaces") as? kotlinx.serialization.json.JsonArray
                    val ws0 = wsArr?.firstOrNull() as? kotlinx.serialization.json.JsonObject
                    val p0 = (ws0?.get("workspacePath") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    val ident0 = (ws0?.get("workspaceIdentity") as? kotlinx.serialization.json.JsonPrimitive)?.content
                    liveWorkspaceKey = ident0?.trim()?.ifEmpty { null } ?: p0
                } finally {
                    probe.close()
                }
                log("recover workspace key resolved: ${liveWorkspaceKey != null}")
            }
            repeat(loops) { attempt ->
                val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
                try {
                    client.connectAndAuthenticate()
                    client.overrideRecovery(recoveryId)
                    val baseline = client.rpcMessageCount()
                    val ready = requestWithRetry(client, { client.sendWorkspaceBridgeOpen(
                        requestId = "spike-bridge-r$attempt",
                        workspaceKey = liveWorkspaceKey ?: return@requestWithRetry,
                    ) }, "workspace-bridge-ready")
                    val frameBefore = client.awaitRpcMessageSince(baseline, 6_000)
                    client.forceKill()
                    // reconnect: new socket, same credential, same bridge identity (recoveryId)
                    val client2 = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, ::log)
                    try {
                        client2.connectAndAuthenticate()
                        client2.overrideRecovery(recoveryId)
                        val baseline2 = client2.rpcMessageCount()
                        val ready2 = requestWithRetry(client2, { client2.sendWorkspaceBridgeOpen(
                            requestId = "spike-bridge-r${attempt}b",
                            workspaceKey = liveWorkspaceKey ?: return@requestWithRetry,
                        ) }, "workspace-bridge-ready")
                        val frameAfter = client2.awaitRpcMessageSince(baseline2, 6_000)
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
