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
        automationWriteRequest(write)

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

    override suspend fun runNow(automationId: String): Boolean =
        runNowTyped(automationId) != AutomationsPort.RunNowOutcome.FAILED

    // ---------------------------------------------------------------- Phase 4B (J2 / A3.2 / B2.11)

    /** automation-types.ts: the create limit surfaces as `AUTOMATION_CREATE_LIMIT_REACHED` in the error text. */
    override suspend fun createTyped(workspaceKey: String, write: AutomationsPort.AutomationWrite): AutomationsPort.CreateOutcome {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return AutomationsPort.CreateOutcome.FAILED
        return try {
            hub.viewerTools().createAutomation(workspacePath, workspaceIdentity, writeRequest(write))
            AutomationsPort.CreateOutcome.OK
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.create", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            if (e.message?.contains(AUTOMATION_CREATE_LIMIT_ERROR_CODE) == true) AutomationsPort.CreateOutcome.LIMIT_REACHED
            else AutomationsPort.CreateOutcome.FAILED
        }
    }

    /** B2.11: `{status: queued|duplicate}` lands as a typed outcome (never fire-and-forget). */
    override suspend fun runNowTyped(automationId: String): AutomationsPort.RunNowOutcome {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return AutomationsPort.RunNowOutcome.FAILED
        return try {
            val result = hub.viewerTools().runAutomationNow(workspacePath, workspaceIdentity, automationId)
            when ((result["status"] as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "duplicate" -> AutomationsPort.RunNowOutcome.DUPLICATE
                else -> AutomationsPort.RunNowOutcome.QUEUED
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.runNow", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            AutomationsPort.RunNowOutcome.FAILED
        }
    }

    override suspend fun runs(automationId: String): AutomationsPort.RunsResult {
        val (workspacePath, workspaceIdentity) = workspaceTarget()
            ?: return AutomationsPort.RunsResult(null, "no active workspace")
        return try {
            AutomationsPort.RunsResult(hub.viewerTools().automationRuns(workspacePath, workspaceIdentity, automationId), null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.runs", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            AutomationsPort.RunsResult(null, e.message ?: "run history unavailable")
        }
    }

    override suspend fun deleteRun(runId: String): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().deleteAutomationRun(workspacePath, workspaceIdentity, runId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.deleteRun", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    override suspend fun restart(automationId: String): Boolean {
        val (workspacePath, workspaceIdentity) = workspaceTarget() ?: return false
        return try {
            hub.viewerTools().restartAutomation(workspacePath, workspaceIdentity, automationId)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hub.log.warn("automationsport.restart", "${e.javaClass.simpleName}: ${e.message?.take(140)}")
            false
        }
    }

    private companion object {
        /** automation-types.ts AUTOMATION_CREATE_LIMIT_ERROR_CODE. */
        const val AUTOMATION_CREATE_LIMIT_ERROR_CODE = "AUTOMATION_CREATE_LIMIT_REACHED"
    }
    // ---------------------------------------------------------------- Phase 5A (A3.10 as shipped)

    // ---------------------------------------------------------------- Phase 5A (L6.4): saved-workflow hub

    private suspend fun <T> hubRead(tag: String, block: suspend (com.zcode.android.data.session.AgentFacadeClient) -> T): T? = try {
        block(hub.agentFacade())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        hub.log.warn("automationsport.workflows.$tag", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        null
    }

    override suspend fun savedWorkflows(): com.zcode.android.domain.model.SavedWorkflowList? =
        hubRead("list") { com.zcode.android.domain.model.SavedWorkflowList.decode(it.listSavedWorkflows()) }

    override suspend fun savedWorkflowRuns(name: String?, limit: Int): List<com.zcode.android.domain.model.SavedWorkflowRun>? =
        hubRead("runs") { com.zcode.android.domain.model.SavedWorkflowRun.decodeList(it.listSavedWorkflowRuns(name, limit)) }

    override suspend fun savedWorkflow(name: String, scope: String?): com.zcode.android.domain.model.SavedWorkflow? =
        hubRead("get") { facade ->
            val obj = facade.getSavedWorkflow(name, scope)
            val ok = (obj["ok"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: true
            if (!ok) null else com.zcode.android.domain.model.SavedWorkflow.decode(
                // FJ ok-arm carries name/path/scope + meta{description, whenToUse, args}; flatten meta for the row decoder.
                kotlinx.serialization.json.buildJsonObject {
                    obj.forEach { (k, v) -> if (k != "meta") put(k, v) }
                    (obj["meta"] as? kotlinx.serialization.json.JsonObject)?.forEach { (k, v) -> put(k, v) }
                },
            )
        }

    override suspend fun pauseOffPeak(offPeakTaskId: String): Boolean = offPeak("pause") { hub.taskService().offPeakPause(offPeakTaskId) }

    override suspend fun continueOffPeak(offPeakTaskId: String): Boolean = offPeak("continue") { hub.taskService().offPeakContinue(offPeakTaskId) }

    private suspend fun offPeak(tag: String, call: suspend () -> Any?): Boolean = try {
        call(); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        hub.log.warn("automationsport.offpeak.$tag", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
        false
    }

}

/**
 * J2-target: the automation write request — ZCodeAutomationCreateParams / UpdateParams
 * (automation-types.ts). A CUSTOM repeat carries the authoritative `scheduleRule`
 * beside the compat `cronExpr`; plain-cron kinds carry none; `clearScheduleRule`
 * (update) sends an explicit null so a carried rule is dropped — "undefined=不修改；
 * null=恢复为普通 cron".
 */
internal fun automationWriteRequest(write: AutomationsPort.AutomationWrite): kotlinx.serialization.json.JsonObject =
    kotlinx.serialization.json.buildJsonObject {
        put("title", kotlinx.serialization.json.JsonPrimitive(write.title))
        put("prompt", kotlinx.serialization.json.JsonPrimitive(write.prompt))
        put("cronExpr", kotlinx.serialization.json.JsonPrimitive(write.schedule.cronExprOrPlaceholder()))
        write.schedule.everyMinutes?.let {
            put("relativeDelayMinutes", kotlinx.serialization.json.JsonPrimitive(it))
            put("recurring", kotlinx.serialization.json.JsonPrimitive(false))
        } ?: run {
            put("recurring", kotlinx.serialization.json.JsonPrimitive(write.recurring))
        }
        write.maxRuns?.let { put("maxRuns", kotlinx.serialization.json.JsonPrimitive(it)) }
        write.endAt?.let { put("endAt", kotlinx.serialization.json.JsonPrimitive(it)) }
        val rule = write.scheduleRule
        when {
            rule != null -> put("scheduleRule", kotlinx.serialization.json.buildJsonObject {
                put("unit", kotlinx.serialization.json.JsonPrimitive(rule.unit))
                put("interval", kotlinx.serialization.json.JsonPrimitive(rule.interval))
                put("hour", kotlinx.serialization.json.JsonPrimitive(rule.hour))
                put("minute", kotlinx.serialization.json.JsonPrimitive(rule.minute))
                put("anchorAt", kotlinx.serialization.json.JsonPrimitive(rule.anchorAt))
                put("weekdays", kotlinx.serialization.json.JsonArray(rule.weekdays.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                put("monthDays", kotlinx.serialization.json.JsonArray(rule.monthDays.map { kotlinx.serialization.json.JsonPrimitive(it) }))
                rule.months?.let { m -> put("months", kotlinx.serialization.json.JsonArray(m.map { kotlinx.serialization.json.JsonPrimitive(it) })) }
                rule.monthlyMode?.let { put("monthlyMode", kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            write.clearScheduleRule -> put("scheduleRule", kotlinx.serialization.json.JsonNull)
        }
        if (write.modelProvider != null && write.modelId != null) {
            put("modelSelection", kotlinx.serialization.json.buildJsonObject {
                put("providerId", kotlinx.serialization.json.JsonPrimitive(write.modelProvider))
                put("modelId", kotlinx.serialization.json.JsonPrimitive(write.modelId))
            })
        }
        write.mode?.let { put("mode", kotlinx.serialization.json.JsonPrimitive(it)) }
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
