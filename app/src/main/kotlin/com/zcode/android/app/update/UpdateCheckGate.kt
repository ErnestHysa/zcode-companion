package com.zcode.android.app.update

import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.state.ConnectionPhase

/**
 * LEDGER-SMALLS (c, vc51 Phase 4A): the app-open update check used to fire on the
 * vault-SEED endpoint emission — before the connect bootstrap patched
 * `desktopAppVersion` — producing the recorded first-launch `update.check desktop=null`
 * race (it self-healed on the next emission, but the pre-bootstrap check was a wasted
 * network round trip). The gate: a PAIRED app fires only once its connection has
 * SETTLED (CONNECTED — the bootstrap/caps have applied); an UNPAIRED app fires
 * immediately (nothing to bootstrap; the 2026-10-12 "check on every app open" contract
 * holds — `force` from the Settings row bypasses this gate entirely).
 */
object UpdateCheckGate {
    fun shouldFire(endpoint: Endpoint?, phase: ConnectionPhase): Boolean =
        endpoint == null || phase == ConnectionPhase.CONNECTED
}
