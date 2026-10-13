package com.zcode.android.app.connect

import com.zcode.android.app.settings.UsagePort
import com.zcode.android.app.settings.PluginsPort
import com.zcode.android.app.settings.CodingPlanPort
import com.zcode.android.domain.model.ResetOpportunityResult
import com.zcode.android.domain.model.ResetUseResult
import com.zcode.android.domain.model.accountAccessFromProviderId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    /** FIELD (2026-10-12): automation CRUD (zcodeAgent.ts:679-688 surface). */
    private fun writeRequest(write: AutomationsPort.AutomationWrite): kotlinx.serialization.json.JsonObject =
        kotlinx.serialization.json.buildJsonObject {
            put("title", kotlinx.serialization.json.JsonPrimitive(write.title))
            put("prompt", kotlinx.serialization.json.JsonPrimitive(write.prompt))
            put("cronExpr", kotlinx.serialization.json.JsonPrimitive(write.schedule.cronExprOrPlaceholder()))
            write.schedule.everyMinutes?.let {
                put("relativeDelayMinutes", kotlinx.serialization.json.JsonPrimitive(it))
                put("recurring", kotlinx.serialization.json.JsonPrimitive(false))
            } ?: run {
                put("recurring", kotlinx.serialization.json.JsonPrimitive(true))
            }
            if (write.modelProvider != null && write.modelId != null) {
                put("modelSelection", kotlinx.serialization.json.buildJsonObject {
                    put("providerId", kotlinx.serialization.json.JsonPrimitive(write.modelProvider))
                    put("modelId", kotlinx.serialization.json.JsonPrimitive(write.modelId))
                })
            }
            write.mode?.let { put("mode", kotlinx.serialization.json.JsonPrimitive(it)) }
        }

    private fun workspaceTarget(): Pair<String, String?>? {
        val target = hub.activeWorkspaceTarget() ?: return null
        val path = target.first ?: return null
        return path to target.second
    }

    override suspend fun create(workspaceKey: String, write: AutomationsPort.AutomationWrite): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().createAutomation(workspacePath, workspaceIdentity, writeRequest(write))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.create", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    override suspend fun update(automationId: String, write: AutomationsPort.AutomationWrite): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().updateAutomation(workspacePath, workspaceIdentity, automationId, writeRequest(write))
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.update", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    override suspend fun delete(automationId: String): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().deleteAutomation(workspacePath, workspaceIdentity, automationId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.delete", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    override suspend fun setEnabled(automationId: String, enabled: Boolean): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().setAutomationEnabled(workspacePath, workspaceIdentity, automationId, enabled)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.setEnabled", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    override suspend fun runNow(automationId: String): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().runAutomationNow(workspacePath, workspaceIdentity, automationId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.runNow", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
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

/**
 * The desktop usage-summary's request shape, verbatim keys
 * (WorkspaceSidebarFooterUsageSummary.tsx: includeSubscription +
 * preferredProviderId + the static zhipu-account descriptor +
 * requirePreferredProvider/allowDisabledPreferredProvider + allowEnvApiKey:false).
 * Without these the service resolves nothing and answers
 * unavailableReason="not_configured" — the "no account connected" face
 * (live-verified 2026-10-09; fixture entitlement-live-zai-coding-plan).
 */
internal fun codingPlanEntitlementRequest(providerId: String): JsonObject {
    val access = accountAccessFromProviderId(providerId, entitled = true)
        ?: return JsonObject(emptyMap())
    return buildJsonObject {
        put("includeSubscription", JsonPrimitive(true))
        put("preferredProviderId", JsonPrimitive(providerId))
        put("accountAccess", access)
        put("allowDisabledPreferredProvider", JsonPrimitive(true))
        put("requirePreferredProvider", JsonPrimitive(true))
        put("allowEnvApiKey", JsonPrimitive(false))
    }
}

class DefaultCodingPlanPort(
    private val hub: ConnectionHub,
) : CodingPlanPort {
    override suspend fun entitlement(): CodingPlanPort.Snapshot {
        // Quota is app-level (the account lives on the desktop), not workspace-scoped.
        // The desktop's own usage summary (WorkspaceSidebarFooterUsageSummary.tsx)
        // addresses the builtin coding-plan provider DIRECTLY: preferredProviderId +
        // the static zhipu-account descriptor + requirePreferredProvider — without
        // them the service resolves nothing and answers not_configured (the
        // "no account connected" face; live-verified 2026-10-09).
        val zai = try {
            CodingPlanPort.Snapshot(
                hub.viewerTools().entitlement(codingPlanEntitlementRequest("account:zai-individual-coding-plan")),
                null,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("codingplanport.entitlement", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            CodingPlanPort.Snapshot(null, e.message ?: "the coding-plan quota is unavailable on this connection")
        }
        val zaiDead = zai.entitlement == null ||
            zai.entitlement.limits.isEmpty() && zai.entitlement.remainingCount == null
        if (!zaiDead) return zai
        // bigmodel twin (the desktop footer queries both families and shows the
        // one that answers).
        return try {
            val bigmodel = CodingPlanPort.Snapshot(
                hub.viewerTools().entitlement(codingPlanEntitlementRequest("account:bigmodel-individual-coding-plan")),
                null,
            )
            if (bigmodel.entitlement != null &&
                (bigmodel.entitlement.limits.isNotEmpty() || bigmodel.entitlement.remainingCount != null)
            ) {
                bigmodel
            } else {
                zai
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            zai
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
