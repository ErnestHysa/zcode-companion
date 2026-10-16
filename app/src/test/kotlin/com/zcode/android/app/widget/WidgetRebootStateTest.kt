package com.zcode.android.app.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.domain.model.NotificationRecord
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * H3.2c (gaps-and-polish Phase 2F, P1): `NotificationBrokerSource.current` is a static
 * that defaults to EMPTY until `PendingApprovalsWidgetUpdater.start()` runs in-process —
 * after a reboot/process death a system-driven widget update rendered "No pending
 * approvals" (reset-to-zero is the hostile direction) until the app next ran.
 * Contract: the last known count is persisted on every change and rendered until
 * fresh broker data is attached.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WidgetRebootStateTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After
    fun reset() {
        NotificationBrokerSource.detach()
    }

    @Test
    fun `a fresh process renders the persisted count until the broker attaches`() {
        WidgetState.persist(context, 3)
        NotificationBrokerSource.detach() // simulates the post-reboot static default
        assertEquals("persisted count must survive the reboot seam", 3, WidgetState.resolve(context).count)
    }

    @Test
    fun `an attached broker is authoritative and refreshes the persisted count`() {
        WidgetState.persist(context, 3)
        NotificationBrokerSource.attach { emptyList<NotificationRecord>() }
        assertEquals("fresh data wins over the persisted count", 0, WidgetState.resolve(context).count)
        assertEquals("the persisted count follows fresh data", 0, WidgetState.persistedCount(context))
    }
}
