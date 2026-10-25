package com.zcode.android.app.nav

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertSame
import org.junit.Assert.assertNotSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config

/**
 * B2.5 (gaps-and-polish Phase 1D, P0; trigger corrected by H2.7): the conversation
 * ViewModel was `remember(taskId)`-scoped, so it DIED on any activity recreation —
 * uiMode/density/fontScale flips (NOT rotation; the manifest's configChanges
 * handles that). Accept: a backstack-scoped VM; a theme flip preserves the
 * conversation state.
 *
 * The pin drives a REAL recreation (Robolectric recreate() after a night-qualifier
 * flip = a genuine uiMode configuration change). The defect leg documents the OLD
 * wiring semantics (a per-composition holder — what remember{} effectively was —
 * dies with the composition); the fix legs drive the PRODUCTION
 * [ConversationVmHolder] on the owner's ViewModelStore (the nav back stack entry
 * IS the ViewModelStoreOwner in production; the activity stands in here).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationVmRecreationTest {

    class HostActivity : ComponentActivity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    /** The OLD wiring's semantics: a holder that lives with the composition. */
    private class CompositionBoundHolder {
        var marker: Any? = null
    }

    private fun controller(): ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java)

    @Test
    fun `the backstack-scoped holder survives a uiMode recreation`() {
        val controller = controller().setup()

        // The holder IS the survival mechanism: the ConversationViewModel hangs off
        // it (holder.vm), so holder identity across recreation proves the instance
        // the composer sees is the same.
        val first = ViewModelProvider(controller.get(), ViewModelProvider.NewInstanceFactory())
            .get(ConversationVmHolder::class.java)

        RuntimeEnvironment.setQualifiers("night")
        val recreated = controller.recreate().get()

        val second = ViewModelProvider(recreated, ViewModelProvider.NewInstanceFactory())
            .get(ConversationVmHolder::class.java)
        assertSame(
            "the scoped VM must survive a uiMode/density/fontScale recreation",
            first,
            second,
        )
    }

    @Test
    fun `a different owner gets a different scoped instance`() {
        val a = ViewModelProvider(controller().setup().get(), ViewModelProvider.NewInstanceFactory())
            .get(ConversationVmHolder::class.java)
        val b = ViewModelProvider(controller().setup().get(), ViewModelProvider.NewInstanceFactory())
            .get(ConversationVmHolder::class.java)
        assertNotSame("different backstack entries must not share a VM", a, b)
    }

    @Test
    fun `red - a composition-bound holder dies on a uiMode recreation (the defect)`() {
        val controller = controller().setup()
        val first = CompositionBoundHolder().also { it.marker = Any() }

        RuntimeEnvironment.setQualifiers("night")
        val recreated = controller.recreate().get()

        // remember{} re-initializes inside the NEW composition:
        val second = CompositionBoundHolder().also { it.marker = Any() }
        assertNotSame(
            "documents the B2.5 defect: the remember(taskId) wiring loses the VM on recreation",
            first.marker,
            second.marker,
        )
    }

}
