package com.zcode.android.features.pairing.port

import com.zcode.android.features.pairing.flow.PairingFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * PairingPort — the consumer-side seam between the pairing UI and the app composition
 * root. features:pairing NEVER imports core:security or core:transport (INV-2/§40.2
 * credential isolation): all IO is expressed with the value types above, and the app
 * module maps them from/to security + transport internals.
 *
 * Implementations MUST: write nothing on invalid input (§12.2 step 5), seal only after
 * every gate, never expose secret material through any of these value types (INV-3).
 */
interface PairingPort {

    /** Currently sealed credential metadata (null = unpaired). Drives Welcome resume. */
    val pairedEndpoint: StateFlow<PairingFlow.LinkView?>

    /**
     * Validate a raw link through the full gate chain (parse → stale → compat, §12.2).
     * Pure from the caller's perspective: no credential state is written here.
     */
    suspend fun validate(raw: String): Validation

    /** Suspend until the pairing connect attempt reaches a terminal outcome. */
    suspend fun completePairing(
        link: PairingFlow.LinkView,
        reducedSecurityConsent: Boolean,
    ): ConnectOutcome

    /** Reconnect attempt after E4/E6 (retry / takeover-consent path). */
    suspend fun retry(): ConnectOutcome

    /** Unpair: wipe credential + key material locally (§12.7). */
    suspend fun unpair()

    sealed class Validation {
        /** All gates passed; [stale] and [compatAboveTested] are warn-continue flags. */
        data class Ready(
            val link: PairingFlow.LinkView,
            val stale: Boolean,
            val compatAboveTested: Boolean,
            /** §3.3 policy decision surface for the SecuritySetup step. */
            val requireE7Consent: Boolean,
            val offerBiometricEnrollment: Boolean,
            /** B2.10: the gate's max-tested boundary (the warn-continue copy). */
            val maxTestedDesktop: String? = null,
        ) : Validation()

        data class Invalid(val reason: String, val detail: String) : Validation()

        /** E3 — hard block. */
        data class CompatBlocked(
            val version: String,
            val detail: String,
            /** B2.10: the gate's minimum-supported boundary (the block copy). */
            val minSupported: String? = null,
        ) : Validation()
    }

    sealed class ConnectOutcome {
        data class Success(val link: PairingFlow.LinkView) : ConnectOutcome()
        data class Failure(val error: PairingFlow.ErrorView) : ConnectOutcome()
        data object TakenOver : ConnectOutcome()
    }
}
