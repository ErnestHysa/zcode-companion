package com.zcode.android.app

import android.app.Activity
import android.app.ActivityManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowActivity

/**
 * C6-taskdesc (gaps-and-polish Phase 1D, P0 one-liner): the OS recents card showed
 * a generic label — no setTaskDescription anywhere. The recents card now carries
 * the open task's title; a blank title is a no-op. The platform jar (API 36+)
 * removed the GETTER, so the pin captures the applied description through a
 * recording shadow of Activity.
 */
@Implements(Activity::class)
class RecordingTaskDescriptionShadow : ShadowActivity() {
    @Implementation
    fun setTaskDescription(description: ActivityManager.TaskDescription) {
        last = description
    }

    companion object {
        @JvmStatic
        var last: ActivityManager.TaskDescription? = null
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], shadows = [RecordingTaskDescriptionShadow::class])
class RecentsTaskLabelTest {

    @Test
    fun `the recents card shows the task name`() {
        val activity = Robolectric.setupActivity(Activity::class.java)
        // The platform init applies its own (label-less) description — reset AFTER
        // setup so the shadow only records OUR call.
        RecordingTaskDescriptionShadow.last = null
        activity.updateRecentsTaskLabel("Fix auth token refresh")
        assertEquals("Fix auth token refresh", RecordingTaskDescriptionShadow.last?.label)
    }

    @Test
    fun `a blank title is a no-op`() {
        val activity = Robolectric.setupActivity(Activity::class.java)
        RecordingTaskDescriptionShadow.last = null
        activity.updateRecentsTaskLabel("   ")
        assertNull("never apply a blank description", RecordingTaskDescriptionShadow.last)
    }
}
