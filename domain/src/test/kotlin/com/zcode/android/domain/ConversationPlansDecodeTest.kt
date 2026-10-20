package com.zcode.android.domain

import com.zcode.android.domain.model.ConversationPlan
import com.zcode.android.domain.model.PlanStepStatus
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A3.12 (Phase 5B) decode pins: the desktop's parsePlanStep normalization — title from
 * content|step|title|text|activeForm, status `-`→`_` + case-insensitive, a bare-string
 * step becomes its title with the FIRST step in_progress; unknown statuses/shapes drop.
 */
class ConversationPlansDecodeTest {

    @Test
    fun `plans decode with the desktop's step normalization`() {
        val plans = ConversationPlan.decodeAll(
            Json.parseToJsonElement(
                """{"plans":[
                  {"title":"Ship the release","steps":[
                    {"content":"Write the changelog","status":"completed"},
                    {"content":"Bump the version","status":"in-progress"},
                    {"content":"Tag and publish","status":"pending"},
                    "ship-it",
                    {"content":"?","status":"weird"}
                  ]},
                  {"name":"Second plan","steps":[{"step":"only the step key","status":"IN_PROGRESS"}]}
                ]}""",
            ),
        )
        assertEquals(2, plans.size)
        val first = plans[0]
        assertEquals("Ship the release", first.title)
        assertEquals(4, first.steps.size, "the unknown-status step drops")
        assertEquals(PlanStepStatus.COMPLETED, first.steps[0].status)
        assertEquals(PlanStepStatus.IN_PROGRESS, first.steps[1].status, "dash-normalized")
        assertEquals(PlanStepStatus.PENDING, first.steps[2].status)
        assertEquals("ship-it", first.steps[3].title, "a bare string step is its title")
        assertEquals(PlanStepStatus.PENDING, first.steps[3].status, "only the FIRST shapeless step starts in_progress")
        assertEquals(1, first.completedCount)
        assertEquals(PlanStepStatus.IN_PROGRESS, plans[1].steps.single().status, "case-insensitive")
        assertEquals("only the step key", plans[1].steps.single().title, "the step-key ladder")
        assertTrue(ConversationPlan.decodeAll(null).isEmpty())
        assertTrue(ConversationPlan.decodeAll(Json.parseToJsonElement("""{"other":1}""")).isEmpty())
        assertNull(ConversationPlan.decode(Json.parseToJsonElement("""{"steps":[]}""")))
    }
}
