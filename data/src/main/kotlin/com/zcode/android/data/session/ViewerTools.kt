package com.zcode.android.data.session

import com.zcode.android.core.transport.AppTransport
import com.zcode.android.domain.model.AppUsageSnapshot
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.McpStatuses
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.domain.model.CodingPlanResetStatus
import com.zcode.android.domain.model.ResetOpportunityResult
import com.zcode.android.domain.model.ResetUseResult
import com.zcode.android.domain.model.UsageEntitlement
import com.zcode.android.domain.model.PluginsView
import com.zcode.android.domain.model.SkillCatalog
import kotlinx.serialization.json.JsonObject

/**
 * Phase 3 read-only viewer tool plane (android-native-gaps.md GAP-5…GAP-9;
 * docs/protocol/v4-wire.md §12) — the ONE place the viewer transport surfaces
 * map onto domain models (§40.2). READ-ONLY: every method here is an R-class
 * read; the write half of these channels is Deferred scope and exists nowhere
 * in this class or the transport's typed surface (INV-9). Transport failures
 * propagate as [com.zcode.android.core.transport.TransportFailure]; the port
 * layer translates them into the §13.3 hidden/disabled fallbacks.
 */
class ViewerTools(private val transport: AppTransport) {

    // ---------------------------------------------------------------- GAP-5: usage

    /**
     * `getAppUsageStats` → CLI `v4/usage/stats` (v4-wire.md §12.1). [range] is
     * "all"|"7d"|"30d" (verified enum); [timeZone] is the CLIENT's IANA zone
     * (the desktop passes Intl.DateTimeFormat().resolvedOptions().timeZone,
     * useUsageStats.ts:210-213) — null lets the host fall back to its own zone.
     */
    suspend fun appUsageStats(range: String, timeZone: String? = null): AppUsageSnapshot =
        AppUsageSnapshot.decode(transport.appUsageStats(range, timeZone))

    // ---------------------------------------------------------------- GAP-6: automations + off-peak

    /**
     * `listAutomations` (zcode-agent, workspace-target form; v4-wire.md §12.2).
     * Read-only: NO create/update/delete/enable surface exists anywhere (Deferred scope).
     */
    suspend fun automations(workspacePath: String?, workspaceIdentity: String?): List<AutomationRow> =
        AutomationRow.decodeList(transport.listAutomations(workspacePath, workspaceIdentity))

    /** FIELD (2026-10-12): automation CRUD — the phone-reachable zcodeAgent surface
     *  (createAutomation/updateAutomation/deleteAutomation/setAutomationEnabled/
     *  runAutomationNow — zcodeAgent.ts:679-688). Workspace target comes from the
     *  caller (the automations section already carries it). */
    suspend fun createAutomation(workspacePath: String?, workspaceIdentity: String?, request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
        transport.createAutomation(workspacePath, workspaceIdentity, request)

    suspend fun updateAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String, request: kotlinx.serialization.json.JsonObject): kotlinx.serialization.json.JsonObject =
        transport.updateAutomation(workspacePath, workspaceIdentity, automationId, request)

    suspend fun deleteAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String) =
        transport.deleteAutomation(workspacePath, workspaceIdentity, automationId)

    suspend fun setAutomationEnabled(workspacePath: String?, workspaceIdentity: String?, automationId: String, enabled: Boolean) =
        transport.setAutomationEnabled(workspacePath, workspaceIdentity, automationId, enabled)

    suspend fun runAutomationNow(workspacePath: String?, workspaceIdentity: String?, automationId: String): kotlinx.serialization.json.JsonObject =
        transport.runAutomationNow(workspacePath, workspaceIdentity, automationId)

    /** Phase 4B (A3.2): run history — `listAutomationRuns` (array normalized to {runs}) + delete + restart. */
    suspend fun automationRuns(workspacePath: String?, workspaceIdentity: String?, automationId: String): List<com.zcode.android.domain.model.AutomationRunRow> =
        com.zcode.android.domain.model.AutomationRunRow.decodeList(transport.listAutomationRuns(workspacePath, workspaceIdentity, automationId))

    suspend fun deleteAutomationRun(workspacePath: String?, workspaceIdentity: String?, runId: String) =
        transport.deleteAutomationRun(workspacePath, workspaceIdentity, runId)

    suspend fun restartAutomation(workspacePath: String?, workspaceIdentity: String?, automationId: String) =
        transport.restartAutomation(workspacePath, workspaceIdentity, automationId)

    /** Phase 4C (C2): the slash panel's extra sections — custom/plugin commands + subagents. */
    suspend fun slashUserCommands(workspacePath: String?, workspaceIdentity: String?): List<com.zcode.android.domain.model.SlashCatalogExtras.CommandEntry> =
        com.zcode.android.domain.model.SlashCatalogExtras.decodeCommands(transport.commandsList(workspacePath, workspaceIdentity))

    suspend fun subagents(workspacePath: String?, workspaceIdentity: String?, provider: String?): List<com.zcode.android.domain.model.SlashCatalogExtras.AgentEntry> =
        com.zcode.android.domain.model.SlashCatalogExtras.decodeAgents(transport.subagentsList(workspacePath, workspaceIdentity, provider))

    /** Phase 4C (A3.4): the reasoning-effort switch (zcodeAgent.ts:705; echo-reconciled). */
    suspend fun setThoughtLevel(workspacePath: String?, workspaceIdentity: String?, sessionId: String, thoughtLevel: String, expectedRevision: Long?): kotlinx.serialization.json.JsonObject =
        transport.setThoughtLevel(workspacePath, workspaceIdentity, sessionId, thoughtLevel, expectedRevision)

    /** Phase 4C (C7): workspace file search for the composer @-mentions (paths, tolerant). */
    suspend fun searchWorkspaceFiles(workspacePath: String?, workspaceIdentity: String?, query: String, limit: Int = 20): List<String> {
        val raw = transport.fileSearch(workspacePath.orEmpty(), workspaceIdentity, query, limit)
        val arr = (raw as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Arr)?.items
            ?: return emptyList()
        return arr.mapNotNull { el ->
            when (el) {
                is com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj -> ((el.json as? kotlinx.serialization.json.JsonObject)["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                is com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Str -> el.value
                else -> null
            }
        }
    }

    /** Phase 4C (C7): the scratch workspace — `{name}` → `{path}`; null = refused. */
    suspend fun createScratchWorkspace(name: String): String? =
        (transport.createScratchWorkspace(name)["path"] as? kotlinx.serialization.json.JsonPrimitive)?.content

    /**
     * `off-peak-task` channel `list` with the desktop's own list projection mirrored
     * CLIENT-SIDE (zcodeAgentService.ts:2666-2761): workspace filter, createdAt DESC,
     * cap 20.
     */
    suspend fun offPeakTasks(workspaceKey: String): List<OffPeakTaskRow> =
        OffPeakTaskRow.decodeList(transport.offPeakTaskList())
            .filter { it.workspaceKey == workspaceKey }
            .sortedByDescending { it.createdAt }
            .take(20)

    // ---------------------------------------------------------------- GAP-7: plugins

    /** `listPlugins` → CLI `plugins/list` (v4-wire.md §12.3). Viewer-only — INV-9. */
    suspend fun plugins(workspacePath: String?, workspaceIdentity: String?): PluginsView =
        PluginsView.decode(transport.listPlugins(workspacePath, workspaceIdentity))

    // ---------------------------------------------------------------- GAP-8: skills

    /** `getSkillReferenceCatalog` → CLI `skills/referenceCatalog` (v4-wire.md §12.4). */
    suspend fun skillCatalog(
        workspacePath: String?,
        workspaceIdentity: String?,
        sessionId: String? = null,
    ): SkillCatalog =
        SkillCatalog.decode(transport.skillReferenceCatalog(workspacePath, workspaceIdentity, sessionId))

    // ---------------------------------------------------------------- GAP-9: mcp

    /** `listMcpServerStatuses` with `mode: "status"` (passive; v4-wire.md §12.5). */
    suspend fun mcpStatuses(workspacePath: String?, workspaceIdentity: String?): McpStatuses =
        McpStatuses.decode(transport.mcpServerStatuses(workspacePath, workspaceIdentity))

    // ---------------------------------------------------------------- GAP-3: coding-plan quota (ADR-020)

    /**
     * `usage-stats` channel `getEntitlementSnapshot` (v4-wire.md §13.1) — the
     * desktop's own quota-panel read, POLLED client-side (one read per Usage-screen
     * load + explicit refresh; ADR-020: no push channel exists). The reset words
     * land with GAP-4 behind the typed-confirm gate.
     */
    suspend fun entitlement(request: kotlinx.serialization.json.JsonObject = kotlinx.serialization.json.JsonObject(emptyMap())): UsageEntitlement =
        UsageEntitlement.decode(transport.usageEntitlementSnapshot(request))

    // ---------------------------------------------------------------- GAP-4: reset words (ADR-020)

    /**
     * `getCodingPlanResetStatus` (v4-wire.md §13.2). [accountAccess] is the
     * phone-DERIVED plan descriptor (domain accountAccessFromProviderId) — the
     * [accountAccessFromProviderId] null case means "no reset surface".
     */
    suspend fun resetStatus(preferredProviderId: String, accountAccess: JsonObject): CodingPlanResetStatus =
        CodingPlanResetStatus.decode(transport.codingPlanResetStatus(preferredProviderId, accountAccess))

    /** `requestCodingPlanResetOpportunity` — server-side grant evaluation. */
    suspend fun resetOpportunity(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
    ): ResetOpportunityResult =
        ResetOpportunityResult.decode(
            transport.codingPlanResetOpportunity(preferredProviderId, accountAccess, idempotencyKey),
        )

    /**
     * `useCodingPlanReset` — the quota plane's ONLY destructive-adjacent word
     * (ADR-020/T19): consumes a server-granted reset opportunity. The UI layer
     * reaches this ONLY behind the typed-confirm dialog.
     */
    suspend fun useReset(
        preferredProviderId: String,
        accountAccess: JsonObject,
        idempotencyKey: String,
        resetType: String,
    ): ResetUseResult = ResetUseResult.decode(
        transport.codingPlanResetUse(preferredProviderId, accountAccess, idempotencyKey, resetType),
    )

    /** `markCodingPlanResetHistoryRead` — clears the desktop's unread cursor. */
    suspend fun markResetHistoryRead(preferredProviderId: String, accountAccess: JsonObject) {
        transport.codingPlanResetHistoryRead(preferredProviderId, accountAccess)
    }
}
