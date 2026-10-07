package com.zcode.android.app.connect

import com.zcode.android.app.settings.UsagePort
import com.zcode.android.app.settings.PluginsPort
import com.zcode.android.app.settings.CodingPlanPort
import com.zcode.android.domain.model.ResetOpportunityResult
import com.zcode.android.domain.model.ResetUseResult
import com.zcode.android.domain.model.accountAccessFromProviderId
import com.zcode.android.app.settings.McpPort
import com.zcode.android.app.settings.SkillsPort
import com.zcode.android.features.tasks.port.AutomationsPort
import java.util.TimeZone
import kotlinx.coroutines.CancellationException

/**
 * Phase 3 app-side viewer port bindings (INV-2/INV-6: screens see ONLY these —
 * never the transport). Thin adapters over the hub-owned viewer tool plane; the
 * §13.3 fallback semantics (channel refused / not connected → error face)
 * live HERE. READ-ONLY: none of these ports expose a write method (INV-9 —
 * the write half of these channels is Deferred scope and exists nowhere).
 */

class DefaultUsagePort(
    private val hub: ConnectionHub,
) : UsagePort {
    override suspend fun appUsage(range: String): UsagePort.Result {
        // Usage is app-level, not workspace-scoped — it needs a connected transport,
        // not an active workspace (the CLI aggregates across the whole desktop).
        return try {
            // The desktop passes the CLIENT's IANA zone (useUsageStats.ts:210-213).
            UsagePort.Result(hub.viewerTools().appUsageStats(range, TimeZone.getDefault().id), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("usageport.appUsage", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            UsagePort.Result(null, e.message ?: "usage is unavailable on this connection")
        }
    }
}

class DefaultAutomationsPort(
    private val hub: ConnectionHub,
) : AutomationsPort {
    override suspend fun lists(): AutomationsPort.Lists {
        val target = hub.activeWorkspaceTarget()
        val workspacePath = target?.first
            ?: return AutomationsPort.Lists(null, null, "no active workspace")
        // §7.11 identity key (identity ?: path) — the desktop's resolveWorkspaceKey.
        val workspaceKey = target.second?.trim()?.takeIf { it.isNotEmpty() } ?: workspacePath
        return try {
            val tools = hub.viewerTools()
            AutomationsPort.Lists(
                automations = tools.automations(workspacePath, target.second),
                offPeak = tools.offPeakTasks(workspaceKey),
                error = null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.lists", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            AutomationsPort.Lists(null, null, e.message ?: "automations unavailable")
        }
    }
}

class DefaultPluginsPort(
    private val hub: ConnectionHub,
) : PluginsPort {
    override suspend fun plugins(): PluginsPort.Result {
        val target = hub.activeWorkspaceTarget()
            ?: return PluginsPort.Result(null, "plugins are unavailable on this connection")
        return try {
            PluginsPort.Result(hub.viewerTools().plugins(target.first, target.second), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("pluginsport.plugins", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            PluginsPort.Result(null, e.message ?: "plugins are unavailable on this connection")
        }
    }
}

class DefaultSkillsPort(
    private val hub: ConnectionHub,
) : SkillsPort {
    override suspend fun catalog(): SkillsPort.Result {
        val target = hub.activeWorkspaceTarget()
            ?: return SkillsPort.Result(null, "the skills catalog is unavailable on this connection")
        return try {
            SkillsPort.Result(hub.viewerTools().skillCatalog(target.first, target.second), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("skillsport.catalog", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            SkillsPort.Result(null, e.message ?: "the skills catalog is unavailable on this connection")
        }
    }
}

class DefaultCodingPlanPort(
    private val hub: ConnectionHub,
) : CodingPlanPort {
    override suspend fun entitlement(): CodingPlanPort.Snapshot {
        // Quota is app-level (the account lives on the desktop), not workspace-scoped.
        return try {
            CodingPlanPort.Snapshot(hub.viewerTools().entitlement(), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("codingplanport.entitlement", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            CodingPlanPort.Snapshot(null, e.message ?: "the coding-plan quota is unavailable on this connection")
        }
    }

    /** The reset surface exists ONLY for a derivable builtin provider id —
     *  the ADR-020 rule: unknown ids derive NOTHING (never guessed). */
    private fun access(providerId: String, entitled: Boolean) =
        accountAccessFromProviderId(providerId, entitled)

    override suspend fun resetStatus(providerId: String, entitled: Boolean): CodingPlanPort.ResetStatusResult {
        val access = access(providerId, entitled)
            ?: return CodingPlanPort.ResetStatusResult(null, "no coding-plan provider")
        return try {
            CodingPlanPort.ResetStatusResult(hub.viewerTools().resetStatus(providerId, access), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CodingPlanPort.ResetStatusResult(null, e.message ?: "reset status unavailable")
        }
    }

    override suspend fun requestOpportunity(
        providerId: String,
        entitled: Boolean,
        idempotencyKey: String,
    ): ResetOpportunityResult? {
        val access = access(providerId, entitled) ?: return null
        return try {
            hub.viewerTools().resetOpportunity(providerId, access, idempotencyKey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // grant evaluation failures are non-fatal (desktop parity: log + skip)
            hub.log.warn("codingplanport.opportunity", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            null
        }
    }

    override suspend fun useReset(
        providerId: String,
        entitled: Boolean,
        idempotencyKey: String,
        resetType: String,
    ): ResetUseResult {
        val access = access(providerId, entitled)
            ?: throw IllegalStateException("no coding-plan provider")
        // Errors PROPAGATE — the section surfaces the honest failure face.
        return hub.viewerTools().useReset(providerId, access, idempotencyKey, resetType)
    }

    override suspend fun markHistoryRead(providerId: String, entitled: Boolean) {
        val access = access(providerId, entitled) ?: return
        runCatching { hub.viewerTools().markResetHistoryRead(providerId, access) }
    }
}

class DefaultMcpPort(
    private val hub: ConnectionHub,
) : McpPort {
    override suspend fun statuses(): McpPort.Result {
        val target = hub.activeWorkspaceTarget()
            ?: return McpPort.Result(null, "MCP server statuses are unavailable on this connection")
        return try {
            McpPort.Result(hub.viewerTools().mcpStatuses(target.first, target.second), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("mcpport.statuses", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            McpPort.Result(null, e.message ?: "MCP server statuses are unavailable on this connection")
        }
    }
}
