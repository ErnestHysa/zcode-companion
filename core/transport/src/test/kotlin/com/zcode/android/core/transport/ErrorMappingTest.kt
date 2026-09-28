package com.zcode.android.core.transport

import com.zcode.android.core.common.error.AppError
import com.zcode.android.core.common.error.ErrorCategory
import com.zcode.android.core.common.error.RecoveryAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 2.4 acceptance — close-code / relay-error mapping table tests (§33) + capability
 * negotiation gates. "Every category has ≥1 test": the final test walks all 18
 * categories with representative transport-layer sources.
 */
class ErrorMappingTest {

    // ---------------------------------------------------- close codes

    @Test
    fun `close-code mapping table matches section 33`() {
        assertEquals(ErrorCategory.TRANSPORT, ErrorMapping.closeCode(4000).category)
        assertEquals(ErrorCategory.TRANSPORT, ErrorMapping.closeCode(4004).category)
        assertEquals(ErrorCategory.SESSION, ErrorMapping.closeCode(4009).category) // takeover UX
        assertEquals(ErrorCategory.TRANSPORT, ErrorMapping.closeCode(4010).category) // desktop-gone
        assertEquals(ErrorCategory.AUTH, ErrorMapping.closeCode(4011).category)
        assertEquals(ErrorCategory.SESSION, ErrorMapping.closeCode(4012).category) // workspace closed
        assertEquals(ErrorCategory.AUTH, ErrorMapping.closeCode(4013).category)
        // unclassified closes stay transport-class (never invented categories)
        assertEquals(ErrorCategory.TRANSPORT, ErrorMapping.closeCode(4999).category)
    }

    @Test
    fun `auth-class closes route to re-pair not reconnect`() {
        assertEquals(RecoveryAction.RE_PAIR, ErrorMapping.closeCode(4011).autoRecovery)
        assertEquals(RecoveryAction.RE_PAIR, ErrorMapping.closeCode(4013).autoRecovery)
        assertEquals(AppError.Retryable.NO, ErrorMapping.closeCode(4011).retryable)
    }

    @Test
    fun `desktop-gone close carries wake-guidance copy`() {
        val err = ErrorMapping.closeCode(4010)
        assertTrue(err.userMessage.contains("Desktop"))
        assertEquals(RecoveryAction.RECONNECT, err.autoRecovery)
    }

    // ---------------------------------------------------- relay error frames

    @Test
    fun `relay error frame mapping table matches observed codes`() {
        assertEquals(ErrorCategory.AUTH, ErrorMapping.relayError("AUTH_FAILED", null).category)
        assertEquals(ErrorCategory.SESSION, ErrorMapping.relayError("KICKED", null).category)
        assertEquals(ErrorCategory.SERVER, ErrorMapping.relayError("INTERNAL", null).category)
        assertEquals(ErrorCategory.INTERNAL, ErrorMapping.relayError("WRONG_PARAM", null).category)
        // unknown relay codes: generic copy, reported with correlation — never "unsupported"
        val unknown = ErrorMapping.relayError("SOMETHING_NEW", null)
        assertEquals(ErrorCategory.INTERNAL, unknown.category)
        assertNotEquals(ErrorCategory.UNSUPPORTED_FEATURE, unknown.category)
    }

    @Test
    fun `unknown vs unsupported vs incompatible are never conflated (33 final rule)`() {
        val unknown = ErrorMapping.relayError("SOMETHING_NEW", null)
        val unsupported = ErrorMapping.unsupportedFeature("terminal")
        val incompatible = ErrorMapping.versionIncompatible("wireVersion=4")
        assertEquals(ErrorCategory.INTERNAL, unknown.category)
        assertEquals(ErrorCategory.UNSUPPORTED_FEATURE, unsupported.category)
        assertEquals(ErrorCategory.VERSION_INCOMPAT, incompatible.category)
        assertEquals(RecoveryAction.FEATURE_DISABLE, unsupported.autoRecovery)
        assertEquals(RecoveryAction.COMPAT_SCREEN, incompatible.autoRecovery)
    }

    // ---------------------------------------------------- capability negotiation gates

    @Test
    fun `capability snapshot reflects hello and defaults optional bits false`() {
        // The HostCapabilities decoder is exercised end-to-end by the live hello path
        // (RelayTransportTest happy path asserts localTerminal); here the ===true gate:
        val caps = com.zcode.android.core.protocol.v4.HostCapabilities(
            nativeDialogs = true, localTerminal = true, binaryFrames = false, compression = "none",
        )
        // absent optional bits === false — consumers must test === true (verified schema rule)
        assertEquals(false, caps.workflowRunDeltas)
        assertEquals(false, caps.workspaceHookReview)
    }

    // ---------------------------------------------------- full-category walk

    @Test
    fun `every one of the 18 categories has a representative mapping with taxonomy fields`() {
        data class Row(val category: ErrorCategory, val err: AppError)

        val rows = listOf(
            Row(ErrorCategory.AUTH, ErrorMapping.relayError("AUTH_FAILED", null)),
            Row(ErrorCategory.AUTHZ, ErrorMapping.authzRefusal("channel refused for role")),
            Row(ErrorCategory.TRANSPORT, ErrorMapping.closeCode(4010)),
            Row(ErrorCategory.NETWORK, ErrorMapping.networkUnreachable("getaddrinfo ENOTFOUND (observed class)")),
            Row(ErrorCategory.PROTOCOL, ErrorMapping.protocolFault("rpc-frame crc32 mismatch")),
            Row(ErrorCategory.SERIALIZATION, ErrorMapping.serializationFault("unknown relay frame type (counted)")),
            Row(ErrorCategory.SERVER, ErrorMapping.relayError("INTERNAL", null)),
            Row(ErrorCategory.SESSION, ErrorMapping.sessionUnavailable()),
            Row(ErrorCategory.TASK, ErrorMapping.taskRejected("fault.command.stale")),
            Row(ErrorCategory.PERMISSION, ErrorMapping.permissionStale()),
            Row(ErrorCategory.STORAGE, ErrorMapping.storageQuarantine()),
            Row(ErrorCategory.ANDROID, ErrorMapping.androidPermission("camera denied")),
            Row(ErrorCategory.NOTIFICATION, ErrorMapping.notificationBlocked()),
            Row(ErrorCategory.VERSION_INCOMPAT, ErrorMapping.versionIncompatible("hello protocolVersion=4")),
            Row(ErrorCategory.UNSUPPORTED_FEATURE, ErrorMapping.unsupportedFeature("workflowRunDeltas")),
            Row(ErrorCategory.TIMEOUT, ErrorMapping.timeout("rowsRange", 10_000)),
            Row(ErrorCategory.CANCELLED, ErrorMapping.cancelled()),
            Row(ErrorCategory.INTERNAL, ErrorMapping.internal("unexpected state")),
        )
        assertEquals(18, ErrorCategory.entries.size, "taxonomy changed — update this walk")
        assertEquals(ErrorCategory.entries.toSet(), rows.map { it.category }.toSet(), "every category covered exactly")
        for (row in rows) {
            assertEquals(row.category, row.err.category)
            // §33 rules: diagnostics present; INTERNAL-class diagnostics never user-facing
            assertTrue(row.err.developerDiagnostic.isNotEmpty(), row.category.name)
            if (row.category != ErrorCategory.UNSUPPORTED_FEATURE && row.category != ErrorCategory.CANCELLED) {
                assertTrue(row.err.userMessage.isNotEmpty(), row.category.name)
            }
        }
    }
}
