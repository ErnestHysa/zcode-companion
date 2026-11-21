package com.zcode.android.core.transport

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * pass-0 Phase 10 (G83/G84/G101/G98) — the INV-9 typed-surface pin for the
 * desktop-parity read mirrors: the V4Plane surface gains EXACTLY these five
 * service-channel reads (client-scenes list / output-style list+active /
 * cua-permission getStatus / source-server-management getSnapshot — asar
 * channel registry `ec475e6e…`, ChannelNames.ClientScenes="client-scenes",
 * OutputStyle="output-style", CuaPermission="cua-permission",
 * SourceServerManagement="source-server-management"). No write method for any
 * mirrored plane exists anywhere on the typed surface (the mirrors are
 * read-only; authoring stays desktop-side).
 */
class V4PlaneParityMirrorSurfaceTest {

    private val phase10ReadMethods = setOf(
        "clientScenesList",
        "outputStyleList",
        "outputStyleActive",
        "cuaPermissionStatus",
        "sourceServerSnapshot",
    )

    /** Any of these words on the mirror surface = a write leak (fail loudly). */
    private val bannedWriteVerbs = listOf(
        "install", "uninstall", "update", "enable", "disable", "configure",
        "create", "delete", "remove", "restore", "cancel", "pause", "run",
        "restart", "connect", "disconnect", "set",
    )

    @Test
    fun `phase-10 parity-mirror surface is exactly the five read methods`() {
        val names = V4Plane::class.java.methods.map { it.name }.toSet()
        assertTrue(
            "missing phase-10 read methods: ${phase10ReadMethods - names}",
            phase10ReadMethods.all { it in names },
        )
    }

    @Test
    fun `phase-10 mirror methods carry no write verbs`() {
        val names = V4Plane::class.java.methods.map { it.name }
        val mirrors = names.filter { it in phase10ReadMethods }
        assertTrue("phase-10 methods missing", mirrors.size == phase10ReadMethods.size)
        mirrors.forEach { name ->
            val lower = name.lowercase()
            val banned = bannedWriteVerbs.filter { lower.contains(it) }
            assertTrue("read-only method `$name` carries write verbs $banned", banned.isEmpty())
        }
    }
}
