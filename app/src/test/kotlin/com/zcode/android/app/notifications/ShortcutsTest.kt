package com.zcode.android.app.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** H2.2 (Phase 2F): shortcut intake routing + the dynamic Approvals shortcut with the live count. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ShortcutsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `intake maps the shortcut extra to a target and ignores plain launches`() {
        assertEquals(AppShortcuts.Target.NEW_TASK, AppShortcuts.intake(Intent().putExtra(AppShortcuts.EXTRA, "new_task")))
        assertEquals(AppShortcuts.Target.APPROVALS, AppShortcuts.intake(Intent().putExtra(AppShortcuts.EXTRA, "approvals")))
        assertEquals(AppShortcuts.Target.CONNECTION, AppShortcuts.intake(Intent().putExtra(AppShortcuts.EXTRA, "connection")))
        assertNull(AppShortcuts.intake(Intent()))
        assertNull(AppShortcuts.intake(Intent().putExtra(AppShortcuts.EXTRA, "bogus")))
        assertNull(AppShortcuts.intake(null))
    }

    @Test
    fun `the dynamic approvals shortcut carries the live count`() {
        AppShortcuts.publishApprovalsCount(context, 3)
        val dyn = ShortcutManagerCompat.getDynamicShortcuts(context)
        val approvals = dyn.firstOrNull { it.id == AppShortcuts.ID_APPROVALS }
        assertTrue("the approvals shortcut must be published", approvals != null)
        assertTrue("label carries the count: ${approvals!!.shortLabel}", approvals.shortLabel.contains("3"))
        AppShortcuts.publishApprovalsCount(context, 0)
        val reset = ShortcutManagerCompat.getDynamicShortcuts(context).first { it.id == AppShortcuts.ID_APPROVALS }
        assertEquals(context.getString(com.zcode.android.app.R.string.shortcut_approvals), reset.shortLabel.toString())
    }
}
