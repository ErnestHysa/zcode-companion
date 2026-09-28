package com.zcode.android.app

import androidx.fragment.app.FragmentActivity
import com.zcode.android.app.pairing.DefaultPairingPort
import com.zcode.android.app.security.FileCredentialStore
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.biometric.AndroidBiometricGate
import com.zcode.android.core.security.pairing.PairingLinkParser
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport

/**
 * AppContainer — manual DI root (§10.1: no framework). Everything credential-adjacent
 * is constructed HERE; the UI never sees any of it except through the port (§26.3).
 * Created once per activity lifetime; the pairing flow survives process death through
 * the sealed credential (§15-S2 full journal is Phase 4/5).
 */
class AppContainer(activity: FragmentActivity) {

    val log: ZLog = ZLog(
        minLevel = ZLog.LogLevel.INFO,
        sink = { entry ->
            android.util.Log.println(
                when (entry.level) {
                    ZLog.LogLevel.DEBUG -> android.util.Log.DEBUG
                    ZLog.LogLevel.INFO -> android.util.Log.INFO
                    ZLog.LogLevel.WARN -> android.util.Log.WARN
                    ZLog.LogLevel.ERROR -> android.util.Log.ERROR
                },
                LOG_TAG,
                "${entry.event}${entry.details?.let { " :: $it" } ?: ""} ${entry.context.entries.joinToString(" ") { "${it.key}=${it.value}" }}",
            )
        },
    )

    val credentialStore = FileCredentialStore(activity)
    val keyWrap = AndroidKeystoreKeyWrap(biometricBound = false)
    val vault = CredentialVault(credentialStore, keyWrap, log)
    val gate = AndroidBiometricGate(activity)
    val transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = log)

    val pairingPort = DefaultPairingPort(
        parser = PairingLinkParser,
        vault = vault,
        gate = gate,
        transport = transport,
        log = log,
    )

    companion object {
        const val LOG_TAG = "ZCODE-PAIR"
    }
}
