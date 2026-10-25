package com.zcode.android.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-PERM (field 2026-10-25): the camera permission is requested directly with
 * CAMERA_PERMISSION_REQUEST_CODE through ActivityCompat.requestPermissions, which
 * FragmentActivity validates against the 16-bit request-code contract. The code
 * must stay inside it (the ActivityResult registry's random codes overflow, which
 * crashed every fresh install at the camera prompt).
 */
class CameraPermissionRequestCodeTest {

    @Test
    fun `the camera permission request code fits the 16-bit contract`() {
        assertTrue(
            "request code $CAMERA_PERMISSION_REQUEST_CODE is outside 1..0xFFFF — " +
                "the camera prompt would crash again",
            CAMERA_PERMISSION_REQUEST_CODE in 1..0xFFFF,
        )
    }
}
