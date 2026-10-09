package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * GAP-3 (the project roadmap; docs/protocol/v4-wire.md §13.1; ADR-020) —
 * the coding-plan entitlement decode. The wire payload is the desktop's
 * TypeScript interface shape (usage-stats.ts:73-89 `UsageEntitlementSnapshot`,
 * usage-quota.ts `UsageQuotaSnapshot`/`UsageQuotaLimit`/`UsageMcpQuotaSnapshot`
 * — interface types, no zod; the golden fixtures are type-check validated by
 * tools/capture/generate-phase4-fixtures.mjs). Decode discipline = the
 * ViewerModels precedent: required faces fail closed
 * ([RowDecodeException]); optional faces decode to null; unknown fields ride
 * the raw object (INV-8 — payloads are additive on newer hosts). Wire enums
 * (`type`, `unavailableReason`) stay RAW strings: unknown future values
 * display verbatim, never guessed (§9 policy).
 */

/** One quota bucket of `UsageQuotaSnapshot.limits[]` (usage-quota.ts:27-45). */
data class UsageQuotaLimit(
    /** TOKENS_LIMIT|CREDIT_LIMIT|TIME_LIMIT|MCP_USAGE_LIMIT|… — kept raw. */
    val type: String,
    val unit: Double?,
    val number: Double?,
    val usage: Double?,
    val remaining: Double?,
    /** The USED share (desktop presentation inverts it for display). */
    val percentage: Double?,
    val nextResetTime: Long?,
    val usageDetails: List<UsageDetail>,
) {
    data class UsageDetail(
        val modelCode: String,
        val displayName: String?,
        val usage: Double,
    )

    companion object {
        fun decode(obj: JsonObject): UsageQuotaLimit = UsageQuotaLimit(
            type = obj.strReq("type"),
            unit = obj.doubleOrNull("unit"),
            number = obj.doubleOrNull("number"),
            usage = obj.doubleOrNull("usage"),
            remaining = obj.doubleOrNull("remaining"),
            percentage = obj.doubleOrNull("percentage"),
            nextResetTime = obj.longOrNullN("nextResetTime"),
            usageDetails = obj.arrayReq("usageDetails").map { el ->
                val d = el.asObject("usage detail")
                UsageDetail(
                    modelCode = d.strReq("modelCode"),
                    displayName = d.strOrNullN("displayName"),
                    usage = d.doubleReq("usage"),
                )
            },
        )
    }
}

/**
 * The `usage-stats` channel `getEntitlementSnapshot` result
 * (UsageEntitlementSnapshot — the desktop's own quota-panel data source,
 * fetched per Usage-screen load + explicit refresh; ADR-020). `remaining*`
 * fields flatten the `UsageEntitlementRemaining | null` block.
 */
data class UsageEntitlement(
    val generatedAt: Long,
    val serverTime: Long?,
    val authenticated: Boolean,
    /** not_authenticated|not_configured|no_plan|unavailable — kept raw. */
    val unavailableReason: String?,
    val providerId: String?,
    val providerName: String?,
    val remainingCount: Double?,
    val remainingIsShow: Boolean?,
    val remainingPercentage: Double?,
    val remainingNextResetTime: Long?,
    val planLevel: String?,
    val limits: List<UsageQuotaLimit>,
    /** The official Server-MCP aggregate — mcpQuota.aggregate, a SEPARATE
     *  entitlement field (never inside limits[]; v4-wire.md §13.1). */
    val mcpAggregate: UsageQuotaLimit?,
) {
    companion object {
        fun decode(obj: JsonObject): UsageEntitlement {
            val quota = obj.objOrNull("quota")
            val remaining = obj.objOrNull("remaining")
            val provider = obj.objOrNull("provider")
            val mcpQuota = obj.objOrNull("mcpQuota")
            return UsageEntitlement(
                generatedAt = obj.longReq("generatedAt"),
                serverTime = obj.longOrNullN("serverTime"),
                authenticated = obj.boolReq("authenticated"),
                unavailableReason = obj.strOrNullN("unavailableReason"),
                providerId = provider?.strOrNullN("id"),
                providerName = provider?.strOrNullN("name"),
                remainingCount = remaining?.doubleOrNull("count"),
                remainingIsShow = remaining?.let { r ->
                    (r["isShow"] as? JsonPrimitive)?.content?.toBooleanStrictOrNull()
                },
                remainingPercentage = remaining?.doubleOrNull("percentage"),
                remainingNextResetTime = remaining?.longOrNullN("nextResetTime"),
                planLevel = quota?.strOrNullN("level"),
                limits = quota?.get("limits")?.let { arr ->
                    (arr as? kotlinx.serialization.json.JsonArray)?.map { el ->
                        UsageQuotaLimit.decode(el.asObject("quota limit"))
                    } ?: throw RowDecodeException("quota.limits wrong type")
                } ?: emptyList(),
                mcpAggregate = mcpQuota?.objOrNull("aggregate")?.let { UsageQuotaLimit.decode(it) },
            )
        }
    }
}

// ================================================================== GAP-4: reset words

/**
 * The `usage-stats` channel `getCodingPlanResetStatus` result
 * (coding-plan-reset.ts `CodingPlanResetStatusSnapshot` — TS interface;
 * v4-wire.md §13.2). `hasUnreadHistory` is a cursor SHARED by both reset types
 * and owned by the newest history (the state machine's rule).
 */
data class CodingPlanResetStatus(
    val fiveHourExpires: List<Long>,
    val weekExpires: List<Long>,
    val latestFiveHourUsedAt: Long?,
    val latestWeekUsedAt: Long?,
    val hasUnreadHistory: Boolean,
) {
    companion object {
        fun decode(obj: JsonObject): CodingPlanResetStatus = CodingPlanResetStatus(
            fiveHourExpires = obj.arrayReq("availableFiveHourResets").map { el ->
                el.asObject("reset opportunity").longReq("expireAt")
            },
            weekExpires = obj.arrayReq("availableWeekResets").map { el ->
                el.asObject("reset opportunity").longReq("expireAt")
            },
            latestFiveHourUsedAt = obj.objOrNull("latestFiveHourResetHistory")?.longOrNullN("usedAt"),
            latestWeekUsedAt = obj.objOrNull("latestWeekResetHistory")?.longOrNullN("usedAt"),
            hasUnreadHistory = obj.boolReq("hasUnreadHistory"),
        )
    }
}

/** `requestCodingPlanResetOpportunity` result: `{granted, nextTryAt?}`. */
data class ResetOpportunityResult(
    val granted: Boolean,
    val nextTryAt: Long?,
) {
    companion object {
        fun decode(obj: JsonObject): ResetOpportunityResult = ResetOpportunityResult(
            granted = obj.boolReq("granted"),
            nextTryAt = obj.longOrNullN("nextTryAt"),
        )
    }
}

/** `useCodingPlanReset` result: `{used: true}`. */
data class ResetUseResult(val used: Boolean) {
    companion object {
        fun decode(obj: JsonObject): ResetUseResult = ResetUseResult(used = obj.boolReq("used"))
    }
}

/** Desktop `CodingPlanResetType` — the closed wire pair, raw strings on the wire. */
object CodingPlanResetType {
    const val FIVE_HOUR = "FIVE_HOUR"
    const val WEEK = "WEEK"
}

/**
 * GAP-4 request side (ADR-020): derive the credential-free plan-scope descriptor
 * `ZCodeProviderAccountAccess` from the desktop's builtin provider-id grammar
 * (`account:<family>-<planKind>`, model-provider-types.ts:7-16) — the family
 * and mode tokens mirror the id; nothing is ever guessed for unknown ids (null
 * ⇒ the reset surface does not exist for that provider). The host resolves the
 * actual API key from its own store; this descriptor carries no secret.
 */
fun accountAccessFromProviderId(providerId: String, entitled: Boolean): JsonObject? {
    val m = Regex("^account:(zai|bigmodel)-(start-plan|individual-coding-plan|team-coding-plan)$").find(providerId)
        ?: return null
    val (family, mode) = m.destructured
    return kotlinx.serialization.json.buildJsonObject {
        put("type", JsonPrimitive("zhipu-account"))
        put("accountType", JsonPrimitive(family))
        put("mode", JsonPrimitive(mode))
        put("entitled", JsonPrimitive(entitled))
    }
}
