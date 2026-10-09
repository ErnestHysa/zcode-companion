package com.zcode.android.features.pairing

import android.Manifest
import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.features.pairing.ui.QrScanner
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * FIELD regression (2026-09-30, camera dead after first-time grant): QrScanner used to
 * request the permission AND bind CameraX unconditionally in the same composition — the
 * bind ran without the grant and the preview stayed dead until a full app restart. The
 * host now owns the permission flow and composes QrScanner only once CAMERA is granted;
 * QrScanner itself refuses to render the preview without the grant (belt-and-braces:
 * no dead-preview bind attempt can ever happen again).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class QrScannerPermissionGateTest {

    @get:Rule
    val rule = createComposeRule()

    private val owner = object : LifecycleOwner {
        private val registry = LifecycleRegistry(this).apply { handleLifecycleEvent(Lifecycle.Event.ON_RESUME) }
        override val lifecycle: Lifecycle get() = registry
    }

    @Test
    fun `without camera permission the scanner composes nothing`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).denyPermissions(Manifest.permission.CAMERA)
        rule.setContent { QrScanner(lifecycleOwner = owner, onDecoded = {}) }
        rule.onAllNodesWithTag("qr-preview").assertCountEquals(0)
    }

    @Test
    fun `with camera permission the preview composes`() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(app).grantPermissions(Manifest.permission.CAMERA)
        rule.setContent { QrScanner(lifecycleOwner = owner, onDecoded = {}) }
        rule.onAllNodesWithTag("qr-preview").assertCountEquals(1)
    }
}
