package com.zcode.android.core.transport

import org.junit.Assert.assertEquals
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

    // ---------------------------------------------------------------- Phase 4 (ADR-020, v4-wire.md §13)

    /**
     * Phase-4 quota plane: FOUR read methods — the entitlement read (GAP-3) and
     * the reset-plane reads/markers. Verb checks are TOKEN-based here: the
     * sanctioned word "reset" contains the substring "set", so a naive
     * substring scan cannot distinguish `codingPlanResetStatus` (a read) from a
     * hypothetical `setCodingPlan…` (a write).
     */
    private val phase4ReadMethods = setOf(
        "usageEntitlementSnapshot",
        "codingPlanResetStatus",
        "codingPlanResetOpportunity",
        "codingPlanResetHistoryRead",
    )

    private fun camelTokens(name: String): Set<String> =
        name.split("(?=[A-Z])".toRegex()).map { it.lowercase() }.filter { it.isNotEmpty() }.toSet()

    @Test
    fun `phase-4 quota read methods exist and carry no write-verb tokens`() {
        val names = V4Plane::class.java.methods.map { it.name }.toSet()
        assertTrue("missing phase-4 read methods: ${phase4ReadMethods - names}", phase4ReadMethods.all { it in names })
        phase4ReadMethods.forEach { name ->
            val banned = bannedWriteVerbs.filter { it in camelTokens(name) }
            assertTrue("read-only method `$name` carries write-verb tokens $banned", banned.isEmpty())
        }
    }

    @Test
    fun `codingPlanResetUse is the quota plane's ONLY sanctioned write word (ADR-020)`() {
        val names = V4Plane::class.java.methods.map { it.name }.toSet()
        // the destructive-adjacent word EXISTS — ADR-020 rules it in scope, gated
        // behind the typed-confirm dialog (pinned by CodingPlanQuotaResetUiTest)
        assertTrue("the ADR-020 reset word must exist", "codingPlanResetUse" in names)
        // …and within the QUOTA PLANE it is the only mutation-shaped member: the
        // plane's six methods are the four reads + the use word + nothing else.
        // (Other planes keep their own pins — terminal.create etc. are
        // pre-existing sanctioned surfaces, out of this test's scope.)
        val quotaPlane = names.filter {
            camelTokens(it).contains("quota") || camelTokens(it).contains("reset") ||
                it == "usageEntitlementSnapshot"
        }
        assertEquals(
            (phase4ReadMethods + "codingPlanResetUse").sorted(),
            quotaPlane.sorted(),
        )
        quotaPlane.filter { it != "codingPlanResetUse" }.forEach { name ->
            val banned = bannedWriteVerbs.filter { it in camelTokens(name) }
            assertTrue("quota-plane method `$name` carries write-verb tokens $banned", banned.isEmpty())
        }
    }
}
