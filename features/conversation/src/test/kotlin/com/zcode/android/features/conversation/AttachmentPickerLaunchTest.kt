package com.zcode.android.features.conversation

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.FragmentActivity
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * GAP-A8 (vc51 roadmap, sub-phase 1A; live FATALs at WriteComponents.kt:832/:844/:854
 * across 11 rig passes): the composer's three outbound-attachment arms register through
 * `rememberLauncherForActivityResult`, whose registry draws its request codes from
 * `Random.nextInt(2147418112) + 65536` — ALWAYS above the 16-bit request-code ceiling
 * that FragmentActivity enforced before 1.3. So `launch()` throws
 * `IllegalArgumentException: Can only use lower 16 bits for requestCode` at
 * `FragmentActivity.checkForValidRequestCode` before any picker opens and the process
 * dies (MainActivity extends FragmentActivity for BiometricPrompt).
 *
 * The pin reproduces the exact shape: a REAL FragmentActivity host, the SAME three
 * contracts registered through the SAME compose wrapper as WriteComponents.kt:657-668,
 * and one `launch()` per arm — each must reach the system dispatch (a started intent)
 * instead of throwing.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class AttachmentPickerLaunchTest {

    private class Arms {
        lateinit var photo: ActivityResultLauncher<PickVisualMediaRequest>
        lateinit var doc: ActivityResultLauncher<Array<String>>
        lateinit var camera: ActivityResultLauncher<Uri>
    }

    /** Hosts the three arms in a real FragmentActivity (the MainActivity base class). */
    private fun hostedArms(): Pair<FragmentActivity, Arms> {
        val arms = Arms()
        val activity = Robolectric.setupActivity(FragmentActivity::class.java)
        activity.setContent {
            arms.photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {}
            arms.doc = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {}
            arms.camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) {}
        }
        // Pump the main looper so the composition (and its registration side effects) settles.
        val looper = shadowOf(android.os.Looper.getMainLooper())
        looper.idle()
        looper.idle()
        return activity to arms
    }

    private fun startedIntent(activity: FragmentActivity): Intent? =
        shadowOf(activity).nextStartedActivity

    @Test
    fun `A8 - the Photo arm launches without the 16-bit requestCode FATAL`() {
        val (activity, arms) = hostedArms()
        arms.photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("the photo arm must reach the system dispatch", startedIntent(activity))
    }

    @Test
    fun `A8 - the Attach-a-file arm launches without the 16-bit requestCode FATAL`() {
        val (activity, arms) = hostedArms()
        arms.doc.launch(arrayOf("*/*"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("the doc arm must reach the system dispatch", startedIntent(activity))
    }

    @Test
    fun `A8 - the Take-photo arm launches without the 16-bit requestCode FATAL`() {
        val (activity, arms) = hostedArms()
        arms.camera.launch(Uri.parse("content://test/shared/camera.jpg"))
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertNotNull("the camera arm must reach the system dispatch", startedIntent(activity))
    }
}
