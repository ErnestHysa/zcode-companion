package com.zcode.android.app.update

/**
 * G43-P24 (vc52 Phase 13): the skip-version gate — the desktop's SkipUpdateVersion,
 * phone-shaped. "Skip this version" suppresses the PROMPT for EXACTLY that
 * release (the banner on the Connection screen and the availability
 * notification); it never hides the FACT — a manual check still resolves
 * Available, and a newer release (or a cleared marker) prompts again.
 */
object UpdateSkipGate {
    fun bannerSuppressed(releaseVersionCode: Long, skippedVersionCode: Long?): Boolean =
        skippedVersionCode != null && releaseVersionCode == skippedVersionCode
}
