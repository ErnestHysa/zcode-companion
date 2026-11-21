package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * pass-0 Phase 10 — the desktop-parity read-mirror models (G83/G84/G101/G98).
 * Each shape mirrors the REAL desktop 3.18.1 (asar `ec475e6e…`) payload fields
 * byte-faithfully; the decodes are TOLERANT (INV-8): unknown fields ignored,
 * absent optional fields stay null, enum-ish strings stay RAW so unknown
 * future values still display truthfully. READ-ONLY planes: authoring
 * (output-style set/delete, CUA restartHelper, source-server connect/rename/
 * remove) exists NOWHERE on the phone (INV-9 — the mirrors carry no write).
 */

// ================================================================== G83: client scenes

/**
 * `client-scenes` channel `list` — the desktop's ONE verb
 * (`createClientScenesService`: GET /api/v1/client/scenes, `{code, msg, data}`).
 * The phone consumes the `draft-suggestion` scene's prompts: each prompt item's
 * `defaults` map names option groups whose items carry `contents.{en,cn}` — the
 * same resolution the desktop's `Iin` adapter runs. A prompt whose referenced
 * option cannot be resolved is SKIPPED, never guessed.
 */
data class ClientScenesView(
    val sceneCount: Int,
    val suggestedPrompts: List<SuggestedPrompt>,
    /** `code !== 0` — the cloud's business-error envelope (honest degraded face). */
    val businessError: Boolean,
) {
    data class SuggestedPrompt(
        val id: String,
        val text: String,
        val imageUrl: String?,
    )

    companion object {
        const val DRAFT_SUGGESTION_SCENE = "draft-suggestion"

        fun decode(obj: JsonObject): ClientScenesView {
            val code = obj.intOrNull("code") ?: 0
            val businessError = code != 0
            val data = obj["data"] as? JsonArray
                ?: return ClientScenesView(0, emptyList(), businessError)
            var sceneCount = 0
            val prompts = ArrayList<SuggestedPrompt>()
            for (el in data) {
                val scene = el as? JsonObject ?: continue
                if (scene.strOrNullN("scene") == null) continue
                sceneCount++
                if (scene.strOrNullN("scene") != DRAFT_SUGGESTION_SCENE) continue
                val options = scene.objOrNull("options") ?: continue
                val groups = HashMap<String, List<JsonObject>>()
                for ((gname, gval) in options) {
                    if (gname == "prompts") continue
                    val gitems = (gval as? JsonObject)?.get("items") as? JsonArray ?: continue
                    groups[gname] = gitems.mapNotNull { it as? JsonObject }
                }
                val promptItems = (options.objOrNull("prompts")?.get("items") as? JsonArray)
                    ?: continue
                for (item in promptItems) {
                    val o = item as? JsonObject ?: continue
                    val id = o.strOrNullN("id") ?: continue
                    val text = resolvePromptText(o, groups) ?: continue
                    prompts.add(SuggestedPrompt(id, text, o.strOrNullN("img")))
                }
            }
            return ClientScenesView(sceneCount, prompts, businessError)
        }

        /** en preferred over cn, the item's own contents first, then its defaults. */
        private fun resolvePromptText(item: JsonObject, groups: Map<String, List<JsonObject>>): String? {
            item.objOrNull("contents")?.let { own ->
                own.strOrNullN("en")?.takeIf { it.isNotBlank() }?.let { return it }
                own.strOrNullN("cn")?.takeIf { it.isNotBlank() }?.let { return it }
            }
            val defaults = item.objOrNull("defaults") ?: return null
            for ((_, refs) in defaults) {
                val ids = (refs as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }
                    ?: continue
                for (ref in ids) {
                    for (group in groups.values) {
                        val hit = group.firstOrNull { it.strOrNullN("id") == ref } ?: continue
                        val contents = hit.objOrNull("contents") ?: continue
                        contents.strOrNullN("en")?.takeIf { it.isNotBlank() }?.let { return it }
                        contents.strOrNullN("cn")?.takeIf { it.isNotBlank() }?.let { return it }
                    }
                }
            }
            return null
        }
    }
}

// ================================================================== G84: output styles

/**
 * `output-style` channel `listStyles` (`{styles:[{id,name,description,enabled,
 * isBuiltIn,filePath?}]}` — built-ins + the ~/.claude/output-styles markdown customs,
 * `enabled: id===activeId`) + `getActiveStyle` (`{styleId: string|null}`) —
 * the read HALF of the desktop's 7-verb outputStyleService. setActiveStyle/
 * addStyle/updateStyle/deleteStyle are authoring and decoded nowhere.
 */
data class OutputStylesView(
    val styles: List<OutputStyle>,
    val activeStyleId: String?,
) {
    data class OutputStyle(
        val id: String,
        val name: String,
        val description: String?,
        val enabled: Boolean,
        val isBuiltIn: Boolean,
        val filePath: String?,
    )

    companion object {
        fun decode(obj: JsonObject, activeStyleId: String?): OutputStylesView = OutputStylesView(
            styles = (obj["styles"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.strOrNullN("id") ?: return@mapNotNull null
                OutputStyle(
                    id = id,
                    name = o.strOrNullN("name") ?: id,
                    description = o.strOrNullN("description")?.takeIf { it.isNotBlank() },
                    enabled = o.boolOrNull("enabled") ?: (id == activeStyleId),
                    isBuiltIn = o.boolOrNull("isBuiltIn") ?: false,
                    filePath = o.strOrNullN("filePath"),
                )
            },
            activeStyleId = activeStyleId,
        )
    }
}

// ================================================================== G101: CUA permission status

/**
 * `cua-permission` channel `getStatus` — the desktop's typed CUA health
 * (`available, reason, grantOwner, grantOwnerDisplayName, accessibility,
 * accessibilityProbeOk, screenRecording, screenCaptureProbeOk, idle`). Only
 * strict-true reads are true (the desktop's own probes are
 * `accessibility_probe_ok===!0` / `classification==="functional"`); absent
 * fields stay null (never defaulted into a health claim). restartHelper is
 * macOS-only authoring — NOT exposed phone-side.
 */
data class CuaPermissionStatus(
    val available: Boolean?,
    val reason: String?,
    val grantOwner: String?,
    val grantOwnerDisplayName: String?,
    val accessibility: Boolean?,
    val accessibilityProbeOk: Boolean?,
    val screenRecording: Boolean?,
    val screenCaptureProbeOk: Boolean?,
    val idle: Boolean?,
) {
    companion object {
        fun decode(obj: JsonObject): CuaPermissionStatus = CuaPermissionStatus(
            available = obj.boolOrNull("available"),
            reason = obj.strOrNullN("reason")?.takeIf { it.isNotBlank() },
            grantOwner = obj.strOrNullN("grantOwner"),
            grantOwnerDisplayName = obj.strOrNullN("grantOwnerDisplayName"),
            accessibility = obj.boolOrNull("accessibility"),
            accessibilityProbeOk = obj.boolOrNull("accessibilityProbeOk"),
            screenRecording = obj.boolOrNull("screenRecording"),
            screenCaptureProbeOk = obj.boolOrNull("screenCaptureProbeOk"),
            idle = obj.boolOrNull("idle"),
        )
    }
}

// ================================================================== G98: source servers

/**
 * `source-server-management` channel `getSnapshot` — the directory snapshot
 * `{generation, servers[], profiles[], workspaceHistory[], states[],
 * failedPathCount, abandonedPathCount, migrationCounts}`. The phone mirrors the
 * SERVERS (the Dh zod item) + their connection STATES (the J0 item; the state
 * vocabulary `absent|bootstrapping|needs-install|needs-upgrade|updating|online|
 * retrying|offline|identity-change-pending|failed` kept RAW). Profiles/history/
 * migration reports are authoring-adjacent desktop management — decoded
 * nowhere; connect/rename/remove stay desktop-only.
 */
data class SourceServersView(
    val generation: Long,
    val servers: List<SourceServerInfo>,
    val states: List<SourceServerState>,
    val failedPathCount: Int,
    val abandonedPathCount: Int,
) {
    data class SourceServerInfo(
        val sourceServerId: String,
        val displayName: String,
        val osUser: String?,
        val hostname: String?,
        val lastSeenVersion: String?,
        val lastOnlineAtMs: Long?,
        /** "installed" | "uninstalled" (raw; absent stays null — never defaulted). */
        val installState: String?,
        val declaredWorkspaces: List<DeclaredWorkspace>,
    )

    data class DeclaredWorkspace(
        val workspacePath: String,
        val workspaceIdentity: String,
        val label: String?,
    )

    data class SourceServerState(
        val profileId: String,
        val sourceServerId: String?,
        /** The 10-value N6 vocabulary kept raw (unknown future states display truthfully). */
        val state: String,
        val error: String?,
        val updateAvailability: String?,
        val currentVersion: String?,
        val availableVersion: String?,
    )

    companion object {
        fun decode(obj: JsonObject): SourceServersView = SourceServersView(
            generation = obj.longOrNullN("generation") ?: 0,
            servers = (obj["servers"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = o.strOrNullN("sourceServerId") ?: return@mapNotNull null
                SourceServerInfo(
                    sourceServerId = id,
                    displayName = o.strOrNullN("displayName") ?: id,
                    osUser = o.strOrNullN("osUser"),
                    hostname = o.strOrNullN("hostname"),
                    lastSeenVersion = o.strOrNullN("lastSeenVersion"),
                    lastOnlineAtMs = o.longOrNullN("lastOnlineAt"),
                    installState = o.strOrNullN("installState"),
                    declaredWorkspaces = (o["declaredWorkspaces"] as? JsonArray).orEmpty()
                        .mapNotNull { w ->
                            val wo = w as? JsonObject ?: return@mapNotNull null
                            val path = wo.strOrNullN("workspacePath") ?: return@mapNotNull null
                            DeclaredWorkspace(
                                workspacePath = path,
                                workspaceIdentity = wo.strOrNullN("workspaceIdentity") ?: path,
                                label = wo.strOrNullN("label"),
                            )
                        },
                )
            },
            states = (obj["states"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val profileId = o.strOrNullN("profileId") ?: return@mapNotNull null
                SourceServerState(
                    profileId = profileId,
                    sourceServerId = o.strOrNullN("sourceServerId"),
                    state = o.strOrNullN("state") ?: "unknown",
                    error = o.strOrNullN("error")?.takeIf { it.isNotBlank() },
                    updateAvailability = o.strOrNullN("updateAvailability"),
                    currentVersion = o.strOrNullN("currentVersion"),
                    availableVersion = o.strOrNullN("availableVersion"),
                )
            },
            failedPathCount = obj.intOrNull("failedPathCount") ?: 0,
            abandonedPathCount = obj.intOrNull("abandonedPathCount") ?: 0,
        )
    }
}

/** Null-tolerant boolean read (absent/null → null; the mirror never invents a claim). */
internal fun JsonObject.boolOrNull(key: String): Boolean? =
    when (val v = this[key]) {
        null, is kotlinx.serialization.json.JsonNull -> null
        is JsonPrimitive -> v.content.toBooleanStrictOrNull()
        else -> null
    }
