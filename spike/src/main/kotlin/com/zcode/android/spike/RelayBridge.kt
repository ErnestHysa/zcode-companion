package com.zcode.android.spike

import java.security.SecureRandom

/** Bridge identity helpers shared by spike stages. */
object RelayBridge {
    fun recoveryId(): String {
        val raw = ByteArray(12)
        SecureRandom().nextBytes(raw)
        return buildString {
            for (b in raw) append("abcdefghijklmnopqrstuvwxyz0123456789"[(b.toInt() and 0xFF) % 36])
        }
    }
}
