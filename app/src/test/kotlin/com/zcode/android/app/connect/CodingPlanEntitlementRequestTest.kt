package com.zcode.android.app.connect

import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AAP-3 follow-up (live device finding 2026-10-09): the coding-plan quota
 * panel showed the "no account connected" face because the entitlement
 * request was EMPTY — the service then resolves nothing and answers
 * unavailableReason="not_configured". The desktop's own usage summary
 * addresses the builtin provider directly; this pins that request shape.
 */
class CodingPlanEntitlementRequestTest {

    @Test
    fun `request carries the desktop footer's keys for the zai builtin provider`() {
        val req = codingPlanEntitlementRequest("account:zai-individual-coding-plan").jsonObject
        // verbatim from WorkspaceSidebarFooterUsageSummary.tsx
        assertEquals("true", (req["includeSubscription"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            "account:zai-individual-coding-plan",
            (req["preferredProviderId"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        val access = req["accountAccess"]!!.jsonObject
        assertEquals("zhipu-account", (access["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals("zai", (access["accountType"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            "individual-coding-plan",
            (access["mode"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals("true", (access["entitled"] as kotlinx.serialization.json.JsonPrimitive).content)
        assertEquals(
            "true",
            (req["requirePreferredProvider"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals(
            "true",
            (req["allowDisabledPreferredProvider"] as kotlinx.serialization.json.JsonPrimitive).content,
        )
        assertEquals("false", (req["allowEnvApiKey"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `unknown provider id derives nothing - empty request (ADR-020 rule intact)`() {
        val req = codingPlanEntitlementRequest("some-custom-provider").jsonObject
        assertTrue(req.isEmpty())
    }
}
