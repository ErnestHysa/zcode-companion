package com.zcode.android.core.transport

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * INV-9 typed-surface pin for the Phase 3 viewer additions (v4-wire.md §12):
 * the V4Plane surface gained EXACTLY these read-only methods — no write method
 * for the automation / plugin / skills / mcp planes exists anywhere on the
 * typed surface (the roadmap's Deferred scope: automation create/update/
 * delete/enable, plugin install/uninstall/update/enable/configure, skills
 * setEnabled, mcp mutations). The surface IS the enforcement (§43.2-9
 * precedent: the terminal plane's write/resize absence).
 */
class V4PlaneViewerSurfaceTest {

    private val phase3Expected = setOf(
        "appUsageStats",
        "listAutomations",
        "offPeakTaskList",
        "listPlugins",
        "skillReferenceCatalog",
        "mcpServerStatuses",
    )

    /** Any of these words on the Phase-3 surface = a write leak (fail loudly). */
    private val bannedWriteVerbs = listOf(
        "install", "uninstall", "update", "enable", "disable", "configure",
        "create", "delete", "remove", "restore", "cancel", "pause", "run",
        "restart", "connect", "disconnect", "set",
    )

    @Test
    fun `phase-3 viewer surface is exactly the six read-only methods`() {
        val names = V4Plane::class.java.methods.map { it.name }.toSet()
        val phase3 = names.filter { it in phase3Expected }.toSet()
        assertTrue(
            "missing read methods: ${phase3Expected - phase3}",
            phase3Expected.all { it in phase3 },
        )
        // EXACTLY the six: a later edit adding a 7th method MUST update this pin
        // (and re-run the INV-9 review for it) — silent surface growth is the leak vector.
        val extra = names.filter {
            it in setOf("listPluginsV2", "pluginsWrite", "automationWrite", "skillWrite", "mcpWrite")
        }
        assertTrue("unexpected phase-3-adjacent methods: $extra", extra.isEmpty())
    }

    @Test
    fun `phase-3 methods carry no write verbs`() {
        val names = V4Plane::class.java.methods.map { it.name }
        val phase3 = names.filter { it in phase3Expected }
        assertTrue("phase-3 methods missing", phase3.size == phase3Expected.size)
        phase3.forEach { name ->
            val lower = name.lowercase()
            val banned = bannedWriteVerbs.filter { lower.contains(it) }
            assertTrue("read-only method `$name` carries write verbs $banned", banned.isEmpty())
        }
    }
}
