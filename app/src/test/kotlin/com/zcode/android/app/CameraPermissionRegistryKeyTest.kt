package com.zcode.android.app

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-PERM (field 2026-10-25): the camera-permission launcher's registry key is
 * validated against the 16-bit request-code contract by the legacy
 * requestPermissions path (FragmentActivity.checkForValidRequestCode). A key
 * whose hash overflows crashes every fresh install at the camera prompt. The
 * key must stay a string whose Java hash code fits 1..0xFFFF.
 */
class CameraPermissionRegistryKeyTest {

    @Test
    fun `the camera permission registry key fits the 16-bit request-code contract`() {
        val hash = CAMERA_PERMISSION_REGISTRY_KEY.hashCode()
        assertTrue(
            "registry key \"$CAMERA_PERMISSION_REGISTRY_KEY\" hashes to $hash — outside 1..0xFFFF, " +
                "the camera prompt would crash again",
            hash in 1..0xFFFF,
        )
    }
}
