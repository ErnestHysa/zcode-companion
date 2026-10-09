package com.zcode.android.core.common.error

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** §33 model sanity: every category is constructible with its taxonomy defaults. */
class AppErrorTest {

    @Test
    fun `all 18 categories exist and carry taxonomy fields`() {
        assertEquals(18, ErrorCategory.entries.size)
        for (c in ErrorCategory.entries) {
            val err = AppError(
                category = c,
                userMessage = "user copy for $c",
                developerDiagnostic = "diag: $c",
                retryable = AppError.Retryable.NO,
                recoverable = true,
            )
            assertEquals(c, err.category)
            assertTrue(err.userMessage.isNotEmpty())
            assertTrue(err.developerDiagnostic.isNotEmpty())
        }
    }

    @Test
    fun `correlation carries ids only`() {
        val err = AppError(
            category = ErrorCategory.TRANSPORT,
            userMessage = "Connection lost — reconnecting…",
            developerDiagnostic = "ws close code=1006",
            retryable = AppError.Retryable.AUTO,
            recoverable = true,
            autoRecovery = RecoveryAction.RECONNECT,
            correlation = Correlation(endpointId = "ep1", connectionId = "c1", sessionId = "s1", commandId = "cmd1"),
        )
        assertEquals(RecoveryAction.RECONNECT, err.autoRecovery)
        assertEquals("c1", err.correlation.connectionId)
    }
}
