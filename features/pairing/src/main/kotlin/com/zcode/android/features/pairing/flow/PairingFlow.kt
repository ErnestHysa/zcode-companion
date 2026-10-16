package com.zcode.android.features.pairing.flow

/**
 * Pairing-flow state machine — §2.6 journey with error branches E1–E7, §18.3 screen
 * states. PURE reducer: (state, event) → state. IO (validation, sealing, connect)
 * lives behind [com.zcode.android.features.pairing.port.PairingPort] and arrives as
 * result-carrying events.
 *
 * Branch coverage (normative):
 *  E1 camera denied → [State.CameraPermissionNeeded] rationale → retry / manual entry
 *  E2 not a ZCode pairing URL → [State.Rejected], no state written (port never sealed)
 *  E3 unsupported app_version → [State.CompatBlocked], pairing blocked
 *  E4 network unreachable → [State.ConnectFailed] retryable (transport class)
 *  E5 AUTH_FAILED / 4013 / 4011 → [State.ConnectFailed] re-pair → re-scan
 *  E6 4009 / KICKED (single terminal) → [State.TakenOver] explainer + takeover consent
 *  E7 no secure lock screen → [State.SecuritySetup] presents explicit reduced-security
 *     consent — never a silent fallback (INV-14)
 */
object PairingFlow {

    /** Display-only link view — the SECRET hash never reaches this layer (§26.3). */
    data class LinkView(
        val deviceSid: String,
        val desktopName: String,
        val desktopAppVersion: String,
        val origin: String,
        val issuedAtMs: Long,
    )

    data class ErrorView(
        /** English fallback; the UI localizes [messageKey] when present (C5-i18n). */
        val userMessage: String,
        val diagnostic: String,
        /** true = AUTH class (E5): the only remedy is re-pair (re-scan). */
        val rePair: Boolean,
        /** true = transport/network class (E4): retry makes sense. */
        val retryable: Boolean,
        val messageKey: com.zcode.android.core.common.error.UserMessageKey? = null,
        val messageArgs: List<String> = emptyList(),
    )

    sealed class State {
        abstract val link: LinkView?

        /** Welcome/security explainer; [link] non-null when a credential is already sealed. */
        data class Welcome(override val link: LinkView?) : State()

        /** E1: camera permission missing/denied — rationale + retry / manual entry. */
        data object CameraPermissionNeeded : State() { override val link: LinkView? = null }

        /** Live scanner. */
        data object Scanning : State() { override val link: LinkView? = null }

        /** Manual paste/typed link entry (§12.2 step 1: first-class fallback). */
        data class ManualEntry(val draft: String = "", val error: String? = null) : State() {
            override val link: LinkView? = null
        }

        data object Validating : State() { override val link: LinkView? = null }

        /** E2: invalid input — user edits or re-scans. NOTHING was written (§12.2 step 5). */
        data class Rejected(val reason: String, val detail: String) : State() {
            override val link: LinkView? = null
        }

        /** QR older than the stale window → warn-continue (§12.2 `t` sanity). */
        data class StaleWarning(override val link: LinkView) : State()

        /** E3: below minimum supported desktop — pairing blocked. */
        data class CompatBlocked(
            val version: String,
            val detail: String,
            /** B2.10: the gate's minimum-supported boundary — rendered from the gate,
             *  never a UI literal. */
            val minSupported: String? = null,
        ) : State() {
            override val link: LinkView? = null
        }

        /** Above maximum-tested → warn-continue (§39.2). */
        data class CompatAboveTested(
            override val link: LinkView,
            /** B2.10: the gate's max-tested boundary — rendered from the gate. */
            val maxTestedDesktop: String? = null,
        ) : State()

        /**
         * Keystore setup + biometric policy presentation. [requireE7Consent] marks the
         * no-secure-lock device (E7 — explicit consent REQUIRED to proceed);
         * [reducedSecurityConsent] records that acceptance.
         */
        data class SecuritySetup(
            override val link: LinkView,
            val requireE7Consent: Boolean,
            val offerBiometricEnrollment: Boolean,
            val reducedSecurityConsent: Boolean = false,
        ) : State()

        data object Connecting : State() { override val link: LinkView? = null }

        /** E4 (retryable) / E5 (rePair → re-scan). */
        data class ConnectFailed(val error: ErrorView, override val link: LinkView?) : State()

        /** E6: another terminal took the single slot (§15.3) — takeover consent UX. */
        data object TakenOver : State() { override val link: LinkView? = null }

        data class Connected(override val link: LinkView) : State()

        /** Unpair confirmation (§12.7 — includes the desktop "Stop" guidance). */
        data class UnpairConfirm(override val link: LinkView) : State()
    }

    sealed class Event {
        object StartScan : Event()
        object CameraPermissionDenied : Event()
        object CameraPermissionGranted : Event()
        object OpenManualEntry : Event()
        data class DraftChanged(val draft: String) : Event()
        object CancelScan : Event()
        data class QrDecoded(val raw: String) : Event()

        /** Results of port.validate(raw) — one event per verdict shape. */
        data class ValidationRejected(val reason: String, val detail: String) : Event()
        data class ValidationReady(
            val link: LinkView,
            val stale: Boolean,
            val compatAboveTested: Boolean,
            val requireE7Consent: Boolean,
            val offerBiometricEnrollment: Boolean,
            val maxTestedDesktop: String? = null,
        ) : Event()
        data class ValidationCompatBlocked(
            val version: String,
            val detail: String,
            val minSupported: String? = null,
        ) : Event()

        object StaleAccepted : Event()
        object CompatAccepted : Event()
        data class SecurityConfirmed(val reducedSecurityConsent: Boolean) : Event()

        /** Port outcome events. */
        data class ConnectSucceeded(val link: LinkView) : Event()
        data class ConnectFailed(val error: ErrorView, val link: LinkView?) : Event()
        object TerminalTakenOver : Event()

        object RetryRequested : Event()
        object TakeOverRequested : Event()
        object DismissTakenOver : Event()
        object UnpairRequested : Event()
        object UnpairConfirmed : Event()
        object UnpairCancelled : Event()
    }

    fun initial(resumed: LinkView?): State = State.Welcome(resumed)

    fun reduce(state: State, event: Event): State {
        if (event is Event.UnpairRequested && state is State.Connected) {
            return State.UnpairConfirm(state.link)
        }
        return when (event) {
            is Event.StartScan -> State.Scanning
            is Event.CameraPermissionDenied -> State.CameraPermissionNeeded
            is Event.CameraPermissionGranted -> State.Scanning
            is Event.OpenManualEntry -> State.ManualEntry()
            is Event.DraftChanged -> if (state is State.ManualEntry) state.copy(draft = event.draft) else state
            is Event.CancelScan -> State.Welcome(resumedLink(state))
            is Event.QrDecoded -> State.Validating
            is Event.ValidationRejected -> State.Rejected(event.reason, event.detail)
            is Event.ValidationCompatBlocked ->
                State.CompatBlocked(event.version, event.detail, event.minSupported)
            is Event.ValidationReady -> when {
                event.stale -> State.StaleWarning(event.link)
                event.compatAboveTested -> State.CompatAboveTested(event.link, event.maxTestedDesktop)
                else -> State.SecuritySetup(
                    link = event.link,
                    requireE7Consent = event.requireE7Consent,
                    offerBiometricEnrollment = event.offerBiometricEnrollment,
                )
            }
            is Event.StaleAccepted -> (state as? State.StaleWarning)?.toSetup()
                ?: State.SecuritySetup(unknownLink(), requireE7Consent = false, offerBiometricEnrollment = false)
            is Event.CompatAccepted -> (state as? State.CompatAboveTested)?.toSetup()
                ?: State.SecuritySetup(unknownLink(), requireE7Consent = false, offerBiometricEnrollment = false)
            is Event.SecurityConfirmed ->
                (state as? State.SecuritySetup)?.copy(reducedSecurityConsent = event.reducedSecurityConsent)
                    ?: state
            is Event.ConnectSucceeded -> State.Connected(event.link)
            is Event.ConnectFailed -> State.ConnectFailed(event.error, event.link ?: resumedLink(state))
            is Event.TerminalTakenOver -> State.TakenOver
            is Event.RetryRequested -> State.Connecting
            is Event.TakeOverRequested -> State.Connecting
            is Event.DismissTakenOver -> State.Welcome(null)
            is Event.UnpairConfirmed -> State.Welcome(null)
            is Event.UnpairCancelled -> state // stays on the confirm's source state via VM refresh
            is Event.UnpairRequested -> state // handled by the Connected guard above
        }
    }

    private fun State.StaleWarning.toSetup() = State.SecuritySetup(
        link, requireE7Consent = false, offerBiometricEnrollment = false,
    )

    private fun State.CompatAboveTested.toSetup() = State.SecuritySetup(
        link, requireE7Consent = false, offerBiometricEnrollment = false,
    )

    private fun resumedLink(s: State): LinkView? = when (s) {
        is State.Welcome -> s.link
        is State.Connected -> s.link
        is State.ConnectFailed -> s.link
        else -> null
    }

    private fun unknownLink() = LinkView("pending", "desktop", "", "", 0)
}
