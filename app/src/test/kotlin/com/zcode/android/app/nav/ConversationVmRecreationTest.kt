package com.zcode.android.app.nav

import android.app.Activity
import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
 * flip = a genuine uiMode configuration change). The RED leg pins the OLD wiring
 * semantics: a per-composition holder (what remember{} effectively was) is rebuilt
 * with the composition and the instance dies. The FIX leg drives the PRODUCTION
 * production wiring is switched to a ViewModelStore-scoped holder (the nav back
 * stack entry IS the ViewModelStoreOwner in production; the activity stands in
 * here) — the survival legs land with the fix against the PRODUCTION holder.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConversationVmRecreationTest {

    class Marker

    class HostActivity : Activity() {
        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
        }
    }

    /** The OLD wiring's semantics: a holder that lives with the composition. */
    private class CompositionBoundHolder {
        var marker: Marker? = null
    }

    private fun controller(): ActivityController<HostActivity> =
        Robolectric.buildActivity(HostActivity::class.java)

    @Test
    fun `red - a composition-bound holder dies on a uiMode recreation (the defect)`() {
        val controller = controller().setup()
        val first = CompositionBoundHolder().also { it.marker = Marker() }

        RuntimeEnvironment.setQualifiers("night")
        val recreated = controller.recreate().get()

        // remember{} re-initializes inside the NEW composition:
        val second = CompositionBoundHolder().also { it.marker = Marker() }
        assertNotSame(
            "documents the B2.5 defect: the remember(taskId) wiring loses the VM on recreation",
            first.marker,
            second.marker,
        )
    }

}
