package com.zcode.android.core.transport

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * C5-i18n (gaps-and-polish Phase 2D): every AppError the §33 mapping table produces
 * carries a message KEY (+ optional args) so the UI can localize the most
 * stress-timed copy in the app; `userMessage` stays only as the English fallback.
 */
class ErrorMappingMessageKeyTest {

    @Test
    fun `every mapped AppError carries a message key`() {
        val m = ErrorMapping
        val errors: List<Pair<String, AppError>> = listOf(
            "closeCode(4000)" to m.closeCode(4000),
            "closeCode(4009)" to m.closeCode(4009),
            "closeCode(4010)" to m.closeCode(4010),
            "closeCode(4011)" to m.closeCode(4011),
            "closeCode(4012)" to m.closeCode(4012),
            "closeCode(4013)" to m.closeCode(4013),
            "closeCode(other)" to m.closeCode(1006),
            "directTokenRejected" to m.directTokenRejected(401),
            "localNetworkBlocked" to m.localNetworkBlocked("EPERM"),
            "tofuPinMismatch" to m.tofuPinMismatch("aaaa", "bbbb"),
            "insecureDirectTransport" to m.insecureDirectTransport("ws://x"),
            "relayError(AUTH_FAILED)" to m.relayError("AUTH_FAILED", null),
            "relayError(DEVICE_OFFLINE)" to m.relayError("DEVICE_OFFLINE", null),
            "relayError(KICKED)" to m.relayError("KICKED", null),
            "relayError(INTERNAL)" to m.relayError("INTERNAL", null),
            "relayError(WRONG_PARAM)" to m.relayError("WRONG_PARAM", null),
            "relayError(other)" to m.relayError("ZZZ", null),
            "protocolFault" to m.protocolFault("d"),
            "serializationFault" to m.serializationFault("d"),
            "networkUnreachable" to m.networkUnreachable("d"),
            "timeout" to m.timeout("q", 10),
            "versionIncompatible" to m.versionIncompatible("d"),
            "degradedBudget" to m.degradedBudget("d"),
            "sessionUnavailable" to m.sessionUnavailable(),
            "bridgeError(session-conflict)" to m.bridgeError("session-conflict"),
            "bridgeError(session-not-found)" to m.bridgeError("session-not-found"),
            "bridgeError(workspace-closed)" to m.bridgeError("workspace-closed"),
            "bridgeError(invalid-mobile-connection)" to m.bridgeError("invalid-mobile-connection"),
            "bridgeError(desktop-disconnected)" to m.bridgeError("desktop-disconnected"),
            "bridgeError(other)" to m.bridgeError("relay-unavailable"),
            "taskRejected" to m.taskRejected("m"),
            "permissionStale" to m.permissionStale(),
            "storageQuarantine" to m.storageQuarantine(),
            "androidPermission" to m.androidPermission("camera"),
            "notificationBlocked" to m.notificationBlocked(),
            "authzRefusal" to m.authzRefusal("x"),
            "internal" to m.internal("d"),
        )
        val missing = errors.filter { (_, e) -> e.messageKey == null }.map { it.first }
        assertTrue("ErrorMapping errors without a message key (compiled English only): $missing", missing.isEmpty())
    }
}
