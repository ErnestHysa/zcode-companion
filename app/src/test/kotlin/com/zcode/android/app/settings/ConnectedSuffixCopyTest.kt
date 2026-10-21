package com.zcode.android.app.settings

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * U6 (verification pass 2, 2026-10-21, live-found on the rig): the active endpoint's row read
 * "Relay (QR pairing) · v3.14.3 · connected: Connected" — a label AND the phase. The argument
 * is already the phase label (Connected / Reconnecting / Disconnected…), so "connected:" is
 * redundant when connected and contradictory otherwise. The suffix carries the phase alone.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class ConnectedSuffixCopyTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the connected suffix carries the phase label alone`() {
        val en = context.getString(R.string.settings_connected_suffix, "Connected")
        assertEquals(" · Connected", en)
        assertFalse("the redundant label must go", en.contains("connected:"))

        val zh = context.createConfigurationContext(Configuration(context.resources.configuration).apply { setLocale(Locale.SIMPLIFIED_CHINESE) })
            .getString(R.string.settings_connected_suffix, "已连接")
        assertEquals(" · 已连接", zh)
    }
}
