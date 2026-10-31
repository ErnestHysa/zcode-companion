package com.zcode.android.domain.model

/**
 * G11 (vc51) — the Start-Plan bucket banner logic (startPlanQuotaBuckets.ts,
 * ported verbatim): which buckets are ACTIVE for the model now, how much
 * remains, whether the plan is exhausted, and the STABLE reminder key that
 * deduplicates the low-quota banner to once per bucket period.
 */
object StartPlanQuotaBuckets {

    fun normalizeQuotaModel(value: String): String =
        value.trim().lowercase()
            .removePrefix("model:")
            .replace("_", "-")

    fun bucketMatchesModel(bucket: UsageQuotaLimit, modelId: String): Boolean =
        bucket.usageDetails.any { normalizeQuotaModel(it.modelCode) == normalizeQuotaModel(modelId) }

    /** The model_usage buckets whose period windows contain [now] (absent = active). */
    fun getActiveModelBuckets(snapshot: UsageEntitlement, now: Long = snapshot.serverTime ?: snapshot.generatedAt): List<UsageQuotaLimit> =
        (snapshot.limits).filter { bucket ->
            (bucket.meter == null || bucket.meter == "model_usage") &&
                (bucket.nextResetTime == null || bucket.nextResetTime > now) &&
                (bucket.periodStart == null || bucket.periodStart <= now) &&
                (bucket.periodEnd == null || bucket.periodEnd > now)
        }

    private fun finite(value: Double?): Boolean = value != null && value.isFinite()

    /**
     * The REAL remaining share (remaining/number — never `available`, and an
     * unknown value can never masquerade as 0).
     */
    fun bucketRemainingRatio(bucket: UsageQuotaLimit): Double? {
        if (finite(bucket.remaining) && finite(bucket.number) && bucket.number!! > 0) {
            return bucket.remaining!! / bucket.number!!
        }
        return bucket.percentage
    }

    /** Only EVERY active bucket exhausted is exhausted (the first-bucket misread). */
    fun allBucketsExhausted(buckets: List<UsageQuotaLimit>): Boolean =
        buckets.isNotEmpty() && buckets.all { bucket ->
            if (finite(bucket.remaining)) bucket.remaining!! <= 0 else bucketRemainingRatio(bucket) == 0.0
        }

    /**
     * The stable once-per-period reminder key: [bucketId, periodStart, periodEnd]
     * — the bucket identity isolates account/plan; the same bucket serving several
     * models still reminds ONCE. null = not keyable (no reminder dedup).
     */
    fun bucketReminderKey(bucket: UsageQuotaLimit): String? {
        val id = bucket.bucketId?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
        val start = bucket.periodStart ?: return null
        val end = bucket.periodEnd ?: return null
        if (start < 0 || end <= start) return null
        return "[$id,$start,$end]"
    }

    /**
     * The banner verdict for the session (the low→exhausted ladder, the desktop's
     * model-very-low / model-exhausted kinds): null = nothing to show.
     */
    fun verdict(
        snapshot: UsageEntitlement,
        modelId: String?,
        now: Long = snapshot.serverTime ?: snapshot.generatedAt,
        reminderHidden: (key: String) -> Boolean = { false },
    ): Verdict? {
        if (!snapshot.authenticated) return null
        val buckets = getActiveModelBuckets(snapshot, now)
            .let { if (modelId != null) it.filter { b -> bucketMatchesModel(b, modelId) } else it }
        if (buckets.isEmpty()) return null
        if (allBucketsExhausted(buckets)) {
            return Verdict(exhausted = true, ratio = 0.0, reminderKey = null, buckets = buckets)
        }
        val veryLow = buckets.mapNotNull { b -> bucketRemainingRatio(b)?.let { b to it } }
            .filter { (_, ratio) -> ratio in 0.0..VERY_LOW_RATIO }
        if (veryLow.isEmpty()) return null
        val (bucket, ratio) = veryLow.minBy { (_, ratio) -> ratio }
        val key = bucketReminderKey(bucket) ?: return null
        if (reminderHidden(key)) return null
        return Verdict(exhausted = false, ratio = ratio, reminderKey = key, buckets = buckets)
    }

    /** The desktop's very-low threshold: 10% remaining. */
    const val VERY_LOW_RATIO = 0.10

    data class Verdict(
        val exhausted: Boolean,
        val ratio: Double?,
        /** Stable once-per-period dedup key; null for the exhausted verdict (always shown). */
        val reminderKey: String?,
        val buckets: List<UsageQuotaLimit>,
    )
}
