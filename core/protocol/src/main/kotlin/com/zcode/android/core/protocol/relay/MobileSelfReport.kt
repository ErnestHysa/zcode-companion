package com.zcode.android.core.protocol.relay

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.serialization.json.putJsonArray

/**
 * G68 (vc52 Phase 12): the phone's OPT-IN self-report control frames — the
 * desktop's inbound union (chunk-RB4EITEQ `oB`, asar 4a6080ed…):
 *
 *   `mobile-view-state-update {viewState:{activeWorkspaceKey?, activeTaskId?,
 *    updatedAt}, deviceInfo?:{platform, version, name, ...}}`
 *   `mobile-diagnostic {event: enum(state-transition|socket-close|socket-error|
 *    recover-start|recover-scheduled|pair-status|failure), timestamp, state?,
 *    previousState?, closeCode?, closeReason?, wasClean?, wasPaired?,
 *    failureReason?, failureMessage?, visibilityState?, online?, hiddenDurationMs?}`
 *
 * The desktop persists the view state in its bootstrap (`tB.mobileViewState`) and
 * logs diagnostics. No response frame exists — senders are fire-and-forget.
 */
object MobileSelfReport {

    /** The device-info subobject (eB) — platform/version/name required, the rest optional. */
    fun deviceInfo(
        platform: String,
        version: String,
        name: String,
        timezone: String? = null,
    ): JsonObject = buildJsonObject {
        put("platform", JsonPrimitive(platform))
        put("version", JsonPrimitive(version))
        put("name", JsonPrimitive(name))
        timezone?.let { put("timezone", JsonPrimitive(it)) }
    }

    fun viewStateUpdate(
        activeWorkspaceKey: String?,
        activeTaskId: String?,
        updatedAtMs: Long,
        deviceInfo: JsonObject? = null,
    ): JsonObject = buildJsonObject {
        put("zcode_type", JsonPrimitive("mobile-view-state-update"))
        putJsonObject("viewState") {
            activeWorkspaceKey?.let { put("activeWorkspaceKey", JsonPrimitive(it)) }
            activeTaskId?.let { put("activeTaskId", JsonPrimitive(it)) }
            put("updatedAt", JsonPrimitive(updatedAtMs))
        }
        deviceInfo?.let { put("deviceInfo", it) }
    }

    /** The desktop's closed event vocabulary — the caller passes a KNOWN value. */
    val DIAGNOSTIC_EVENTS = setOf(
        "state-transition", "socket-close", "socket-error",
        "recover-start", "recover-scheduled", "pair-status", "failure",
    )

    fun diagnostic(
        event: String,
        timestampMs: Long,
        state: String? = null,
        previousState: String? = null,
        closeCode: Int? = null,
        closeReason: String? = null,
        wasClean: Boolean? = null,
        wasPaired: Boolean? = null,
        failureReason: String? = null,
        failureMessage: String? = null,
        online: Boolean? = null,
        hiddenDurationMs: Long? = null,
    ): JsonObject = buildJsonObject {
        require(event in DIAGNOSTIC_EVENTS) { "unknown diagnostic event: $event" }
        put("zcode_type", JsonPrimitive("mobile-diagnostic"))
        put("event", JsonPrimitive(event))
        put("timestamp", JsonPrimitive(timestampMs))
        state?.let { put("state", JsonPrimitive(it)) }
        previousState?.let { put("previousState", JsonPrimitive(it)) }
        closeCode?.let { put("closeCode", JsonPrimitive(it)) }
        closeReason?.let { put("closeReason", JsonPrimitive(it)) }
        wasClean?.let { put("wasClean", JsonPrimitive(it)) }
        wasPaired?.let { put("wasPaired", JsonPrimitive(it)) }
        failureReason?.let { put("failureReason", JsonPrimitive(it)) }
        failureMessage?.let { put("failureMessage", JsonPrimitive(it)) }
        online?.let { put("online", JsonPrimitive(it)) }
        hiddenDurationMs?.let { put("hiddenDurationMs", JsonPrimitive(it)) }
    }
}
