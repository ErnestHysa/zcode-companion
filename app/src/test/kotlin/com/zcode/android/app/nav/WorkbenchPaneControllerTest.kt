package com.zcode.android.app.nav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * G61 (vc52 Phase 13): the workbench detail pane's VM lifecycle controller. The
 * campaign's F-33-A lesson: a retained conversation VM bound to a dead container
 * is the zombie-screen generator — the workbench must hold EXACTLY ONE live
 * conversation VM: re-acquiring the same task returns the same VM (recomposes
 * never re-create), a different task tears the old one down BEFORE creating the
 * next, and closing tears down.
 */
class WorkbenchPaneControllerTest {

    private class FakeVm(val id: String) {
        var tornDown = false
    }

    @Test
    fun `same task acquires the same instance - recomposition never re-creates`() {
        var creations = 0
        val c = WorkbenchPaneController<FakeVm>(teardown = { it.tornDown = true })
        val first = c.acquire("t1") { creations++; FakeVm(it) }
        val second = c.acquire("t1") { creations++; FakeVm(it) }
        assertSame(first, second)
        assertEquals(1, creations)
        assertFalse(first.tornDown)
    }

    @Test
    fun `a different task tears the old VM down and creates exactly one new`() {
        var creations = 0
        val c = WorkbenchPaneController<FakeVm>(teardown = { it.tornDown = true })
        val a = c.acquire("t1") { creations++; FakeVm(it) }
        val b = c.acquire("t2") { creations++; FakeVm(it) }
        assertTrue(a.tornDown)
        assertFalse(b.tornDown)
        assertEquals(2, creations)
    }

    @Test
    fun `close tears down and the next acquire creates fresh`() {
        var creations = 0
        val c = WorkbenchPaneController<FakeVm>(teardown = { it.tornDown = true })
        val a = c.acquire("t1") { creations++; FakeVm(it) }
        c.close()
        assertTrue(a.tornDown)
        val b = c.acquire("t1") { creations++; FakeVm(it) }
        assertFalse(b.tornDown)
        assertEquals(2, creations)
    }

    @Test
    fun `teardown-before-create ordering - the zombie class never coexists`() {
        val order = mutableListOf<String>()
        val strict = WorkbenchPaneController<FakeVm>(teardown = {
            order.add("teardown-${it.id}")
            it.tornDown = true
        })
        strict.acquire("t1") { order.add("create-t1"); FakeVm("t1") }
        strict.acquire("t2") { order.add("create-t2"); FakeVm("t2") }
        assertEquals(listOf("create-t1", "teardown-t1", "create-t2"), order)
    }
}
