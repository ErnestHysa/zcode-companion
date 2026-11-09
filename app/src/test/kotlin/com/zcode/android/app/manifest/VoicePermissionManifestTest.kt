package com.zcode.android.app.manifest

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * F-34-D (vc52 Phase 8): the MERGED manifest declares RECORD_AUDIO. Pass 34: manifest
 * grep 0, `dumpsys package` 0 — the recognition service rejected every Dictate tap.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class VoicePermissionManifestTest {

    @Test
    fun `F-34-D - the merged manifest declares RECORD_AUDIO for composer dictation`() {
        val app = RuntimeEnvironment.getApplication()
        val requested = app.packageManager
            .getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            .orEmpty()
            .toList()
        assertTrue(
            "RECORD_AUDIO is not declared — Dictate can never work (requested=$requested)",
            Manifest.permission.RECORD_AUDIO in requested,
        )
    }
}
