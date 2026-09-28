package com.zcode.android.domain

import com.zcode.android.domain.model.PermissionOption
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * §20.4 fail-closed option semantics (INV-14): an approval that cannot be understood
 * is never approved — unknown option ids are inert and NEVER allow-capable.
 */
class PermissionOptionTest {

    @Test
    fun `synthesized option ids map to verified semantics`() {
        assertEquals(PermissionOption.Semantics.ALLOW_ONCE, PermissionOption("allowOnce").semantics)
        assertEquals(PermissionOption.Semantics.ALLOW_ALWAYS, PermissionOption("allowAlways").semantics)
        assertEquals(PermissionOption.Semantics.ALLOW_PROJECT, PermissionOption("allow_project").semantics)
        assertEquals(PermissionOption.Semantics.DENY, PermissionOption("deny").semantics)
        assertEquals(PermissionOption.Semantics.REFINE, PermissionOption("workflowRefine").semantics)
    }

    @Test
    fun `unknown option id is inert and NEVER allow-capable (INV-14)`() {
        val unknown = PermissionOption("someFutureOption")
        assertEquals(PermissionOption.Semantics.OTHER, unknown.semantics)
        assertFalse(unknown.allowCapable)
    }

    @Test
    fun `allow-capable set is exactly the recognized allow semantics`() {
        assertTrue(PermissionOption("allowOnce").allowCapable)
        assertTrue(PermissionOption("allowAlways").allowCapable)
        assertTrue(PermissionOption("allow_project").allowCapable)
        assertFalse(PermissionOption("deny").allowCapable)
        assertFalse(PermissionOption("workflowRefine").allowCapable)
        assertFalse(PermissionOption("allow_always_uppercase").allowCapable) // exact ids only
    }
}
