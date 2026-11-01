package com.zcode.android.app.widget

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * PINS-BACKLOG anchor (vc51 Phase 5): the glance widget's PICKER PRESENCE contract on
 * the merged manifest. Pass 9 verified the receiver registered in `dumpsys appwidget`
 * LIVE and the Phase-5 exit gate binds it on the rig; the AppWidgetManager shadow
 * cannot ingest manifest providers, so the unit surface pins what the launcher's
 * widget tray enumerates FROM: the receiver resolves, it answers the APPWIDGET_UPDATE
 * broadcast (the scan AppWidgetService runs over receivers), and it carries the
 * `android.appwidget.provider` meta-data pointing at the widget-info xml the tray
 * renders. A receiver registration, filter, or meta-data regression flips this.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class WidgetProviderListingTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun componentName() =
        ComponentName(context, PendingApprovalsWidgetReceiver::class.java)

    private fun receiverInfo() = context.packageManager.getReceiverInfo(
        componentName(),
        PackageManager.GET_META_DATA,
    )

    @Test
    fun `the widget receiver resolves on the merged manifest`() {
        assertEquals(
            "the receiver must keep its package-local registration",
            "com.zcode.android.app.widget.PendingApprovalsWidgetReceiver",
            receiverInfo().name,
        )
    }

    @Test
    fun `the receiver answers APPWIDGET_UPDATE — the scan the tray's enumeration runs`() {
        val matches = context.packageManager.queryBroadcastReceivers(
            Intent("android.appwidget.action.APPWIDGET_UPDATE"),
            0,
        )
        assertTrue(
            "PendingApprovalsWidgetReceiver must answer the APPWIDGET_UPDATE broadcast",
            matches.any { it.activityInfo?.name?.endsWith("PendingApprovalsWidgetReceiver") == true },
        )
    }

    @Test
    fun `the provider meta-data points at the widget-info xml the tray renders`() {
        val info = receiverInfo()
        assertNotNull("the android.appwidget.provider meta-data must exist", info.metaData)
        // android:resource values ride the bundle as resource ids (getString is null).
        val resId = info.metaData.getInt("android.appwidget.provider")
        assertTrue("the meta-data must reference a real xml resource (id=$resId)", resId != 0)
        assertEquals(
            "the resolved resource must be the widget-info xml",
            "pending_approvals_widget_info",
            context.resources.getResourceEntryName(resId),
        )
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("repo root not found")
        assertTrue(
            "res/xml/pending_approvals_widget_info.xml must exist",
            File(root, "app/src/main/res/xml/pending_approvals_widget_info.xml").isFile,
        )
    }
}
