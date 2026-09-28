package com.zcode.android.domain

import com.zcode.android.domain.ids.CommandId
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.ids.WorkspaceIdentityKey
import com.zcode.android.domain.model.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** §9.13/§16 identity rules: typed wrappers prevent identifier mix-ups. */
class IdsTest {

    @Test
    fun `workspace identity key derives per the verbatim AGENTS rule`() {
        // identity key = workspaceIdentity?.trim() || workspacePath (§7.11)
        assertEquals(WorkspaceIdentityKey("ws-ident"), WorkspaceIdentityKey.of("ws-ident", "/some/path"))
        assertEquals(WorkspaceIdentityKey("ws-ident"), WorkspaceIdentityKey.of("  ws-ident  ", "/some/path"))
        assertEquals(WorkspaceIdentityKey("/some/path"), WorkspaceIdentityKey.of(null, "/some/path"))
        assertEquals(WorkspaceIdentityKey("/some/path"), WorkspaceIdentityKey.of("", "/some/path"))
        assertEquals(WorkspaceIdentityKey("/some/path"), WorkspaceIdentityKey.of("   ", "/some/path"))
    }

    @Test
    fun `blank identities are rejected`() {
        assertFailsWith<IllegalArgumentException> { WorkspaceIdentityKey("") }
        assertFailsWith<IllegalArgumentException> { EndpointId("") }
        assertFailsWith<IllegalArgumentException> { SessionId(" ") }
        assertFailsWith<IllegalArgumentException> { CommandId("") }
    }

    @Test
    fun `distinct id types never compare equal even with equal raw`() {
        // value classes over the same raw are distinct TYPES — mixing them in typed
        // positions is a compile error; boxed as Any they still differ at runtime.
        val a: Any = EndpointId("x")
        val b: Any = SessionId("x")
        assertTrue(a != b)
        assertEquals((a as EndpointId).raw, (b as SessionId).raw)
    }
}

/** §16 "status enum tolerant" — unknown wire statuses never crash (INV-8). */
class TaskStatusTest {
    @Test
    fun `tolerant parse`() {
        assertEquals(TaskStatus.RUNNING, TaskStatus.fromWire("running"))
        assertEquals(TaskStatus.UNKNOWN, TaskStatus.fromWire("someFutureStatus"))
        assertEquals(TaskStatus.UNKNOWN, TaskStatus.fromWire(null))
    }
}
