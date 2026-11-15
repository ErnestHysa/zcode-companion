package com.zcode.android.tools.mockzcode

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pass-15 mock-fidelity debts #15–#16 self-tests — the two pass-14 Q.A2 residuals:
 *  - #15: markCodingPlanResetHistoryRead CLEARS the unread cursor (the desktop's
 *    server behavior) — every later getCodingPlanResetStatus then serves
 *    hasUnreadHistory=false instead of the sticky true; the arrival counter
 *    ([MockZCode.Script.resetHistoryReadCount], declared pass-14-dead) goes live;
 *  - #16: a scripted use MUTATES the entitlement meters on later
 *    getEntitlementSnapshot reads (the used window's limit entry drops by the
 *    scripted delta; the other window's entry untouched) — pass 14 receipted the
 *    "Quota reset" note beside an unmoved Weekly meter and filed the disagreement.
 * Synthetic content only (INV-15/16).
 */
class MockZCodePass15RigSelfTest {

    private fun mock(block: (MockZCode.Script.() -> Unit)? = null): MockZCode {
        val m = MockZCode(passHash = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { 15 }), expectedSid = "d_p15")
        block?.let { m.script.it() }
        return m
    }

    /** The SUCCESS payload a usage-stats reply carried (the response seam). */
    private fun usageStatsPayload(m: MockZCode, name: String, arg: JsonObject): JsonObject? {
        val before = m.promiseResponsesForTest().size
        m.usageStatsPromiseForTest(7, name, com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj(arg))
        val last = m.promiseResponsesForTest().drop(before).lastOrNull() ?: return null
        if (last.second != com.zcode.android.core.protocol.channel.ChannelWire.ResponseType.PromiseSuccess.id) return null
        return (last.third as? com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj)?.json as? JsonObject
    }

    private fun markHistoryRead(m: MockZCode) {
        m.usageStatsPromiseForTest(
            7, "markCodingPlanResetHistoryRead",
            com.zcode.android.core.protocol.bytes.RpcSerialization.RpcValue.Obj(JsonObject(emptyMap())),
        )
    }

    private fun hasUnread(status: JsonObject?): Boolean =
        ((status?.get("hasUnreadHistory") as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false)

    /** Mini entitlement fixture shaped like entitlement-full (units 6=weekly tokens, 5=five-hour time). */
    private fun entitlementFixture(): JsonObject {
        fun limit(unit: Int, type: String, usage: Int) = JsonObject(mapOf(
            "type" to JsonPrimitive(type),
            "unit" to JsonPrimitive(unit),
            "usage" to JsonPrimitive(usage),
            "remaining" to JsonPrimitive(100 - usage),
            "percentage" to JsonPrimitive(usage),
        ))
        return JsonObject(mapOf(
            "quota" to JsonObject(mapOf(
                "limits" to JsonArray(listOf(
                    limit(6, "TOKENS_LIMIT", 51),
                    limit(5, "TIME_LIMIT", 12),
                )),
            )),
        ))
    }

    private fun limitOf(snapshot: JsonObject?, unit: Int): JsonObject? =
        (((snapshot?.get("quota") as? JsonObject)?.get("limits") as? JsonArray)
            ?.map { it.jsonObject }
            ?.firstOrNull { (it["unit"] as? JsonPrimitive)?.content?.toIntOrNull() == unit })

    private fun usageOf(limit: JsonObject?): Int =
        (limit?.get("usage") as? JsonPrimitive)?.content?.toIntOrNull() ?: -1

    // -------------------------------------------------------------- debt #15

    @Test
    fun `mark read clears the unread cursor on every later status read`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = JsonObject(mapOf(
                "availableWeekResets" to JsonArray(emptyList()),
                "hasUnreadHistory" to JsonPrimitive(true),
            ))
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        // Pre-mark: the confirmed progression carries the unread cursor (pass-14 shape).
        assertTrue(hasUnread(usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))),
            "pre-mark: unread cursor set")
        markHistoryRead(m)
        assertEquals(1, m.script.resetHistoryReadCount.get(), "the arrival counter goes live")
        // Post-mark: every later read serves the CLEARED cursor (the desktop's server).
        assertEquals(false, hasUnread(usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))),
            "post-mark: unread cursor cleared")
        assertEquals(false, hasUnread(usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))),
            "sticky clear, not a one-shot")
    }

    @Test
    fun `mark read without a stamped use also clears the base fixture's cursor`() {
        val m = mock {
            codingPlanResetStatus = JsonObject(mapOf(
                "availableWeekResets" to JsonArray(emptyList()),
                "hasUnreadHistory" to JsonPrimitive(true),
            ))
        }
        assertTrue(hasUnread(usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))),
            "pre-mark: base cursor honored")
        markHistoryRead(m)
        assertEquals(false, hasUnread(usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))),
            "post-mark: cleared even without a stamped use")
    }

    // -------------------------------------------------------------- debt #16

    @Test
    fun `scripted WEEK use mutates the weekly meter on later entitlement reads`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            usageEntitlement = entitlementFixture()
        }
        // Pre-use: the fixture serves untouched (weekly 51%).
        assertEquals(51, usageOf(limitOf(usageStatsPayload(m, "getEntitlementSnapshot", JsonObject(emptyMap())), 6)))
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        val after = usageStatsPayload(m, "getEntitlementSnapshot", JsonObject(emptyMap()))
        val weekly = limitOf(after, 6)
        assertEquals(41, usageOf(weekly), "weekly usage dropped by the scripted delta")
        assertEquals(59, (weekly?.get("remaining") as? JsonPrimitive)?.content?.toIntOrNull(),
            "remaining recomputed (usage+remaining=100)")
        assertEquals(41, (weekly?.get("percentage") as? JsonPrimitive)?.content?.toIntOrNull(),
            "percentage recomputed to match usage")
        assertEquals(12, usageOf(limitOf(after, 5)), "the five-hour entry untouched by a WEEK use")
    }

    @Test
    fun `scripted FIVE_HOUR use mutates the five-hour meter instead`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            usageEntitlement = entitlementFixture()
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("FIVE_HOUR"))))
        val after = usageStatsPayload(m, "getEntitlementSnapshot", JsonObject(emptyMap()))
        assertEquals(2, usageOf(limitOf(after, 5)), "five-hour usage dropped by the scripted delta")
        assertEquals(51, usageOf(limitOf(after, 6)), "the weekly entry untouched by a FIVE_HOUR use")
    }

    @Test
    fun `meter mutation clamps at zero instead of going negative`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            usageEntitlement = JsonObject(mapOf(
                "quota" to JsonObject(mapOf("limits" to JsonArray(listOf(JsonObject(mapOf(
                    "type" to JsonPrimitive("TOKENS_LIMIT"),
                    "unit" to JsonPrimitive(6),
                    "usage" to JsonPrimitive(3),
                    "remaining" to JsonPrimitive(97),
                    "percentage" to JsonPrimitive(3),
                )))))),
            ))
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        val weekly = limitOf(usageStatsPayload(m, "getEntitlementSnapshot", JsonObject(emptyMap())), 6)
        assertEquals(0, usageOf(weekly), "clamped at 0")
        assertEquals(100, (weekly?.get("remaining") as? JsonPrimitive)?.content?.toIntOrNull())
    }

    @Test
    fun `no stamped use serves the entitlement untouched`() {
        val m = mock { usageEntitlement = entitlementFixture() }
        val snapshot = usageStatsPayload(m, "getEntitlementSnapshot", JsonObject(emptyMap()))
        assertEquals(51, usageOf(limitOf(snapshot, 6)))
        assertEquals(12, usageOf(limitOf(snapshot, 5)))
    }

    // ------------------------------------------- debt #10's queue variant (pass 15)

    /** The base with an INTACT week opportunity + NO history (the pre-consume shape). */
    private val preConsumeBase = JsonObject(mapOf(
        "availableWeekResets" to JsonArray(listOf(JsonObject(mapOf("expireAt" to JsonPrimitive(System.currentTimeMillis() + 7 * 24 * 3_600_000))))),
        "availableFiveHourResets" to JsonArray(emptyList()),
        "hasUnreadHistory" to JsonPrimitive(false),
    ))

    @Test
    fun `queue pre-consume entry delays the confirmation to the fallback path`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = preConsumeBase
            resetQueuePreOnUse = true
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        // Read #1: the queued PRE-CONSUME entry — the week opportunity is STILL THERE
        // (the pass-14 fold must NOT confirm it in place) and no usedAt history.
        val first = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(1, (first!!["availableWeekResets"] as JsonArray).size,
            "read #1: the pre-consume shape survives the queue")
        assertTrue(first["latestWeekResetHistory"] == null, "read #1: no usedAt history yet")
        // Read #2: the queue is empty → the fallback's confirmed fold lands COMPLETED.
        val second = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(0, (second!!["availableWeekResets"] as JsonArray).size,
            "read #2: the fallback confirms (opportunity consumed)")
        val usedAt = ((second["latestWeekResetHistory"] as? JsonObject)?.get("usedAt") as? JsonPrimitive)?.content?.toLongOrNull()
        assertTrue(usedAt != null && usedAt > 0, "read #2: the usedAt history lands")
    }

    @Test
    fun `queue entry honors the unread clear too`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = JsonObject(preConsumeBase + mapOf("hasUnreadHistory" to JsonPrimitive(true)))
            resetQueuePreOnUse = true
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        markHistoryRead(m)
        // The queued entry serves with the #15 unread fold applied.
        val served = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(false, hasUnread(served), "the queued shape still honors the mark")
    }

    @Test
    fun `use without the queue arm enqueues nothing (pass-14 shape preserved)`() {
        val m = mock {
            resetUse = JsonObject(mapOf("used" to JsonPrimitive(true)))
            codingPlanResetStatus = preConsumeBase
        }
        usageStatsPayload(m, "useCodingPlanReset", JsonObject(mapOf("resetType" to JsonPrimitive("WEEK"))))
        // The FIRST read after the use is already confirmed (no queued pre-consume).
        val served = usageStatsPayload(m, "getCodingPlanResetStatus", JsonObject(emptyMap()))
        assertEquals(0, (served!!["availableWeekResets"] as JsonArray).size,
            "no arm: the first post-use read confirms immediately (Q.A2 shape)")
    }
}
