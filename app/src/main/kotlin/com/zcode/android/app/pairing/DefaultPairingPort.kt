package com.zcode.android.app.pairing

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.biometric.AndroidBiometricGate
import com.zcode.android.core.security.biometric.BiometricGatePolicy
import com.zcode.android.core.security.pairing.CompatibilityGate
import com.zcode.android.core.security.pairing.PairingLink
import com.zcode.android.core.security.pairing.PairingLinkParser
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.security.vault.SealOutcome
import com.zcode.android.core.transport.EndpointDescriptor
import com.zcode.android.core.transport.ErrorMapping
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.port.PairingPort
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Composition-root implementation of the pairing port: security (parser/gate/vault)
 * + transport, mapped into the feature-owned value types. This is the ONLY place the
 * two worlds meet (§40.2 — features never import security/transport; INV-2/6).
 *
 * The raw [PairingLink] (carrying the SECRET hash) lives ONLY in [lastValidated] —
 * in-memory, replaced on each validation, never logged (INV-3; ZLog scrubbers are
 * registered at seal time by the vault). The UI receives display-only views.
 */
class DefaultPairingPort(
    private val parser: PairingLinkParser,
    private val vault: CredentialVault,
    private val gate: AndroidBiometricGate,
    private val transport: RelayTransport,
    private val log: ZLog,
) : PairingPort {

    private var lastValidated: PairingLink? = null

    private val _pairedEndpoint = MutableStateFlow<PairingFlow.LinkView?>(vault.loadMetadata()?.let(::toView))

    override val pairedEndpoint: StateFlow<PairingFlow.LinkView?> = _pairedEndpoint

    override suspend fun validate(raw: String): PairingPort.Validation {
        log.info("pairing.validate.begin", "len=${raw.length} scheme=${raw.substringBefore(":", "").take(12)}")
        val parsed = parser.parse(raw, nowMs = System.currentTimeMillis())
        val link = when (parsed) {
            is PairingLinkParser.Result.Rejected -> {
                log.info("pairing.validate.rejected", parsed.reason.name)
                return PairingPort.Validation.Invalid(reason = parsed.reason.name, detail = rejectionCopy(parsed.reason, parsed.detail))
            }
            is PairingLinkParser.Result.Ready -> parsed.link
        }
        when (val compat = CompatibilityGate.check(link.appVersion)) {
            is CompatibilityGate.Verdict.Blocked -> {
                log.warn("pairing.compat.blocked", "desktop=$${link.appVersion}")
                return PairingPort.Validation.CompatBlocked(version = compat.version, detail = compat.detail)
            }
            is CompatibilityGate.Verdict.AboveTested -> {
                val stale = (parsed as? PairingLinkParser.Result.Ready)?.staleWarning ?: false
                lastValidated = link
                return PairingPort.Validation.Ready(
                    link = toView(link),
                    stale = stale,
                    compatAboveTested = true,
                    requireE7Consent = policyDecision().first,
                    offerBiometricEnrollment = policyDecision().second,
                )
            }
            CompatibilityGate.Verdict.Supported -> Unit
        }
        lastValidated = link
        val (e7, offer) = policyDecision()
        return PairingPort.Validation.Ready(
            link = toView(link),
            stale = (parsed as PairingLinkParser.Result.Ready).staleWarning,
            compatAboveTested = false,
            requireE7Consent = e7,
            offerBiometricEnrollment = offer,
        )
    }

    override suspend fun completePairing(
        link: PairingFlow.LinkView,
        reducedSecurityConsent: Boolean,
    ): PairingPort.ConnectOutcome {
        val raw = lastValidated?.takeIf { it.deviceSid == link.deviceSid }
            ?: return failure("Pairing link expired in memory — re-enter it", rePair = true, retryable = false)
        // E7 consent is REQUIRED before any seal on a no-lock-screen device (INV-14):
        val (e7, _) = policyDecision()
        if (e7 && !reducedSecurityConsent) {
            return failure("Reduced-security consent was not given", rePair = false, retryable = false)
        }
        // Seal (or rotate) the credential — §12.2 step 5: nothing written before all gates.
        log.info("pairing.seal.begin", "sid=${raw.deviceSid.take(8)}…")
        val sealOutcome = vault.seal(raw)
        when (sealOutcome) {
            is SealOutcome.Conflicts -> {
                log.warn("pairing.seal.conflict", "existing=${sealOutcome.existingSid.take(8)}…")
                return failure(
                    "A different desktop is already paired on this device. Unpair it first.",
                    rePair = false,
                    retryable = false,
                )
            }
            is SealOutcome.Rotated -> log.info("pairing.seal.rotated", "same-sid rotation (§12.7) generation=${sealOutcome.metadata.generation}")
            is SealOutcome.Created -> log.info("pairing.seal.created", "endpointId=${sealOutcome.metadata.endpointId}")
        }
        // Keystore gate provision when biometrics are enforceable (§3.3).
        if (gate.capability() == BiometricGatePolicy.Capability.AVAILABLE) {
            gate.provisionGateKey()
        }
        // pairedEndpoint flips ONLY on a successful connect (§12.6): an AUTH-class
        // failure leaves the credential suspect — the user stays on the pairing flow's
        // re-scan state instead of being navigated into the app.
        val outcome = connect(raw)
        log.info(
            "pairing.connect.outcome",
            when (outcome) {
                is PairingPort.ConnectOutcome.Success -> "success"
                is PairingPort.ConnectOutcome.TakenOver -> "takenOver"
                is PairingPort.ConnectOutcome.Failure ->
                    "failure rePair=${outcome.error.rePair} retryable=${outcome.error.retryable} msg=${outcome.error.userMessage.take(80)}"
            },
        )
        if (outcome is PairingPort.ConnectOutcome.Success) {
            _pairedEndpoint.value = toView(raw)
        }
        return outcome
    }

    override suspend fun retry(): PairingPort.ConnectOutcome {
        val meta = vault.loadMetadata()
            ?: return failure("Nothing to connect to — pair first", rePair = true, retryable = false)
        // Origin: the in-flight link when present, else the sealed endpoint identity (§15-S2).
        val origin = lastValidated?.takeIf { it.deviceSid == meta.deviceSid }?.endpointOrigin
            ?: meta.endpointOrigin
        if (origin.isBlank()) {
            return failure("Endpoint origin unknown — pair again", rePair = true, retryable = false)
        }
        val signer = vault.loadSigner()
            ?: return failure("No sealed credential — pair again", rePair = true, retryable = false)
        val endpoint = EndpointDescriptor(
            id = meta.endpointId,
            kind = com.zcode.android.core.transport.EndpointKind.RELAY_ZAI,
            displayName = meta.desktopName,
            wsUrl = wsUrlForOrigin(origin),
            deviceSid = meta.deviceSid,
            deviceMid = meta.deviceMid,
            desktopAppVersion = meta.desktopAppVersion,
        )
        return try {
            transport.connect(endpoint) { nonce, role, sid -> signer.proof(nonce, role, sid) }
            val view = PairingFlow.LinkView(
                deviceSid = meta.deviceSid,
                desktopName = meta.desktopName,
                desktopAppVersion = meta.desktopAppVersion,
                origin = origin,
                issuedAtMs = meta.issuedAtMs,
            )
            _pairedEndpoint.value = view // retry success → paired (§12.6 recovery)
            PairingPort.ConnectOutcome.Success(view)
        } catch (e: com.zcode.android.core.transport.RelayTransport.Failure) {
            mapError(e.error)
        } catch (e: Exception) {
            mapError(ErrorMapping.internal("connect: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    /**
     * Transport reached CONNECTED while the port still reports unpaired — the
     * background self-heal loop connected with the SEALED credential after a
     * retryable pairing failure (e.g. transient bridge/desktop-disconnected).
     * Flip the paired gate so the nav moves the user into the app (§15-S2 shape:
     * the sealed credential, not the in-flight UI attempt, is the pairing truth).
     * No-op unless the vault actually holds a credential (post-wipe CONNECTED
     * must never resurrect pairing).
     */
    fun onTransportConnected() {
        if (_pairedEndpoint.value != null) return
        val meta = vault.loadMetadata() ?: return
        log.info("pairing.resumedFromBackgroundConnect", "sid=${meta.deviceSid.take(6)}…")
        _pairedEndpoint.value = toView(meta)
    }

    override suspend fun unpair() {
        transport.close("unpair")
        gate.wipeGate()
        vault.wipe()
        _pairedEndpoint.value = null
        lastValidated = null
    }

    // ------------------------------------------------------------------ internals

    private suspend fun connect(raw: PairingLink): PairingPort.ConnectOutcome {
        log.info("pairing.connect.begin", "sid=${raw.deviceSid.take(8)}… origin=${raw.endpointOrigin.take(40)}")
        val signer = vault.loadSigner()
            ?: return failure("No sealed credential — pair again", rePair = true, retryable = false)
        val endpoint = EndpointDescriptor(
            id = vault.loadMetadata()?.endpointId ?: raw.deviceSid,
            kind = com.zcode.android.core.transport.EndpointKind.RELAY_ZAI,
            displayName = raw.desktopName,
            wsUrl = wsUrlForOrigin(raw.endpointOrigin),
            deviceSid = raw.deviceSid,
            deviceMid = raw.deviceMid,
            desktopAppVersion = raw.appVersion,
        )
        return try {
            transport.connect(endpoint) { nonce, role, sid -> signer.proof(nonce, role, sid) }
            PairingPort.ConnectOutcome.Success(toView(raw))
        } catch (e: com.zcode.android.core.transport.RelayTransport.Failure) {
            mapError(e.error)
        } catch (e: Exception) {
            mapError(ErrorMapping.internal("connect: ${e.javaClass.simpleName}: ${e.message}"))
        }
    }


    private fun mapError(error: com.zcode.android.core.common.error.AppError): PairingPort.ConnectOutcome {
        val rePair = error.category == com.zcode.android.core.common.error.ErrorCategory.AUTH
        if (error.category == com.zcode.android.core.common.error.ErrorCategory.SESSION &&
            error.autoRecovery == com.zcode.android.core.common.error.RecoveryAction.RECONNECT
        ) {
            // 4009/KICKED class (§15.3) — takeover UX.
            return PairingPort.ConnectOutcome.TakenOver
        }
        return PairingPort.ConnectOutcome.Failure(
            PairingFlow.ErrorView(
                userMessage = error.userMessage,
                diagnostic = error.developerDiagnostic,
                rePair = rePair,
                retryable = !rePair,
            ),
        )
    }

    private fun failure(message: String, rePair: Boolean, retryable: Boolean) = PairingPort.ConnectOutcome.Failure(
        PairingFlow.ErrorView(message, message, rePair, retryable),
    )

    /** (requireE7Consent, offerBiometricEnrollment) per the §3.3 policy, no consent yet. */
    private fun policyDecision(): Pair<Boolean, Boolean> = when (
        BiometricGatePolicy.decide(gate.capability(), BiometricGatePolicy.Consent.NONE)
    ) {
        BiometricGatePolicy.Decision.REQUIRE_GATE -> false to true
        BiometricGatePolicy.Decision.OFFER_OPT_IN -> false to true
        BiometricGatePolicy.Decision.REQUIRE_E7_CONSENT, BiometricGatePolicy.Decision.PROCEED_REDUCED_CONSENT -> true to false
        BiometricGatePolicy.Decision.FAIL_CLOSED -> true to false
    }

    private fun toView(link: PairingLink) = PairingFlow.LinkView(
        deviceSid = link.deviceSid,
        desktopName = link.desktopName,
        desktopAppVersion = link.appVersion,
        origin = link.endpointOrigin,
        issuedAtMs = link.issuedAtMs,
    )

    private fun toView(meta: com.zcode.android.core.security.vault.CredentialMetadata) = PairingFlow.LinkView(
        deviceSid = meta.deviceSid,
        desktopName = meta.desktopName,
        desktopAppVersion = meta.desktopAppVersion,
        origin = "",
        issuedAtMs = meta.issuedAtMs,
    )

    private fun rejectionCopy(reason: PairingLinkParser.Reason, detail: String): String = when (reason) {
        PairingLinkParser.Reason.NOT_A_URL -> detail
        PairingLinkParser.Reason.UNSUPPORTED_ORIGIN -> "The link does not point at a verified ZCode endpoint origin."
        PairingLinkParser.Reason.WRONG_ROUTE -> "The link is not a ZCode remote-control pairing route."
        PairingLinkParser.Reason.MISSING_PARAM -> "The link is missing required pairing parameters."
        PairingLinkParser.Reason.BAD_SID -> "The device id in the link is malformed."
        PairingLinkParser.Reason.BAD_HASH -> "The credential hash in the link is malformed."
        PairingLinkParser.Reason.FUTURE_TIMESTAMP -> "The link timestamp is in the future."
        PairingLinkParser.Reason.DUPLICATE_PARAM -> "The link contains duplicated parameters."
        PairingLinkParser.Reason.OVERSIZED_INPUT -> "The link is implausibly long."
        PairingLinkParser.Reason.BAD_APP_VERSION -> "The desktop version in the link is malformed."
    }
}
