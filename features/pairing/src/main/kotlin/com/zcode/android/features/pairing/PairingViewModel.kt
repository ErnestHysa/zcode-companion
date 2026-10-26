package com.zcode.android.features.pairing

import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * PairingViewModel — binds the pure [PairingFlow] reducer to the [PairingPort].
 * Plain state holder (manual DI, no framework — §10.1); all coroutine work runs in
 * the injected scope; UI observes [state].
 */
class PairingViewModel(
    private val port: PairingPort,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<PairingFlow.State>(PairingFlow.initial(port.pairedEndpoint.value))
    val state: StateFlow<PairingFlow.State> = _state

    /** Link for the in-flight connect attempt (rides out of SecuritySetup). */
    private var pendingLink: PairingFlow.LinkView? = null

    fun dispatch(event: PairingFlow.Event) {
        when (event) {
            is PairingFlow.Event.QrDecoded -> {
                _state.value = PairingFlow.reduce(_state.value, event)
                validate(event.raw)
            }
            is PairingFlow.Event.SecurityConfirmed -> {
                val setup = _state.value as? PairingFlow.State.SecuritySetup ?: return
                _state.value = PairingFlow.reduce(_state.value, event)
                pendingLink = setup.link
                _state.value = PairingFlow.State.Connecting
                // F-PERM2: the E7 requirement the user ANSWERED rides with the consent.
                launchConnect {
                    port.completePairing(setup.link, setup.requireE7Consent, event.reducedSecurityConsent)
                }
            }
            is PairingFlow.Event.RetryRequested -> {
                _state.value = PairingFlow.reduce(_state.value, event)
                launchConnect { port.retry() }
            }
            is PairingFlow.Event.TakeOverRequested -> {
                _state.value = PairingFlow.reduce(_state.value, event)
                launchConnect { port.retry() }
            }
            is PairingFlow.Event.UnpairConfirmed -> {
                scope.launch {
                    port.unpair()
                    _state.value = PairingFlow.reduce(PairingFlow.State.Connected(pendingLink ?: fakeLink()), event)
                }
            }
            else -> _state.value = PairingFlow.reduce(_state.value, event)
        }
    }

    private var connectJob: Job? = null

    private fun launchConnect(call: suspend () -> PairingPort.ConnectOutcome) {
        connectJob?.cancel()
        connectJob = scope.launch {
            when (val outcome = runCatching { call() }.getOrElse {
                android.util.Log.e("ZCODE-FLOW", "connect failed", it)
                PairingPort.ConnectOutcome.Failure(
                    PairingFlow.ErrorView(
                        userMessage = "Unexpected error — please retry",
                        diagnostic = it.message ?: it.javaClass.simpleName,
                        rePair = false,
                        retryable = true,
                    ),
                )
            }) {
                is PairingPort.ConnectOutcome.Success ->
                    dispatch(PairingFlow.Event.ConnectSucceeded(outcome.link))
                is PairingPort.ConnectOutcome.Failure ->
                    dispatch(PairingFlow.Event.ConnectFailed(outcome.error, pendingLink))
                PairingPort.ConnectOutcome.TakenOver ->
                    dispatch(PairingFlow.Event.TerminalTakenOver)
            }
        }
    }

    private fun validate(raw: String) {
        scope.launch {
            when (val v = runCatching { port.validate(raw) }.getOrElse {
                android.util.Log.e("ZCODE-FLOW", "validate failed", it)
                PairingPort.Validation.Invalid("internal", it.message ?: "validation failed")
            }) {
                is PairingPort.Validation.Ready -> {
                    pendingLink = v.link
                    dispatch(
                        PairingFlow.Event.ValidationReady(
                            link = v.link,
                            stale = v.stale,
                            compatAboveTested = v.compatAboveTested,
                            requireE7Consent = v.requireE7Consent,
                            offerBiometricEnrollment = v.offerBiometricEnrollment,
                            maxTestedDesktop = v.maxTestedDesktop,
                        ),
                    )
                }
                is PairingPort.Validation.Invalid ->
                    dispatch(PairingFlow.Event.ValidationRejected(v.reason, v.detail))
                is PairingPort.Validation.CompatBlocked ->
                    dispatch(PairingFlow.Event.ValidationCompatBlocked(v.version, v.detail, v.minSupported))
            }
        }
    }

    private fun fakeLink() = PairingFlow.LinkView("paired", "endpoint", "", "", 0)
}
