package com.zcode.android.core.protocol.v4

import com.zcode.android.core.protocol.bytes.ProtocolException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * workspace-config topic decode — port of packages/shared/src/zcode-protocol-v4/
 * workspace-config.ts (v3.14.3, commit 29628c9; docs/protocol/v4-wire.md §11.2,
 * GAP-12). Workspace-level "living config catalog": conflated latest-wins, WHOLE
 * replacement on every frame (never a deep merge — the sessions-index discipline).
 *
 * Tolerance policy (INV-8/INV-14, exact): unknown FIELDS ride `raw`; a
 * slashCommand.source outside the verified builtin|custom enum is retained RAW
 * (never guessed); a missing REQUIRED field is a typed [ProtocolException] — this
 * is an auxiliary surface, the frame is dropped whole and the catalog stays as-is.
 */

data class WorkspaceConfigSelectValue(
    val value: String,
    val name: String,
    val description: String?,
    val origin: String?,
    val modelProviderId: String?,
    val modelProviderName: String?,
    val modelThoughtLevels: List<String>,
    val modelDefaultThoughtLevel: String?,
    val raw: JsonObject,
)

data class WorkspaceConfigOption(
    val id: String,
    val name: String,
    val description: String?,
    val category: String?,
    val type: String,
    val currentValue: String?,
    val options: List<WorkspaceConfigSelectValue>,
    val raw: JsonObject,
)

/** workspaceSlashCommandSchema — `{name, description, inputHint?, source?}`. */
data class WorkspaceSlashCommand(
    val name: String,
    val description: String,
    /** Args hint shown in the "/" menu (e.g. "[instructions]"); null = argument-less. */
    val inputHint: String?,
    /** "builtin"|"custom"; null = legacy payload (capability unknown — never defaulted). */
    val source: String?,
    val raw: JsonObject,
)

data class WorkspaceConfigState(
    val configOptions: List<WorkspaceConfigOption>,
    val slashCommands: List<WorkspaceSlashCommand>,
    val raw: JsonObject,
)

data class WorkspaceConfigSnapshot(
    val protocolVersion: Long,
    val workspaceId: String,
    /** Workspace-level config-log generation (independent of the session logEpoch). */
    val logEpoch: String,
    val config: WorkspaceConfigState,
    val raw: JsonObject,
) {
    companion object {
        fun decode(obj: JsonObject): WorkspaceConfigSnapshot = WorkspaceConfigSnapshot(
            protocolVersion = obj.longOrNullP("protocolVersion")
                ?: throw ProtocolException("workspaceConfig.protocolVersion missing"),
            workspaceId = obj.strOrNullP("workspaceId")
                ?: throw ProtocolException("workspaceConfig.workspaceId missing"),
            logEpoch = obj.strOrNullP("logEpoch")
                ?: throw ProtocolException("workspaceConfig.logEpoch missing"),
            config = decodeConfig(
                obj["config"] as? JsonObject
                    ?: throw ProtocolException("workspaceConfig.config missing"),
            ),
            raw = obj,
        )
    }
}

/** workspaceConfigDeltaSchema — exactly ONE op: `config.updated {config}` (whole replacement). */
data class WorkspaceConfigDelta(
    val op: String,
    val config: WorkspaceConfigState,
    val raw: JsonObject,
) {
    companion object {
        const val OP_CONFIG_UPDATED = "config.updated"

        fun decode(obj: JsonObject): WorkspaceConfigDelta {
            val op = obj.strOrNullP("op")
            if (op != OP_CONFIG_UPDATED) {
                throw ProtocolException("workspaceConfig delta op invalid: $op")
            }
            return WorkspaceConfigDelta(
                op = op,
                config = decodeConfig(
                    obj["config"] as? JsonObject
                        ?: throw ProtocolException("config.updated payload missing"),
                ),
                raw = obj,
            )
        }
    }
}

/** `workspace-config/<workspaceId>` suffix parse (parseWorkspaceConfigTopic dual). */
fun parseWorkspaceConfigTopic(topic: String): String? {
    if (!topic.startsWith(V4Topics.WORKSPACE_CONFIG_PREFIX)) return null
    val id = topic.removePrefix(V4Topics.WORKSPACE_CONFIG_PREFIX)
    return id.ifEmpty { null }
}

private fun decodeConfig(obj: JsonObject): WorkspaceConfigState = WorkspaceConfigState(
    configOptions = (obj["configOptions"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.map { o ->
            WorkspaceConfigOption(
                id = o.strOrNullP("id") ?: throw ProtocolException("configOption.id missing"),
                name = o.strOrNullP("name") ?: throw ProtocolException("configOption.name missing"),
                description = o.strOrNullP("description"),
                category = o.strOrNullP("category"),
                type = o.strOrNullP("type") ?: throw ProtocolException("configOption.type missing"),
                currentValue = (o["currentValue"] as? JsonPrimitive)?.content,
                options = (o["options"] as? JsonArray)
                    ?.mapNotNull { it as? JsonObject }
                    ?.map { v ->
                        WorkspaceConfigSelectValue(
                            value = v.strOrNullP("value") ?: throw ProtocolException("selectValue.value missing"),
                            name = v.strOrNullP("name") ?: throw ProtocolException("selectValue.name missing"),
                            description = v.strOrNullP("description"),
                            origin = v.strOrNullP("origin"),
                            modelProviderId = v.strOrNullP("modelProviderId"),
                            modelProviderName = v.strOrNullP("modelProviderName"),
                            modelThoughtLevels = (v["modelThoughtLevels"] as? JsonArray)
                                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                                ?: emptyList(),
                            modelDefaultThoughtLevel = v.strOrNullP("modelDefaultThoughtLevel"),
                            raw = v,
                        )
                    }
                    ?: emptyList(),
                raw = o,
            )
        }
        ?: emptyList(),
    slashCommands = (obj["slashCommands"] as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.map { c ->
            WorkspaceSlashCommand(
                name = c.strOrNullP("name") ?: throw ProtocolException("slashCommand.name missing"),
                description = c.strOrNullP("description")
                    ?: throw ProtocolException("slashCommand.description missing"),
                inputHint = c.strOrNullP("inputHint"),
                source = c.strOrNullP("source"),
                raw = c,
            )
        }
        ?: emptyList(),
    raw = obj,
)

// package-local helpers (the JsonObject helpers in V4Conversation.kt are internal
// to the same package — reuse them directly).
private fun JsonObject.strOrNullP(key: String): String? = (this[key] as? JsonPrimitive)?.content

private fun JsonObject.longOrNullP(key: String): Long? = (this[key] as? JsonPrimitive)?.content?.toLongOrNull()
