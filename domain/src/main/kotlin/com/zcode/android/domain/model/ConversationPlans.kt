package com.zcode.android.domain.model

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * A3.12 (gaps-and-polish Phase 5B): the plans projection of `conversationPlansV4` —
 * decoded with the desktop's OWN plan-step normalization (chunk-4RHDQOPX.js `parsePlanStep`
 * ≈752487: title from content|step|title|text|activeForm; status normalized `-`→`_` and
 * lower-cased to pending|in_progress|completed; a bare-string step is its title, the FIRST
 * step of a shapeless list is in_progress). Tolerant (INV-8): unknown statuses/step shapes
 * are dropped, unknown plan keys fall back down the title ladder.
 */
enum class PlanStepStatus(val wire: String) {
    PENDING("pending"), IN_PROGRESS("in_progress"), COMPLETED("completed");

    companion object {
        /** The desktop's normalizePlanStatus: case-insensitive, `-` and `_` equivalent. */
        fun normalize(value: String?): PlanStepStatus? {
            val v = value?.replace('-', '_')?.lowercase() ?: return null
            return entries.firstOrNull { it.wire == v }
        }
    }
}

/** `parsePlanStep`: {id?, title, status} — or a bare string step. */
data class ConversationPlanStep(
    val id: String,
    val title: String,
    val status: PlanStepStatus,
) {
    companion object {
        fun decode(el: JsonElement, first: Boolean): ConversationPlanStep? = when (el) {
            is JsonPrimitive ->
                if (!el.isString) null
                else el.content.trim().takeIf { it.isNotEmpty() }?.let { ConversationPlanStep(it, it, if (first) PlanStepStatus.IN_PROGRESS else PlanStepStatus.PENDING) }
            is JsonObject -> {
                fun str(key: String): String? = (el[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
                val title = listOf("content", "step", "title", "text", "activeForm").firstNotNullOfOrNull(::str) ?: return null
                val status = PlanStepStatus.normalize(str("status")) ?: return null
                ConversationPlanStep(str("id") ?: title, title, status)
            }
            else -> null
        }
    }
}

/** One plan of the session's plans list: title + the step checklist. */
data class ConversationPlan(
    val title: String,
    val status: String?,
    val steps: List<ConversationPlanStep>,
) {
    val completedCount: Int get() = steps.count { it.status == PlanStepStatus.COMPLETED }

    companion object {
        fun decode(o: JsonObject): ConversationPlan? {
            fun str(key: String): String? = (o[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content?.takeIf { it.isNotBlank() }
            val title = listOf("title", "name", "objective", "content").firstNotNullOfOrNull(::str) ?: return null
            val rawSteps = o["steps"] as? JsonArray ?: return null
            val parsed = rawSteps.mapIndexedNotNull { i, el -> ConversationPlanStep.decode(el, first = i == 0) }
            return ConversationPlan(title, str("status"), parsed)
        }

        /** `{plans:[…]}` — tolerant of a bare array too. */
        fun decodeAll(el: JsonElement?): List<ConversationPlan> {
            val arr = when (el) {
                is JsonArray -> el
                is JsonObject -> el["plans"] as? JsonArray ?: return emptyList()
                else -> return emptyList()
            }
            return arr.mapNotNull { (it as? JsonObject)?.let(::decode) }
        }
    }
}
