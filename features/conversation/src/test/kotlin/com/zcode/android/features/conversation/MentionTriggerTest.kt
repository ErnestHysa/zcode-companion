package com.zcode.android.features.conversation

import com.zcode.android.features.conversation.ui.MentionTrigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * GAP-21-A (vc52 Phase 10): unit pins over the ported desktop trigger parser
 * (BA10.5.2 — `nR = V7e.exec ?? B7e.exec`, the @-widening, the Han+dot invalidation,
 * the ¥/￥→$ normalization). The recorded defect class: the old
 * `substringAfterLast(' ', "")` tokenizer returned the empty MISSING-DEFAULT for any
 * draft without a space, so a FIRST-TOKEN mention computed no token at all.
 */
class MentionTriggerTest {

    @Test
    fun `first-token mention parses with an empty start index`() {
        val t = MentionTrigger.parse("@re")!!
        assertEquals('@', t.trigger)
        assertEquals("re", t.query)
        assertEquals(0, t.start)
    }

    @Test
    fun `mid-text mention parses after whitespace`() {
        val t = MentionTrigger.parse("look at this @read")!!
        assertEquals('@', t.trigger)
        assertEquals("read", t.query)
        assertEquals(13, t.start)
    }

    @Test
    fun `bare at-sign parses with an EMPTY query (desktop listing parity)`() {
        val t = MentionTrigger.parse("@")!!
        assertEquals('@', t.trigger)
        assertEquals("", t.query)
        assertEquals(0, t.start)
    }

    @Test
    fun `han-prefixed mention parses (the desktop V7e widening)`() {
        val t = MentionTrigger.parse("设置@re")!!
        assertEquals('@', t.trigger)
        assertEquals("re", t.query)
        assertEquals(2, t.start)
    }

    @Test
    fun `a word-adjacent at-sign does NOT trigger (a@b is not a mention)`() {
        assertNull("preceded by a word char — neither regex matches", MentionTrigger.parse("email a@b.com"))
    }

    @Test
    fun `trailing space disarms the trigger`() {
        assertNull(MentionTrigger.parse("@re "))
        assertNull(MentionTrigger.parse("x @re "))
    }

    @Test
    fun `han-preceding char with a dotted query does NOT trigger (U7e-H7e invalidation)`() {
        assertNull("Han + dotted path — the desktop suppression", MentionTrigger.parse("看a.b@x"))
    }

    @Test
    fun `han-preceding char with a NON-dotted query still triggers`() {
        val t = MentionTrigger.parse("看@re")!!
        assertEquals('@', t.trigger)
        assertEquals("re", t.query)
        // A word char between the Han char and @ is the a@b rule — no trigger.
        assertNull(MentionTrigger.parse("看read@re"))
    }

    @Test
    fun `fullwidth-punctuation-prefixed mention triggers (V7e fullwidth range)`() {
        val t = MentionTrigger.parse("注：@re")!!
        assertEquals('@', t.trigger)
        assertEquals("re", t.query)
    }

    @Test
    fun `yen triggers normalize to the dollar trigger (desktop skill alias)`() {
        val half = MentionTrigger.parse("¥sk")
        val full = MentionTrigger.parse("￥sk")
        // G65: the #/$ categories are Phase 12 — the parser MODELS the normalization
        // but the composer arms @ only, so both surface as the $ trigger, unarmed.
        assertEquals('$', half?.trigger)
        assertEquals('$', full?.trigger)
    }

    @Test
    fun `hash trigger parses but is not armed today (the Phase-12 boundary)`() {
        val t = MentionTrigger.parse("#ses")
        assertEquals('#', t?.trigger)
    }

    @Test
    fun `empty and whitespace-only drafts never trigger`() {
        assertNull(MentionTrigger.parse(""))
        assertNull(MentionTrigger.parse("   "))
    }

    @Test
    fun `slash drafts never route through the mention parser`() {
        // `/` is the slash palette's own contract; B7e parses it as a trigger but the
        // composer arms @ only — asserted here so a future arming is a DELIBERATE act.
        assertEquals('/', MentionTrigger.parse("/compact")?.trigger)
    }
}
