package com.zcode.android.data.session

import com.zcode.android.core.transport.AppTransport
import com.zcode.android.domain.model.AppUsageSnapshot
import com.zcode.android.domain.model.AutomationRow
import com.zcode.android.domain.model.McpStatuses
import com.zcode.android.domain.model.OffPeakTaskRow
import com.zcode.android.domain.model.PluginsView
import com.zcode.android.domain.model.SkillCatalog

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
}
