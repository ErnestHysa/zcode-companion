package com.zcode.android.core.transport

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import com.zcode.android.core.common.error.UserMessageKey
import org.junit.Test

/**
 * G79 (vc52 Phase 13): the desktop's `fault.*` vocabulary — 54 real wire codes
 * clustered in the planes the phone renders raw; every v4 `fault.*` reached the
 * phone as a `refused: fault.<code>: …` string with NO per-family wording and
 * NO recovery hint (N-BG-2's silent refusals were this path). The vocabulary:
 *
 *  - code extraction from the refusal detail (the wire's own spelling);
 *  - the KNOWN-code table covering the phone's receive families
 *    (subscription.*, attachment.*, connection.*, command.*, runtime.hookBlocked,
 *    bashOutput.connectionUntrusted, conversation.rowsRangeConnectionUntrusted,
 *    terminal.*) — a table pin per code;
 *  - UNKNOWN codes stay unknown (never guessed into a family — INV-8).
 */
class FaultVocabularyTest {

    // ------------------------------------------------- code extraction

    @Test
    fun `codeFromRefusal extracts from the logged refusal shape`() {
        assertEquals(
            "subscription.contentRejected",
            FaultVocabulary.codeFromRefusal("fault.subscription.contentRejected: the frame was refused"),
        )
        assertEquals(
            "command.capabilityUnsupported",
            FaultVocabulary.codeFromRefusal("fault.command.capabilityUnsupported"),
        )
    }

    @Test
    fun `codeFromRefusal tolerates prefixes and suffixes`() {
        assertEquals("connection.closed", FaultVocabulary.codeFromRefusal("refused: fault.connection.closed"))
        assertEquals("connection.closed", FaultVocabulary.codeFromRefusal("refused: fault.connection.closed: details"))
        assertNull(FaultVocabulary.codeFromRefusal("no fault code in here"))
        assertNull(FaultVocabulary.codeFromRefusal(""))
        assertNull(FaultVocabulary.codeFromRefusal(null))
    }

    // ------------------------------------------------- the family table (per code)

    @Test
    fun `subscription family covers the eight wire codes`() {
        for (code in listOf(
            "subscription.contentRejected",
            "subscription.initialFrameStagingOverflow",
            "subscription.notOwned",
            "subscription.recoveryFailed",
            "subscription.recoveryFrameTimedOut",
            "subscription.resyncGenerationMismatch",
            "subscription.runtimeRestarted",
            "subscription.transportReplaced",
        )) {
            assertEquals(FaultVocabulary.Family.SUBSCRIPTION, FaultVocabulary.familyOf(code), code)
            assertTrue(FaultVocabulary.isKnown(code), code)
        }
    }

    @Test
    fun `attachment family covers the audited receive codes`() {
        for (code in listOf(
            "attachment.previewNotMedia",
            "attachment.previewTooLarge",
            "attachment.shareReadNotAuthorized",
            "attachment.shareStatNotFound",
            "attachment.checksumUnavailable",
        )) {
            assertEquals(FaultVocabulary.Family.ATTACHMENT, FaultVocabulary.familyOf(code), code)
        }
    }

    @Test
    fun `connection family covers the audited receive codes`() {
        for (code in listOf(
            "connection.clientChanged",
            "connection.closed",
            "connection.flowControlForbidden",
            "connection.handshakeRequired",
            "connection.helloRequired",
            "connection.invalidConnectionId",
        )) {
            assertEquals(FaultVocabulary.Family.CONNECTION, FaultVocabulary.familyOf(code), code)
        }
    }

    @Test
    fun `command family covers the audited receive codes incl the rejected families`() {
        for (code in listOf(
            "command.capabilityUnsupported",
            "command.inputDiscardedOnRestart",
            "command.queryForeignWorkspace",
            "command.queryUnavailable",
            "command.clientMismatch",
            "command.backgroundWorkCancelRejected.busy",
            "command.savedWorkflowStartRejected.missing",
            "command.workflowRunResumeRejected.busy",
        )) {
            assertEquals(FaultVocabulary.Family.COMMAND, FaultVocabulary.familyOf(code), code)
        }
    }

    @Test
    fun `runtime untrusted and terminal planes are keyed`() {
        assertEquals(
            FaultVocabulary.Family.RUNTIME,
            FaultVocabulary.familyOf("runtime.hookBlocked"),
        )
        assertEquals(
            FaultVocabulary.Family.UNTRUSTED,
            FaultVocabulary.familyOf("bashOutput.connectionUntrusted"),
        )
        assertEquals(
            FaultVocabulary.Family.UNTRUSTED,
            FaultVocabulary.familyOf("conversation.rowsRangeConnectionUntrusted"),
        )
        assertTrue(FaultVocabulary.isKnown("terminal.connectionLost"))
    }

    @Test
    fun `subscribe-dot-unknown is keyed (the audit's subscription sibling)`() {
        assertEquals(FaultVocabulary.Family.SUBSCRIPTION, FaultVocabulary.familyOf("subscribe.unknown"))
    }

    @Test
    fun `an unknown fault stays UNKNOWN (never guessed into a family)`() {
        assertNull(FaultVocabulary.familyOf("fault.unknown.madeUpCode"))
        assertNull(FaultVocabulary.familyOf("mock.unscripted"))
        assertFalse(FaultVocabulary.isKnown("definitely.not.a.code"))
    }

    // ------------------------------------------------- the ErrorMapping arm

    @Test
    fun `faultRefusal builds a keyed error with the RAW code as the arg (support keeps it)`() {
        val error = ErrorMapping.faultRefusal("fault.attachment.previewTooLarge")
        assertEquals(UserMessageKey.FAULT_ATTACHMENT, error.messageKey)
        assertEquals(listOf("attachment.previewTooLarge"), error.messageArgs)
    }

    @Test
    fun `faultRefusal on an unknown code keeps the generic keyed face with the detail`() {
        val error = ErrorMapping.faultRefusal("refused: fault.mock.unscripted:getEntitlementSnapshot")
        assertEquals(UserMessageKey.NOT_ALLOWED, error.messageKey)
        assertTrue(error.developerDiagnostic.contains("fault.mock.unscripted"))
    }

    @Test
    fun `faultRefusal on a non-fault detail stays generic`() {
        val error = ErrorMapping.faultRefusal("channel unavailable")
        assertEquals(UserMessageKey.NOT_ALLOWED, error.messageKey)
    }
}
