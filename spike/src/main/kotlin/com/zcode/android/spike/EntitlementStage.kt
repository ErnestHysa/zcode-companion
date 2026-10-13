package com.zcode.android.spike

import com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue
import java.io.File

/**
 * Live-capture stage for the coding-plan quota plane (ADR-020): calls
 * `usage-stats.getEntitlementSnapshot` twice — once with the app's current
 * EMPTY request and once with `preferredProviderId` + the static
 * `zhipu-account` accountAccess descriptor the desktop's own UI passes —
 * and dumps BOTH raw responses so the client decode can be fixed against
 * reality. Payload is credential-free quota metadata (ADR-020 §26 analysis);
 * keep it out of the repository.
 */
fun runEntitlementStage(
    opts: Map<String, String>,
    relayUrl: String,
    params: PairingParams,
    capture: CaptureSink,
    log: (String) -> Unit,
) {
    val providerId = opts["provider-id"] ?: error("entitlement: --provider-id required")
    val m = Regex("^account:(zai|bigmodel)-(start-plan|individual-coding-plan|team-coding-plan)$").find(providerId)
        ?: error("entitlement: provider id not a builtin coding-plan id: $providerId")
    val (family, mode) = m.destructured
    val outDir = File(opts["capture-dir"]!!, "${opts["capture-id"] ?: "capture"}-entitlement.json")

    val client = RelayAuthClient(relayUrl, params, CredentialSigner(params.passHash), capture, log)
    try {
        client.connectAndAuthenticate(deviceName = opts["name"] ?: "zcode-android-spike")
        client.awaitChannelReady(15_000)

        // The phone bridges a workspace BEFORE its channel calls — mirror that:
        // bootstrap → first workspace → bridge-open (the usage-stats ProxyChannel
        // is served by the desktop only for bridged device connections).
        val boot = run {
            var attempt = 0
            var answer: kotlinx.serialization.json.JsonObject? = null
            while (attempt < 6 && answer == null) {
                client.sendBootstrapRequest(requestId = "spike-ent-boot")
                answer = client.awaitControl("bootstrap-response", 3_000)
                attempt += 1
            }
            answer ?: error("entitlement: no bootstrap-response")
        }
        val ws = ((boot["result"] as? kotlinx.serialization.json.JsonObject)
            ?.get("workspaces") as? kotlinx.serialization.json.JsonArray)
            ?.firstOrNull() as? kotlinx.serialization.json.JsonObject
        val wsPath = (ws?.get("workspacePath") as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
        val wsIdent = (ws?.get("workspaceIdentity") as? kotlinx.serialization.json.JsonPrimitive)?.content?.trim()?.ifEmpty { null }
        log("entitlement bridge workspace: $wsPath")
        var bridgeAttempt = 0
        var bridgeReady: kotlinx.serialization.json.JsonObject? = null
        while (bridgeAttempt < 6 && bridgeReady == null) {
            client.sendWorkspaceBridgeOpen(requestId = "spike-ent-bridge", workspaceKey = wsIdent ?: wsPath)
            bridgeReady = client.awaitControl("workspace-bridge-ready", 3_000)
            bridgeAttempt += 1
        }
        log("entitlement bridge ready: ${bridgeReady != null}")

        fun call(args: RpcValue, tag: String) {
            val p = client.channel.call("usage-stats", "getEntitlementSnapshot", args)
            awaitPromise(p, 15_000)
            val body = when (val r = p.result) {
                is RpcValue.Obj -> r.json.toString()
                else -> p.result?.javaClass?.simpleName ?: "null"
            }
            val err = p.error?.let { "${it.message?.take(200)}" }
            log("ENTITLEMENT[$tag] success=${p.isSuccess} error=$err body=$body")
            File(outDir.parentFile, outDir.nameWithoutExtension + ".$tag.json").writeText(body)
        }

        call(RpcValue.Arr(listOf(RpcValue.Obj(kotlinx.serialization.json.buildJsonObject { }))), "empty")
        val derived = kotlinx.serialization.json.buildJsonObject {
            put("preferredProviderId", kotlinx.serialization.json.JsonPrimitive(providerId))
            put("accountAccess", kotlinx.serialization.json.buildJsonObject {
                put("type", kotlinx.serialization.json.JsonPrimitive("zhipu-account"))
                put("accountType", kotlinx.serialization.json.JsonPrimitive(family))
                put("mode", kotlinx.serialization.json.JsonPrimitive(mode))
                put("entitled", kotlinx.serialization.json.JsonPrimitive(true))
            })
        }
        call(RpcValue.Arr(listOf(RpcValue.Obj(derived))), "derived")
        log("ENTITLEMENT-STAGE-DONE")
    } finally {
        client.close()
    }
}
