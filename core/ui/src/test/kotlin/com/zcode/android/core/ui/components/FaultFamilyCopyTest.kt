package com.zcode.android.core.ui.components

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import com.zcode.android.core.common.error.UserMessageKey
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * G79 (vc52 Phase 13): the fault-family copy resolves in BOTH shipped locales —
 * each family phrase names WHAT was refused class-wise and carries the raw
 * code (the %1$s arg) for support; no silent refusals left.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FaultFamilyCopyTest {

    private val keys = listOf(
        UserMessageKey.FAULT_SUBSCRIPTION,
        UserMessageKey.FAULT_ATTACHMENT,
        UserMessageKey.FAULT_CONNECTION,
        UserMessageKey.FAULT_COMMAND,
        UserMessageKey.FAULT_RUNTIME,
        UserMessageKey.FAULT_UNTRUSTED,
        UserMessageKey.FAULT_TERMINAL,
    )

    private fun copy(key: UserMessageKey, locale: java.util.Locale): String {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = android.content.res.Configuration().apply { setLocale(locale) }
        val localized = context.createConfigurationContext(config)
        val res = localized.resources.getIdentifier(resName(key), "string", context.packageName)
        assertTrue("resource for $key missing", res != 0)
        val text = localized.getString(res, "fault.test.code")
        return text
    }

    private fun resName(key: UserMessageKey): String =
        "err_msg_" + key.name.lowercase().replace('_', '_')

    @Test
    fun `every fault family resolves in en`() {
        for (key in keys) {
            val text = copy(key, java.util.Locale.ENGLISH)
            assertTrue("$key names the code arg: $text", text.contains("fault.test.code"))
        }
    }

    @Test
    fun `every fault family resolves in zh-CN`() {
        for (key in keys) {
            val text = copy(key, java.util.Locale.forLanguageTag("zh-CN"))
            assertTrue("$key names the code arg: $text", text.contains("fault.test.code"))
            assertTrue(text.isNotBlank())
        }
    }
}
