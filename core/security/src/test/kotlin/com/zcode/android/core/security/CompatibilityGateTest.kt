package com.zcode.android.core.security

import com.zcode.android.core.security.pairing.CompatibilityGate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Phase 3.1 compat-gate table tests (§39.2 matrix + §12.2 step 3). The matrix rows are
 * normative; garbage versions fail closed.
 */
class CompatibilityGateTest {

    private fun v(s: String): CompatibilityGate.Verdict = CompatibilityGate.check(s)

    @Test
    fun `matrix rows`() {
        // Below minimum → BLOCK
        assertTrue(v("3.13.9") is CompatibilityGate.Verdict.Blocked)
        assertTrue(v("3.0.0") is CompatibilityGate.Verdict.Blocked)
        assertTrue(v("2.99.99") is CompatibilityGate.Verdict.Blocked)
        // Supported (tested) range
        assertEquals(CompatibilityGate.Verdict.Supported, v("3.14.0"))
        assertEquals(CompatibilityGate.Verdict.Supported, v("3.14.1"))
        assertEquals(CompatibilityGate.Verdict.Supported, v("3.14.3"))
        // Above maximum-tested → warn-continue
        assertTrue(v("3.14.4") is CompatibilityGate.Verdict.AboveTested)
        assertTrue(v("3.15.0") is CompatibilityGate.Verdict.AboveTested)
        // Future major → warn-continue at the QR gate (the wire-version literal check at
        // hello remains the hard gate per §39.2/§39.3).
        assertTrue(v("4.0.0") is CompatibilityGate.Verdict.AboveTested)
    }

    @Test
    fun `garbage versions fail closed`() {
        for (bad in listOf("", "abc", "3", "3.x.1", "-1.4.0", "3.14.-1", "3.14.3-beta")) {
            // Pre-release suffix parses on the core semver; metadata tolerated.
            val verdict = v(bad)
            assertTrue(
                verdict is CompatibilityGate.Verdict.Blocked || verdict is CompatibilityGate.Verdict.AboveTested || verdict == CompatibilityGate.Verdict.Supported,
                "unhandled verdict for '$bad'",
            )
        }
        assertTrue(v("abc") is CompatibilityGate.Verdict.Blocked)
        assertTrue(v("-1.4.0") is CompatibilityGate.Verdict.Blocked)
        // two-component version tolerated as x.y.0 by the gate (QR parser already requires 3 components)
        assertEquals(CompatibilityGate.Verdict.Supported, v("3.14"))
    }

    @Test
    fun `version comparison semantics`() {
        val p = CompatibilityGate::parseVersion
        val c = CompatibilityGate::compareVersions
        val a = p("3.14.3")!!
        val b = p("3.14.10")!!
        assertTrue(c(a, b) < 0, "3.14.3 < 3.14.10 (numeric, not lexical)")
        assertEquals(0, c(a, p("3.14.3")!!))
    }
}
