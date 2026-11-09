package com.zcode.android.features.conversation.ui

/**
 * GAP-21-A (vc52 Phase 10): the composer's mention trigger parser — the desktop
 * renderer's mention parser ported VERBATIM (the BA10.5.2 fix spec, zcode 3.14.5
 * asar `nR(textBeforeCursor) = V7e.exec(e) ?? B7e.exec(e)`):
 *
 *   B7e = /(^|\s)([/@$#¥￥])([^\s/@$#¥￥]*)$/                       — line-start or
 *     whitespace-preceded trigger; the body may not contain whitespace or a
 *     trigger character.
 *   V7e = /(^|[\s\p{Script=Han}\u3000-\u303f\uff00-\uffef])(@)([^\s/@$#¥￥]*)$/u
 *                                                                   — the @-widening:
 *     also fires after Han script and CJK punctuation/fullwidth characters.
 *
 * The decisive desktop face this ports: the `(^|...)` alternatives fire the search on
 * a FIRST-TOKEN `@` — with an EMPTY query allowed (the host lists up to 1000
 * candidates) — exactly the face the phone never rendered (11 wire-proofed live
 * repros: `substringAfterLast(' ', "")` returned the empty MISSING-DEFAULT for any
 * draft without a space, so a draft that STARTS with the mention computed no token).
 *
 * Invalidation (the desktop's U7e/H7e): when the character PRECEDING the `@` is Han
 * script AND the query is dotted (`\S\.\S`), the trigger is suppressed — Han text
 * quoting dotted file paths is not a mention.
 * `¥` / `￥` normalize to `$` (the desktop's skill trigger).
 *
 * G65 note: the trigger SET is modeled here so categories plug in, but only `@` is
 * ARMED by the composer today — the `#`/`$` panels and the `/` commands are the
 * Phase-12 category expansion (`/` keeps the slash palette's own contract and never
 * routes through this parser).
 */
internal object MentionTrigger {

    /** One parsed trigger: the (normalized) trigger char, the trailing query, and
     *  the trigger's index in the scanned text (for in-place pick replacement). */
    data class Trigger(val trigger: Char, val query: String, val start: Int)

    /** Whitespace/line-start trigger (desktop B7e). */
    private val GENERAL = Regex("(^|\\s)([/@$#¥￥])([^\\s/@$#¥￥]*)$")
    /** The @-widening: also after Han script / CJK punctuation / fullwidth forms (desktop V7e). */
    private val WIDENED_AT = Regex("(^|[\\s\\p{Script=Han}\\u3000-\\u303f\\uff00-\\uffef])(@)([^\\s/@$#¥￥]*)$")

    private val HAN = Regex("\\p{Script=Han}")
    /** Dotted query in Han context (desktop H7e). */
    private val DOTTED = Regex("\\S\\.\\S")

    fun parse(text: String): Trigger? {
        if (text.isEmpty()) return null
        // The @-widening runs FIRST (the desktop's `V7e.exec(e) ?? B7e.exec(e)`).
        WIDENED_AT.find(text)?.let { m ->
            val query = m.groupValues[3]
            val start = text.length - query.length - 1
            val prev = text.getOrNull(start - 1)
            if (prev != null && HAN.matches(prev.toString()) && DOTTED.containsMatchIn(query)) {
                return null // Han text quoting a dotted path — the desktop's invalidation
            }
            return Trigger('@', query, start)
        }
        GENERAL.find(text)?.let { m ->
            val raw = m.groupValues[2][0]
            val trigger = if (raw == '¥' || raw == '￥') '$' else raw
            val query = m.groupValues[3]
            return Trigger(trigger, query, text.length - query.length - 1)
        }
        return null
    }
}
