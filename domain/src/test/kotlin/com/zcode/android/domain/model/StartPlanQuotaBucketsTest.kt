package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * G11 (vc51) — the conversation quota banner's bucket logic (startPlanQuota
 * Buckets.ts verbatim): active-bucket windows, the REAL remaining ratio
 * (never `available`, unknown never 0), the every-bucket exhaustion rule,
 * the once-per-period reminder key, and the low→exhausted verdict ladder.
 */
class StartPlanQuotaBucketsTest {

    private val NOW = 1_700_000_000_000L

    private fun bucket(
        remaining: Double? = 50.0,
        number: Double? = 100.0,
        percentage: Double? = null,
        meter: String? = "model_usage",
        bucketId: String? = "b-1",
        periodStart: Long? = NOW - 1_000,
        periodEnd: Long? = NOW + 1_000,
        model: String = "glm-5.1",
    ) = buildJsonObject {
        put("type", JsonPrimitive("TOKENS_LIMIT"))
        meter?.let { put("meter", JsonPrimitive(it)) }
        bucketId?.let { put("bucketId", JsonPrimitive(it)) }
        periodStart?.let { put("periodStart", JsonPrimitive(it)) }
        periodEnd?.let { put("periodEnd", JsonPrimitive(it)) }
        remaining?.let { put("remaining", JsonPrimitive(it)) }
        number?.let { put("number", JsonPrimitive(it)) }
        percentage?.let { put("percentage", JsonPrimitive(it)) }
        put("usageDetails", kotlinx.serialization.json.buildJsonArray {
            add(buildJsonObject {
                put("modelCode", kotlinx.serialization.json.JsonPrimitive(model))
                put("usage", kotlinx.serialization.json.JsonPrimitive(1.0))
            })
        })
    }

    private fun snapshot(vararg limits: kotlinx.serialization.json.JsonObject) = UsageEntitlement.decode(
        buildJsonObject {
            put("generatedAt", JsonPrimitive(NOW))
            put("serverTime", JsonPrimitive(NOW))
            put("authenticated", JsonPrimitive(true))
            put("quota", buildJsonObject {
                put("level", JsonPrimitive("pro"))
                put("limits", kotlinx.serialization.json.buildJsonArray { limits.forEach { add(it) } })
            })
        },
    )

    @Test
    fun `active buckets are the in-window model_usage ones`() {
        val snap = snapshot(
            bucket(), // in window
            bucket(periodStart = NOW + 10), // future period — inactive
            bucket(periodEnd = NOW - 10), // past period — inactive
            bucket(meter = "mcp_usage"), // different meter — excluded
        )
        val active = StartPlanQuotaBuckets.getActiveModelBuckets(snap, NOW)
        assertEquals(1, active.size)
    }

    @Test
    fun `the remaining ratio is remaining over number - percentage only as fallback`() {
        assertEquals(0.5, StartPlanQuotaBuckets.bucketRemainingRatio(UsageQuotaLimit.decode(bucket(remaining = 50.0, number = 100.0)))!!, 1e-9)
        assertEquals(0.3, StartPlanQuotaBuckets.bucketRemainingRatio(UsageQuotaLimit.decode(bucket(remaining = null, percentage = 0.3)))!!, 1e-9)
        assertNull(StartPlanQuotaBuckets.bucketRemainingRatio(UsageQuotaLimit.decode(bucket(remaining = null, percentage = null))))
    }

    @Test
    fun `exhaustion requires EVERY active bucket exhausted - unknown never counts as 0`() {
        assertTrue(StartPlanQuotaBuckets.allBucketsExhausted(listOf(UsageQuotaLimit.decode(bucket(remaining = 0.0)), UsageQuotaLimit.decode(bucket(remaining = 0.0)))))
        assertTrue(
            !StartPlanQuotaBuckets.allBucketsExhausted(listOf(UsageQuotaLimit.decode(bucket(remaining = 0.0)), UsageQuotaLimit.decode(bucket(remaining = 5.0)))),
            "one live bucket means NOT exhausted (the first-bucket misread)",
        )
        assertTrue(
            !StartPlanQuotaBuckets.allBucketsExhausted(listOf(UsageQuotaLimit.decode(bucket(remaining = 0.0)), UsageQuotaLimit.decode(bucket(remaining = null, percentage = null)))),
            "an unknown value can never masquerade as 0",
        )
    }

    @Test
    fun `the reminder key is the stable bucket-period identity`() {
        assertEquals("[b-1,${NOW - 1_000},${NOW + 1_000}]", StartPlanQuotaBuckets.bucketReminderKey(UsageQuotaLimit.decode(bucket())))
        assertNull(StartPlanQuotaBuckets.bucketReminderKey(UsageQuotaLimit.decode(bucket(bucketId = null))), "unkeyable = no dedup")
        assertNull(StartPlanQuotaBuckets.bucketReminderKey(UsageQuotaLimit.decode(bucket(periodStart = null))))
        assertNull(StartPlanQuotaBuckets.bucketReminderKey(UsageQuotaLimit.decode(bucket(periodEnd = 1L, periodStart = 2L))), "inverted period")
    }

    @Test
    fun `the verdict ladder - low reminds once per key, exhausted always, healthy silent`() {
        // Healthy: no verdict.
        assertNull(StartPlanQuotaBuckets.verdict(snapshot(bucket(remaining = 80.0, number = 100.0)), "glm-5.1", NOW))
        // Very low (10%): a keyed verdict.
        val low = StartPlanQuotaBuckets.verdict(snapshot(bucket(remaining = 5.0, number = 100.0)), "glm-5.1", NOW)!!
        assertTrue(!low.exhausted)
        assertEquals(0.05, low.ratio!!, 1e-9)
        assertEquals("[b-1,${NOW - 1_000},${NOW + 1_000}]", low.reminderKey)
        // The reminder-dismissed key suppresses the low verdict.
        assertNull(StartPlanQuotaBuckets.verdict(snapshot(bucket(remaining = 5.0, number = 100.0)), "glm-5.1", NOW) { key -> key == low.reminderKey })
        // Exhausted: always shown, no dedup key.
        val exhausted = StartPlanQuotaBuckets.verdict(snapshot(bucket(remaining = 0.0, number = 100.0)), "glm-5.1", NOW)!!
        assertTrue(exhausted.exhausted)
        assertNull(exhausted.reminderKey)
        // A different model's bucket does not raise the banner for THIS model.
        assertNull(StartPlanQuotaBuckets.verdict(snapshot(bucket(remaining = 5.0, number = 100.0, model = "glm-other")), "glm-5.1", NOW))
    }
}
