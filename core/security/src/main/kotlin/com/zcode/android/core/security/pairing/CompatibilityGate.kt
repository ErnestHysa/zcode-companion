package com.zcode.android.core.security.pairing

/**
 * Desktop-version compatibility gate — architectural.md §12.2 step 3 + §39.2 matrix
 * (living table, verified against desktop v3.14.3 on 2026-09-27/28).
 *
 * Below minimum → BLOCK (E3: compatibility screen, pairing refused). Above maximum
 * tested → WARN-CONTINUE (expected-compatible; the wire-version literal check at hello
 * remains the hard gate — Phase 2 `V4WireVersionException`).
 */
object CompatibilityGate {

    /** §39.2: < 3.14.0 unsupported (v3→v4 remote app routes differ; relay shifted in 3.14). */
    const val MIN_SUPPORTED_DESKTOP = "3.14.0"

    /** §39.2: 3.14.0–3.14.5 supported. 3.14.5 live-verified 2026-10-13 → 2026-10-26
     * (real device: pairing, auth, bridge-open, bootstrap 610 tasks/8 workspaces,
     * stable connection, v4 conversation plane — the F-WORKSPACES/F-QR/F-PERM field
     * round ran against it; diagnostics export desktopAppVersion=3.14.5). */
    const val MAX_TESTED_DESKTOP = "3.14.5"

    sealed class Verdict {
        /** Inside the tested range. */
        data object Supported : Verdict()

        /** Above maximum-tested → warn-continue (§12.2 step 3). */
        data class AboveTested(val version: String) : Verdict()

        /** Below minimum / unparseable → hard block (E3). Fail-closed on garbage. */
        data class Blocked(val version: String, val detail: String) : Verdict()
    }

    fun check(appVersion: String): Verdict {
        val v = parseVersion(appVersion)
            ?: return Verdict.Blocked(appVersion, "app_version unparseable")
        return when {
            compareVersions(v, parseVersion(MIN_SUPPORTED_DESKTOP)!!) < 0 ->
                Verdict.Blocked(appVersion, "below minimum supported desktop $MIN_SUPPORTED_DESKTOP")
            compareVersions(v, parseVersion(MAX_TESTED_DESKTOP)!!) > 0 ->
                Verdict.AboveTested(appVersion)
            else -> Verdict.Supported
        }
    }

    data class SemVer(val major: Int, val minor: Int, val patch: Int)

    fun parseVersion(s: String): SemVer? {
        val core = s.substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 2) return null
        val maj = parts[0].toIntOrNull() ?: return null
        val min = parts[1].toIntOrNull() ?: return null
        val pat = parts.getOrElse(2) { "0" }.toIntOrNull() ?: return null
        if (maj < 0 || min < 0 || pat < 0) return null
        return SemVer(maj, min, pat)
    }

    fun compareVersions(a: SemVer, b: SemVer): Int =
        compareValues(a.major, b.major)
            .takeIf { it != 0 }
            ?: compareValues(a.minor, b.minor).takeIf { it != 0 }
            ?: compareValues(a.patch, b.patch)
}
