package com.zcode.android.core.ui.components

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.UserMessageKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.Locale

/**
 * C5-i18n vocabulary closure: every §33 [UserMessageKey] the mapping table can emit
 * resolves to real copy in BOTH shipped locales — an unmapped or untranslated key would
 * silently fall back to category copy. F-37-A (vc52) adds CLEARTEXT_NOT_ALLOWED: the
 * add-endpoint dialog must say "Plain ws:// isn't allowed" instead of the misdiagnosed
 * Local-Network copy or the generic "Network unavailable".
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [30])
class UserMessageKeyCopyTest {

    private fun localized(tag: String): Context {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val config = Configuration(base.resources.configuration).apply { setLocale(Locale.forLanguageTag(tag)) }
        return base.createConfigurationContext(config)
    }

    @Test
    fun `every message key resolves to non-blank copy in en and zh-CN`() {
        for (tag in listOf("en", "zh-CN")) {
            val context = localized(tag)
            for (key in UserMessageKey.entries) {
                val res = userMessageRes(key)
                assertNotEquals("$key has no resource", 0, res)
                val text = context.getString(res)
                if (key != UserMessageKey.NONE) assertTrue("$key is blank in $tag", text.isNotBlank())
            }
        }
    }

    @Test
    fun `F-37-A - the cleartext refusal renders its own honest copy, not the category fallback`() {
        val error = AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "fallback",
            developerDiagnostic = "pin",
            retryable = AppError.Retryable.NO,
            recoverable = true,
            messageKey = UserMessageKey.CLEARTEXT_NOT_ALLOWED,
        )
        assertEquals(
            "Plain ws:// isn't allowed — use wss:// or a tunnel URL",
            errorMessage(localized("en"), error),
        )
        val zh = errorMessage(localized("zh-CN"), error)
        assertTrue("zh-CN copy must be translated: $zh", zh.contains("wss://") && !zh.startsWith("Plain"))
    }
}
