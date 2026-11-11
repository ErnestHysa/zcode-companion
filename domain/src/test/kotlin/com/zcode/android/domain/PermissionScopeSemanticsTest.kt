package com.zcode.android.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import com.zcode.android.domain.model.PermissionOption
import com.zcode.android.domain.model.PermissionScope

/**
 * G76 (vc52 Phase 12) pin: the wire has NO scope field — the scope is WHICH
 * option the runtime names, and `kind` is the desktop's own classification.
 * Pins: the legacy id table (unchanged); the CURRENT ship's opaque scoped ids
 * classified by `kind`; custom + scoped label / fullAccess = allow; custom
 * otherwise = OTHER (fail closed, INV-14); the scope table byte-matches the
 * desktop's KAn keys; unknown labels have NO scope.
 */
class PermissionScopeSemanticsTest {

    @Test
    fun `the legacy id table is unchanged`() {
        assertEquals(PermissionOption.Semantics.ALLOW_ONCE, PermissionOption.Semantics.of("allowOnce"))
        assertEquals(PermissionOption.Semantics.ALLOW_ALWAYS, PermissionOption.Semantics.of("allowAlways"))
        assertEquals(PermissionOption.Semantics.ALLOW_PROJECT, PermissionOption.Semantics.of("allow_project"))
        assertEquals(PermissionOption.Semantics.DENY, PermissionOption.Semantics.of("deny"))
        assertEquals(PermissionOption.Semantics.REFINE, PermissionOption.Semantics.of("workflowRefine"))
        assertEquals(PermissionOption.Semantics.OTHER, PermissionOption.Semantics.of("mystery"))
    }

    @Test
    fun `an opaque scoped id classifies by its wire kind`() {
        // The CURRENT ship: scoped always-options carry opaque optionIds + the kind.
        val opt = PermissionOption("opt-7f2a", "Always allow in this project", kind = "allowAlways")
        assertEquals(PermissionOption.Semantics.ALLOW_ALWAYS, opt.semantics)
        assertEquals(PermissionScope.PROJECT, opt.scope)
    }

    @Test
    fun `custom plus fullAccess is allow - custom without a scope stays OTHER (fail closed)`() {
        val fullAccess = PermissionOption("fullAccess", "Full access", kind = "custom")
        assertEquals(PermissionOption.Semantics.ALLOW_ALWAYS, fullAccess.semantics)
        assertEquals(PermissionScope.FULL_ACCESS, fullAccess.scope)
        val weird = PermissionOption("opt-xyz", "Something else entirely", kind = "custom")
        assertEquals(PermissionOption.Semantics.OTHER, weird.semantics, "custom + no scope = inert (INV-14)")
    }

    @Test
    fun `unknown id with NO kind stays OTHER (the pre-G76 face)`() {
        assertEquals(PermissionOption.Semantics.OTHER, PermissionOption.Semantics.of("mystery-2", null, null))
    }

    @Test
    fun `the scope table byte-matches the desktop's KAn keys`() {
        assertEquals(PermissionScope.PROJECT, PermissionScope.of("Always allow in this project"))
        assertEquals(PermissionScope.CUA_PROJECT, PermissionScope.of("Always allow computer use in this project"))
        assertEquals(PermissionScope.SESSION, PermissionScope.of("Always allow in this session"))
        assertEquals(PermissionScope.FULL_ACCESS, PermissionScope.of("Full access"))
        // The whitespace-normalized variant (the desktop's qAn normalizer).
        assertEquals(PermissionScope.PROJECT, PermissionScope.of("always  allow in this  project"))
        assertNull(PermissionScope.of("Allow once"))
        assertNull(PermissionScope.of(null))
    }

    @Test
    fun `deny and allowOnce kinds classify without inventing scope`() {
        assertEquals(PermissionOption.Semantics.DENY, PermissionOption("opt-d", "No", kind = "deny").semantics)
        assertEquals(PermissionOption.Semantics.ALLOW_ONCE, PermissionOption("opt-o", "Yes, once", kind = "allowOnce").semantics)
        assertNull(PermissionOption("opt-d", "No", kind = "deny").scope)
    }
}
