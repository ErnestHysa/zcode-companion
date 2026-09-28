package com.zcode.android.domain

import com.zcode.android.domain.outbox.CommandRetryClassifier
import com.zcode.android.domain.outbox.OutboxStateMachine
import com.zcode.android.domain.outbox.OutboxStateMachine.Event
import com.zcode.android.domain.outbox.OutboxStateMachine.IllegalOutboxTransition
import com.zcode.android.domain.outbox.OutboxStateMachine.State
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §43.2-4.4 outbox state machine suite — §17.5 lifecycle + §15.5 unknown semantics +
 * INV-4 (idempotent, replay-safe; NO path duplicates a server-contacted command).
 */
class OutboxStateMachineTest {

    private fun accepted(): OutboxStateMachine =
        OutboxStateMachine.fresh().apply { on(Event.Accept) }

    @Test
    fun `happy path - accept transmit ack`() {
        val sm = accepted()
        assertEquals(State.TRANSMITTED, sm.on(Event.Transmit))
        assertEquals(State.TRANSMITTED, sm.currentState)
        assertEquals(State.ACKED, sm.on(Event.Ack("accepted")))
        assertTrue(sm.currentState == State.ACKED)
    }

    @Test
    fun `duplicate and noop acks are terminal-confirmed`() {
        for (status in listOf("duplicate", "noop")) {
            val sm = accepted()
            sm.on(Event.Transmit)
            assertEquals(State.ACKED, sm.on(Event.Ack(status)))
        }
    }

    @Test
    fun `ack decision mapping per commandAck statuses`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        assertEquals(State.FAILED_TERMINAL, sm.on(Event.Ack("rejected")))

        val sm2 = accepted()
        sm2.on(Event.Transmit)
        assertEquals(State.FAILED_TERMINAL, sm2.on(Event.Ack("failed")))

        val sm3 = accepted()
        sm3.on(Event.Transmit)
        assertEquals(State.FAILED_STALE, sm3.on(Event.Ack("stale")))
    }

    @Test
    fun `pre-contact failure allows retry then transmit`() {
        val sm = accepted()
        assertEquals(State.FAILED_RETRYABLE, sm.on(Event.SendFailedPreContact))
        // pre-transmit auto-retry: back to TRANSMITTED via a fresh transmit event
        assertEquals(State.TRANSMITTED, sm.on(Event.Transmit))
    }

    @Test
    fun `send lost lands UNKNOWN - never assume success (15-5)`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        assertEquals(State.UNKNOWN, sm.on(Event.SendLost))
        assertTrue(sm.serverContacted)
        // authoritative ack may still arrive later
        assertEquals(State.ACKED, sm.on(Event.Ack("accepted")))
    }

    @Test
    fun `INV-4 - cancel is legal only PRE-transmit`() {
        val sm = accepted()
        assertEquals(State.CANCELLED, sm.on(Event.Cancel))

        val sm2 = accepted()
        sm2.on(Event.Transmit)
        assertFailsWith<IllegalOutboxTransition> { sm2.on(Event.Cancel) }
        assertEquals(State.TRANSMITTED, sm2.currentState)
    }

    @Test
    fun `INV-4 - no double transmit and transmit only from ACCEPTED_LOCALLY`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        assertFailsWith<IllegalOutboxTransition> { sm.on(Event.Transmit) }
        assertEquals(State.TRANSMITTED, sm.currentState)
    }

    @Test
    fun `INV-4 - user-gated retry from UNKNOWN reuses the SAME envelope state`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        sm.on(Event.SendLost)
        // user explicitly retries: same commandId (idempotent server dedupe table)
        assertEquals(State.TRANSMITTED, sm.on(Event.UserGatedRetry))
    }

    @Test
    fun `INV-4 - user-gated retry is NOT available from TRANSMITTED (in flight)`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        assertFailsWith<IllegalOutboxTransition> { sm.on(Event.UserGatedRetry) }
    }

    @Test
    fun `INV-4 - terminal states accept no further transmit`() {
        val sm = accepted()
        sm.on(Event.Transmit)
        sm.on(Event.Ack("rejected"))
        assertFailsWith<IllegalOutboxTransition> { sm.on(Event.UserGatedRetry) }
        assertFailsWith<IllegalOutboxTransition> { sm.on(Event.Transmit) }
    }

    @Test
    fun `TTL expiry applies to live states only`() {
        val seeds: List<(OutboxStateMachine) -> Unit> = listOf(
            { sm -> sm.on(Event.Transmit) }, // seeded at ACCEPTED_LOCALLY
            { sm -> sm.on(Event.Transmit); sm.on(Event.SendLost) },
        )
        for (seed in seeds) {
            val sm = accepted()
            seed(sm)
            assertEquals(State.EXPIRED, sm.on(Event.Expire))
        }
        val done = accepted()
        done.on(Event.Transmit)
        done.on(Event.Ack("accepted"))
        assertFailsWith<IllegalOutboxTransition> { done.on(Event.Expire) }
    }

    @Test
    fun `restore from persisted state preserves the machine`() {
        val sm = OutboxStateMachine.restore(State.UNKNOWN)
        assertTrue(sm.serverContacted)
        assertEquals(State.ACKED, sm.on(Event.Ack("accepted")))
    }

    @Test
    fun `accept only from DRAFT`() {
        assertFailsWith<IllegalOutboxTransition> { accepted().on(Event.Accept) }
    }
}

/** §28.6 idempotency classification — verbatim policy rows. */
class CommandRetryClassifierTest {

    @Test
    fun `verified classification rows`() {
        assertEquals(CommandRetryClassifier.RetryClass.SAFE_AGGRESSIVE_RETRY, CommandRetryClassifier.classify("stop"))
        assertEquals(CommandRetryClassifier.RetryClass.SAFE_AGGRESSIVE_RETRY, CommandRetryClassifier.classify("cancelBackgroundWork"))
        assertEquals(CommandRetryClassifier.RetryClass.PRE_TRANSMIT_ONLY, CommandRetryClassifier.classify("sendText"))
        assertEquals(CommandRetryClassifier.RetryClass.PRE_TRANSMIT_ONLY, CommandRetryClassifier.classify("createSession"))
        assertEquals(CommandRetryClassifier.RetryClass.PRE_TRANSMIT_ONLY, CommandRetryClassifier.classify("forkAssistant"))
        assertEquals(CommandRetryClassifier.RetryClass.PRE_TRANSMIT_ONLY, CommandRetryClassifier.classify("editUserQuery"))
        assertEquals(CommandRetryClassifier.RetryClass.PRE_TRANSMIT_ONLY, CommandRetryClassifier.classify("retryTurn"))
        assertEquals(CommandRetryClassifier.RetryClass.USER_GATED_AFTER_CONTACT, CommandRetryClassifier.classify("resolveInteraction"))
        assertEquals(CommandRetryClassifier.RetryClass.USER_GATED_AFTER_CONTACT, CommandRetryClassifier.classify("switchModelConfig"))
        assertEquals(CommandRetryClassifier.RetryClass.USER_GATED_AFTER_CONTACT, CommandRetryClassifier.classify("renameSession"))
    }

    @Test
    fun `unknown command types fail closed to USER_GATED (never auto)`() {
        assertEquals(CommandRetryClassifier.RetryClass.USER_GATED_AFTER_CONTACT, CommandRetryClassifier.classify("someBrandNewCommand"))
    }

    @Test
    fun `canAutoRetry matrix (INV-4)`() {
        // pre-contact failure → all classes auto-retry (pre-transmit rule)
        for (t in listOf("sendText", "resolveInteraction", "stop")) {
            assertTrue(CommandRetryClassifier.canAutoRetry(t, State.FAILED_RETRYABLE), t)
        }
        // outcome lost → only the verified-idempotent family
        assertFalse(CommandRetryClassifier.canAutoRetry("sendText", State.UNKNOWN))
        assertFalse(CommandRetryClassifier.canAutoRetry("resolveInteraction", State.UNKNOWN))
        assertTrue(CommandRetryClassifier.canAutoRetry("stop", State.UNKNOWN))
        // in-flight / acked / terminal → nothing auto
        for (t in listOf("sendText", "stop")) {
            assertFalse(CommandRetryClassifier.canAutoRetry(t, State.TRANSMITTED))
            assertFalse(CommandRetryClassifier.canAutoRetry(t, State.ACKED))
            assertFalse(CommandRetryClassifier.canAutoRetry(t, State.FAILED_TERMINAL))
        }
    }
}
