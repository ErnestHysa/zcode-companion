package com.zcode.android.app.connect

import com.zcode.android.app.settings.UsagePort
import com.zcode.android.app.settings.PluginsPort
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
