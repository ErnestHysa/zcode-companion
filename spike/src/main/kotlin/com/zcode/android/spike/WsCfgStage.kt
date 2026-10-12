package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import java.io.File

/**
 * Live A/B for the workspace-config subscribe (GAP-12 field finding: the app's
 * shape — {workspacePath, workspaceIdentity?, topic} — is answered
 * fault.subscribe.sessionNotFound (: undefined) by the relay/host, so the slash
 * catalog never loads). Calls the SAME RPC in three shapes and logs every
 * conversationFrame that comes back:
 *   A: the app's current shape
 *   B: A + connectionId + clientMode (the desktop adapter's own fields)
 *   C: the desktop adapter's minimal shape {topic, connectionId, clientMode, visibility}
 */
fun runWsCfgStage(
    opts: Map<String, String>,
    relayUrl: String,
    params: PairingParams,
    capture: CaptureSink,
    log: (String) -> Unit,
) {
    val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, log)
    try {
        client.connectAndAuthenticate(deviceName = opts["name"] ?: "zcode-android-spike")

        // bootstrap for the workspace key (control plane, pre-channel — the
        // subscribe stage's own order)
        var boot: kotlinx.serialization.json.JsonObject? = null
        var attempt = 0
        while (attempt < 6 && boot == null) {
            client.sendBootstrapRequest(requestId = "spike-wscfg-boot")
            boot = client.awaitControl("bootstrap-response", 3_000)
            attempt += 1
        }
        val ws = ((boot?.get("result") as? kotlinx.serialization.json.JsonObject)
            ?.get("workspaces") as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
        val wsPath = (ws?.get("workspacePath") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val wsIdent = (ws?.get("workspaceIdentity") as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.ifEmpty { null }
        val workspaceKey = wsIdent ?: wsPath
        val topic = "workspace-config/$workspaceKey"

        // bridge open (same as subscribe — this is what makes the server's
        // Channel Initialize + hello flow work)
        var bridgeReady: kotlinx.serialization.json.JsonObject? = null
        var bridgeAttempt = 0
        while (bridgeAttempt < 6 && bridgeReady == null) {
            client.sendWorkspaceBridgeOpen(requestId = "spike-wscfg-bridge", workspaceKey = workspaceKey)
            bridgeReady = client.awaitControl("workspace-bridge-ready", 3_000)
            bridgeAttempt += 1
        }
        log("wscfg bridge ready=${bridgeReady != null}")
        client.awaitChannelReady(15_000)

        // hello + initialize — the hello RESULT carries the connectionId the
        // desktop adapter sends in every subscribe.
        var connectionId: String? = null
        val hello = client.channel.call("zcode-agent", "helloConversationV4", RpcValue.Arr(emptyList()))
        awaitPromise(hello, 10_000)
        run {
            val r = (hello.result as? RpcValue.Obj)?.json as? kotlinx.serialization.json.JsonObject
            log("wscfg hello success=${hello.isSuccess} error=${hello.error?.message?.take(120)} result=${r?.toString()?.take(400) ?: hello.result?.javaClass?.simpleName}")
            connectionId = (r?.get("connectionId") as? kotlinx.serialization.json.JsonPrimitive)?.content
        }
        val clientHello = kotlinx.serialization.json.buildJsonObject {
            put("kind", kotlinx.serialization.json.JsonPrimitive("clientHello"))
            put("protocolVersion", kotlinx.serialization.json.JsonPrimitive(3))
            put("clientId", kotlinx.serialization.json.JsonPrimitive("zcode-android-spike-" + java.util.UUID.randomUUID().toString().take(8)))
            put("clientKind", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
            put("appVersion", kotlinx.serialization.json.JsonPrimitive("wscfg"))
            put("capabilities", kotlinx.serialization.json.buildJsonObject { })
        }
        val init = client.channel.call("zcode-agent", "initializeConversationV4", RpcValue.Arr(listOf(RpcValue.Obj(clientHello))))
        awaitPromise(init, 10_000)
        log("wscfg init success=${init.isSuccess} connectionId=$connectionId")
        if (connectionId == null) error("wscfg: hello result carried no connectionId")
        log("wscfg topic=$topic connectionId=$connectionId")

        val frames = java.util.Collections.synchronizedList(mutableListOf<String>())
        client.channel.listen(
            "zcode-agent", "onDynamicConversationFrame",
            RpcValue.Obj(kotlinx.serialization.json.buildJsonObject {
                put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
                wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
            }),
        ) { data ->
            val s = when (data) {
                is RpcValue.Obj -> (data.json as? kotlinx.serialization.json.JsonObject)
                    ?.get("topic")?.let { it as? kotlinx.serialization.json.JsonPrimitive }?.content ?: "?"
                else -> "?"
            }
            frames.add(s)
        }

        fun call(tag: String, paramsObj: kotlinx.serialization.json.JsonObject) {
            val p = client.channel.call("zcode-agent", "subscribeConversationV4", RpcValue.Arr(listOf(RpcValue.Obj(paramsObj))))
            awaitPromise(p, 10_000)
            val err = p.error?.let { "${it.message?.take(160)}" }
            val ackMode = when (val r = p.result) {
                is RpcValue.Obj -> ((r.json as? kotlinx.serialization.json.JsonObject)?.get("ack")
                    as? kotlinx.serialization.json.JsonObject)?.get("mode")?.let {
                    (it as? kotlinx.serialization.json.JsonPrimitive)?.content
                }
                else -> null
            }
            log("WSCFG[$tag] success=${p.isSuccess} ackMode=$ackMode error=$err")
        }

        call("A-app", kotlinx.serialization.json.buildJsonObject {
            put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
            wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
        })
        call("B-app-plus-conn", kotlinx.serialization.json.buildJsonObject {
            put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
            wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
            put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
            put("connectionId", kotlinx.serialization.json.JsonPrimitive(connectionId))
            put("clientMode", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
        })
        val nestedWorkspace = kotlinx.serialization.json.buildJsonObject {
            put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
            put("workspaceKey", kotlinx.serialization.json.JsonPrimitive(workspaceKey))
            wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
        }
        call("C-desktop-min", kotlinx.serialization.json.buildJsonObject {
            put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
            put("connectionId", kotlinx.serialization.json.JsonPrimitive(connectionId))
            put("clientMode", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
            put("visibility", kotlinx.serialization.json.JsonPrimitive("foreground"))
        })
        call("D-conn-nested-ws", kotlinx.serialization.json.buildJsonObject {
            put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
            put("connectionId", kotlinx.serialization.json.JsonPrimitive(connectionId))
            put("clientMode", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
            put("workspace", nestedWorkspace)
        })
        call("E-conn-nested-ws-plus", kotlinx.serialization.json.buildJsonObject {
            put("topic", kotlinx.serialization.json.JsonPrimitive(topic))
            put("connectionId", kotlinx.serialization.json.JsonPrimitive(connectionId))
            put("clientMode", kotlinx.serialization.json.JsonPrimitive("mobileRemote"))
            put("visibility", kotlinx.serialization.json.JsonPrimitive("foreground"))
            put("workspace", nestedWorkspace)
            put("workspacePath", kotlinx.serialization.json.JsonPrimitive(wsPath))
            wsIdent?.let { put("workspaceIdentity", kotlinx.serialization.json.JsonPrimitive(it)) }
        })
        Thread.sleep(3_000)
        log("WSCFG frames observed: ${frames.joinToString(" | ")}")
        val out = File(opts["capture-dir"]!!, "${opts["capture-id"] ?: "capture"}-wscfg.log")
        out.writeText(frames.joinToString("\n"))
    } finally {
        client.close()
    }
}
