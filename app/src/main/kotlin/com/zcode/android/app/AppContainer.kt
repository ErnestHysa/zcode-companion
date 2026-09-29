package com.zcode.android.app

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.zcode.android.app.connect.ConnectionHub
import com.zcode.android.app.connect.DefaultConversationPort
import com.zcode.android.app.connect.DefaultTasksPort
import com.zcode.android.app.pairing.DefaultPairingPort
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
 *
 * Phase 4 (§43.2): the §12.4 credential-envelope binding is the DataStore-proto store
 * (ADR-008); Room/DataStore singletons live in the process-scoped [AppStores] holder.
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

    val credentialStore = AppStores.get(activity).credentialStore
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

    /** Phase 4 persistence (§43.2): process-scoped Room + DataStore stores/repositories. */
    val stores: AppStores = AppStores.get(activity)

    /**
     * Phase 6 (§43.2-6.1): app-level connection lifecycle — post-pairing connect,
     * bootstrap → task projection, conversation session attachment. The feature
     * ports below are the ONLY surfaces the UI sees (INV-2/INV-6).
     */
    val hub: ConnectionHub = ConnectionHub(
        transport = transport,
        vault = vault,
        taskRepo = stores.taskRepository,
        conversationRepo = stores.conversationRepository,
        journalRepo = stores.journalRepository,
        interactions = stores.interactionRepository,
        endpointRepo = stores.endpointRepository,
        scope = activity.lifecycleScope,
        log = log,
    )

    val tasksPort: DefaultTasksPort = DefaultTasksPort(hub, vault, stores.taskRepository)
    val conversationPort: DefaultConversationPort = DefaultConversationPort(hub, stores.conversationRepository)


    companion object {
        const val LOG_TAG = "ZCODE-PAIR"
    }
}
