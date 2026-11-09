package com.zcode.android.features.pairing

import android.Manifest
import androidx.activity.ComponentActivity
import androidx.camera.core.UseCase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.features.pairing.ui.QrCameraBinding
import com.zcode.android.features.pairing.ui.QrCameraBindingSource
import com.zcode.android.features.pairing.ui.QrScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * F-34-C (vc52 Phase 8, P3, privacy) — the pairing camera kept running after the user left
 * the scanner. CameraX opened the camera at 14:19:42; the user moved to manual entry at
 * ~14:23, paired, reached Tasks at 14:29:52 — and the camera WITH the QR-analysis use case
 * ran until the app was backgrounded at 14:30:49: ~7.5 minutes of invisible camera use (the
 * green privacy indicator lit throughout). Cause: the use cases were bound to the
 * ACTIVITY lifecycle and never unbound when the composable left composition.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QrScannerDisposalTest {

    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    /** Records what is bound — the provider's bound-use-case set, observable. */
    private class FakeBinding : QrCameraBinding {
        val bound = mutableListOf<UseCase>()
        var unbinds = 0
        override fun bind(lifecycleOwner: LifecycleOwner, vararg useCases: UseCase) { bound += useCases }
        override fun unbindAll() { unbinds++; bound.clear() }
    }

    @Before
    fun grantCamera() {
        shadowOf(rule.activity.application).grantPermissions(Manifest.permission.CAMERA)
    }

    @Test
    fun `F-34-C - leaving the scanner unbinds the camera - the bound use cases are empty`() {
        val binding = FakeBinding()
        val source: QrCameraBindingSource = { _, ready -> ready(binding) }
        var showScanner by mutableStateOf(true)
        rule.setContent {
            if (showScanner) QrScanner(lifecycleOwner = rule.activity, onDecoded = {}, cameraBindingSource = source)
        }
        rule.waitForIdle()
        assertEquals("the scanner binds preview + analysis", 2, binding.bound.size)

        rule.runOnIdle { showScanner = false } // the user switched to manual entry
        rule.waitForIdle()
        assertTrue(
            "the camera kept running after the scanner left composition (pass 34: ~7.5 minutes)",
            binding.bound.isEmpty(),
        )
        assertTrue(binding.unbinds >= 2) // the pre-bind reset + the disposal
    }

    @Test
    fun `F-34-C - a provider that resolves AFTER the scanner left never binds the camera`() {
        val binding = FakeBinding()
        var pending: ((QrCameraBinding) -> Unit)? = null
        val lateSource: QrCameraBindingSource = { _, ready -> pending = ready }
        var showScanner by mutableStateOf(true)
        rule.setContent {
            if (showScanner) QrScanner(lifecycleOwner = rule.activity, onDecoded = {}, cameraBindingSource = lateSource)
        }
        rule.waitForIdle()
        rule.runOnIdle { showScanner = false } // left before CameraX resolved
        rule.waitForIdle()
        rule.runOnIdle { pending?.invoke(binding) } // the provider future completes late
        rule.waitForIdle()
        assertTrue("a late provider bound the camera for a scanner that is gone", binding.bound.isEmpty())
    }
}
