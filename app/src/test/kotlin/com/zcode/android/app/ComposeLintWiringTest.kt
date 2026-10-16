package com.zcode.android.app

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * E6-lint (gaps-and-polish Phase 2A, P1): `app/build.gradle.kts` had no `lint {}` block
 * and no Compose lint dependency, so the bug classes Part B found by hand (bare
 * `clickable {}` without roles, sub-48dp targets, literal ARGB, un-guarded early returns
 * before `remember`) could regress silently.
 *
 * Wiring gate: the discipline exists only if (1) every Compose module declares the
 * slack compose-lints checks, (2) the app's lint block aborts on error and checks
 * dependencies (so one `:app:lintDebug` covers every feature module), (3) the CI
 * workflow runs it, and (4) the triage artifact (baseline) is present.
 */
class ComposeLintWiringTest {

    private val root: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        requireNotNull(dir) { "repo root not found" }
    }

    private val composeModules = listOf(
        "app", "core/ui", "features/pairing", "features/tasks", "features/conversation",
        "features/terminal", "features/git", "features/files",
    )

    @Test
    fun `the version catalog declares the compose lint checks`() {
        val catalog = File(root, "gradle/libs.versions.toml").readText()
        assertTrue("libs.versions.toml must declare compose-lint-checks", "compose-lint-checks" in catalog)
        assertTrue("the checks are slack's compose-lints", "com.slack.lint.compose:compose-lint-checks" in catalog)
    }

    @Test
    fun `every Compose module wires lintChecks`() {
        val missing = composeModules.filter { m ->
            val gradle = File(root, "$m/build.gradle.kts").readText()
            "lintChecks(libs.compose.lint.checks)" !in gradle
        }
        assertTrue("modules missing lintChecks(libs.compose.lint.checks): $missing", missing.isEmpty())
    }

    @Test
    fun `the app lint block aborts on error and checks dependencies`() {
        val gradle = File(root, "app/build.gradle.kts").readText()
        assertTrue("app needs a lint {} block", Regex("""\blint\s*\{""").containsMatchIn(gradle))
        assertTrue("lint must abort on error", Regex("""abortOnError\s*=\s*true""").containsMatchIn(gradle))
        assertTrue("lint must check dependencies (one gate for every feature module)", Regex("""checkDependencies\s*=\s*true""").containsMatchIn(gradle))
        assertTrue("lint must use the triage baseline", "lint-baseline.xml" in gradle)
    }

    @Test
    fun `CI runs the lint gate and the triage baseline exists`() {
        val ci = File(root, ".github/workflows/ci.yml").readText()
        assertTrue("ci.yml must run the lint gate", Regex("""gradle\s+:app:lint(Debug)?\b""").containsMatchIn(ci))
        assertTrue("the triage baseline must be committed", File(root, "app/lint-baseline.xml").isFile)
    }
}
