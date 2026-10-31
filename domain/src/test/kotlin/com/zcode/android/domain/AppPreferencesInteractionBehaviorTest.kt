package com.zcode.android.domain

import com.zcode.android.domain.model.AppPreferences
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * G4/AC6 (vc51): the default interaction-behavior preference (the desktop's
 * `settings.zcodeInteractionBehavior`) — additive schema (§32.3): default null (no local
 * default = the pre-G4 follow-the-desktop behavior), and the stored value is the closed
 * "queue"|"guide" set.
 */
class AppPreferencesInteractionBehaviorTest {

    @Test
    fun `default is null - no local default without an explicit user choice`() {
        assertNull(AppPreferences().interactionBehavior)
    }

    @Test
    fun `copy carries the stored behavior`() {
        val updated = AppPreferences().copy(interactionBehavior = "queue")
        assertEquals("queue", updated.interactionBehavior)
        assertEquals("guide", updated.copy(interactionBehavior = "guide").interactionBehavior)
        assertNull(updated.copy(interactionBehavior = null).interactionBehavior)
    }
}
