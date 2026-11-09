package com.zcode.android.app

import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick

/**
 * F-34-E (vc52 Phase 8, rig-caught): the post-pairing transition is REACHABLE now — a
 * pairing lands on the notification ask while Android reports notifications off (every
 * fresh API-33+ install), then on the first-run onboarding, then the task list. Before the
 * fix the NavHost's start-destination flip reset the stack straight to the task list, so
 * neither step ever showed on a real pairing. Instrumented pairing flows walk them here.
 */
internal fun AndroidComposeTestRule<*, *>.finishPostPairingSteps(timeoutMs: Long = 30_000) {
    fun present(tag: String) = onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    waitUntil(timeoutMs) { present("tasks-title") || present("notification_setup") || present("onboarding_skip") }
    if (present("notification_setup")) {
        onNodeWithTag("notification_setup_continue").performClick()
        waitUntil(timeoutMs) { present("tasks-title") || present("onboarding_skip") }
    }
    if (present("onboarding_skip")) onNodeWithTag("onboarding_skip").performClick()
    waitUntil(timeoutMs) { present("tasks-title") }
}
