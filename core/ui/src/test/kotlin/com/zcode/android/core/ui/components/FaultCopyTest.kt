package com.zcode.android.core.ui.components

import com.zcode.android.core.ui.R
import org.junit.Assert.assertEquals
import org.junit.Test

/** K9-errorcopy: the raw → friendly classifier (one mechanism for every error state). */
class FaultCopyTest {
    @Test
    fun `desktop fault codes map to the unavailable or desktop-failed copy`() {
        assertEquals(R.string.err_fault_unavailable, faultMessageRes("fault.mock.unscripted:getAppUsageStats"))
        assertEquals(R.string.err_fault_unavailable, faultMessageRes("fault.method.unsupported"))
        assertEquals(R.string.err_fault_desktop, faultMessageRes("fault.internal:boom"))
    }

    @Test
    fun `transport internals map to the connection-lost copy`() {
        assertEquals(R.string.err_msg_connection_lost_reconnecting, faultMessageRes("channel closed: no outbound sink attached"))
        assertEquals(R.string.err_msg_connection_lost_reconnecting, faultMessageRes("socket EOF"))
    }

    @Test
    fun `timeouts, refusals and blanks have their own copy`() {
        assertEquals(R.string.err_msg_taking_too_long, faultMessageRes("request timed out after 10000ms"))
        assertEquals(R.string.err_msg_not_allowed, faultMessageRes("channel refused"))
        assertEquals(R.string.err_msg_something_went_wrong, faultMessageRes(null))
    }
}
