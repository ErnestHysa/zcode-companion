package com.zcode.android.app

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.launch
import com.zcode.android.app.connect.ConnectionHub
import com.zcode.android.app.diagnostics.DiagnosticsFileStore
import com.zcode.android.app.connect.DefaultConversationPort
import com.zcode.android.app.connect.DefaultFilesPort
import com.zcode.android.app.connect.DefaultGitPort
import com.zcode.android.app.connect.DefaultTerminalPort
import com.zcode.android.app.connect.DefaultTasksPort
import com.zcode.android.app.pairing.DefaultPairingPort
import com.zcode.android.app.notifications.NotificationWiring
import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.security.biometric.AndroidBiometricGate
import com.zcode.android.core.security.pairing.PairingLinkParser
import com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap
import com.zcode.android.core.security.vault.CredentialVault
import com.zcode.android.core.transport.OkHttpWsFactory
import com.zcode.android.core.transport.RelayTransport
import com.zcode.android.core.notifications.NotificationChannels

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

    /**
     * Round-7b: the ring is PERSISTED across process restarts (redacted lines only).
     * The field workflow "first launch fails → force-close → reopen" previously lost
     * the failing session's diagnostics entirely.
     */
    val diagnosticsStore: DiagnosticsFileStore = DiagnosticsFileStore.inDir(
        File(activity.cacheDir, "diagnostics"),
    ).also { it.rotateOnStartup() }

    val log: ZLog = ZLog(
        // Round-7 diagnostics enhancement: capture DEBUG (protocol frames, gateway
        // routing, coordinator internals) into a 5,000-entry ring — ~10× the field
        // visibility, still fully redacted by the centralized scrubbers (INV-3) and
        // bounded (INV-5). The DEBUG level is the transport/coordinator's native
        // verbosity; INFO-only hid exactly the stages field debugging needed.
        minLevel = ZLog.LogLevel.DEBUG,
        ringCapacity = 5_000,
        sink = { entry ->
            val ctx = entry.context.entries.joinToString(" ") { "${it.key}=${it.value}" }
            val line = "${entry.timestampMs} [${entry.level}] ${entry.event}" +
                (entry.details?.let { " :: $it" } ?: "") +
                (if (ctx.isEmpty()) "" else " $ctx")

            android.util.Log.println(
                when (entry.level) {
                    ZLog.LogLevel.DEBUG -> android.util.Log.DEBUG
                    ZLog.LogLevel.INFO -> android.util.Log.INFO
                    ZLog.LogLevel.WARN -> android.util.Log.WARN
                    ZLog.LogLevel.ERROR -> android.util.Log.ERROR
                },
                LOG_TAG,
                line,
            )
            // Persist (redacted, formatted) across restarts — the field-workflow fix.
            diagnosticsStore.append(line)
        },
    )

    init {
        val pm = activity.packageManager.getPackageInfo(activity.packageName, 0)
        log.info(
            "app.start",
            "version=${pm.versionName} (vc${pm.longVersionCode}) " +
                "model=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} " +
                "api=${android.os.Build.VERSION.SDK_INT}",
        )
    }

    val credentialStore = AppStores.get(activity).credentialStore
    val keyWrap = AndroidKeystoreKeyWrap(biometricBound = false)
    val vault = CredentialVault(credentialStore, keyWrap, log)
    val gate = AndroidBiometricGate(activity)
    val transport = RelayTransport(wsFactory = OkHttpWsFactory(), log = log)

    /** Phase 10 (§11.4): the DIRECT transport — token capability bound per active endpoint. */
    val directTransport = com.zcode.android.core.transport.DirectTransport(
        tokenProvider = com.zcode.android.core.transport.TokenProvider { "" },
        wsFactory = OkHttpWsFactory(),
        log = log,
    )

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
        directTransport = directTransport,
        vault = vault,
        taskRepo = stores.taskRepository,
        conversationRepo = stores.conversationRepository,
        journalRepo = stores.journalRepository,
        interactions = stores.interactionRepository,
        endpointRepo = stores.endpointRepository,
        preferences = stores.preferencesRepository,
        outbox = stores.outboxRepository,
        drafts = stores.draftRepository,
        scope = activity.lifecycleScope,
        log = log,
    )

    init {
        // Persist a structured auto-report at the moment of Degraded (round-7e).
        hub.onDegradedReport = { text -> diagnosticsStore.appendAutoBlock("DEGRADED", text) }
    }

    val tasksPort: DefaultTasksPort = DefaultTasksPort(hub, vault, stores.taskRepository, rePair = {
        // Unpair is suspend (vault wipe + transport close) — launch on the activity scope.
        activity.lifecycleScope.launch {
            pairingPort.unpair()
            hub.onUnpaired()
        }
    })
    val conversationPort: DefaultConversationPort = DefaultConversationPort(
        hub,
        stores.conversationRepository,
        vault,
        attachmentCacheDir = java.io.File(activity.cacheDir, "attachments").also { it.mkdirs() },
    )

    /** Phase 9 (§43.2-9): the advanced read-only surfaces + view-only terminal. */
    val gitPort: DefaultGitPort = DefaultGitPort(hub)
    val filesPort: DefaultFilesPort = DefaultFilesPort(
        hub,
        cacheDir = java.io.File(activity.cacheDir, "downloads").also { it.mkdirs() },
        onShare = { file, mime ->
            // §23.4 share-out via FileProvider (app-private storage only, INV-16).
            runCatching {
                val shareUri = androidx.core.content.FileProvider.getUriForFile(
                    activity,
                    activity.packageName + ".fileprovider",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(android.content.Intent.createChooser(intent, "Share file"))
            }.onFailure { log.warn("files.share", "${it.javaClass.simpleName}: ${it.message?.take(100)}") }
        },
    )
    val terminalPort: DefaultTerminalPort = DefaultTerminalPort(
        controller = com.zcode.android.data.session.TerminalController(hub.activeTransport(), log),
        preferences = stores.preferencesRepository,
        log = log,
    )

    /**
     * Phase 8 (§43.2-8.1): the §24.1 broker wiring — the sole notification decision
     * point fed by the authoritative repositories (interactions, task projection,
     * connection phase) plus the §24.7 deep-link intake. Channels are created at
     * first run here (§24.2); the FGS/worker hooks bind the app identity.
     */
    val notifications: NotificationWiring = NotificationWiring(
        stores = stores,
        broker = stores.notificationBroker,
        phase = hub.phase,
        endpointFlow = hub.endpoint,
        scope = stores.appScope,
        log = log,
    )

    init {
        NotificationChannels.ensure(activity)
        notifications.start()
        // §43.2-11.4: the widget is a broker-state consumer (event-driven updates).
        com.zcode.android.app.widget.PendingApprovalsWidgetUpdater(
            context = activity.applicationContext,
            broker = stores.notificationBroker,
            scope = stores.appScope,
        ).start()
        // §15-S12: the recovery coordinator's reconciliation port is the broker.
        hub.notificationRecords = notifications.recordsPort()
        // §24.5/§25.2: the FGS notification names the paired desktop; the tap target
        // is the launcher activity. The worker hook is the §25.3 process bridge.
        com.zcode.android.core.notifications.LiveConnectionService.endpointLabelProvider = {
            vault.loadMetadata()?.desktopName
                ?: activity.getString(com.zcode.android.core.notifications.R.string.fgs_default_desktop)
        }
        com.zcode.android.core.notifications.LiveConnectionService.LAUNCHER_ACTIVITY = MainActivity::class.java
        com.zcode.android.core.notifications.ReconnectProbeWorker.ProbeHook.probe = {
            hub.networkAvailable()
        }
    }

    init {
        // Background self-heal success → flip the pairing gate (the QR flow's connect
        // may have failed retryably; the recovery loop connecting later IS pairing).
        hub.onConnected = { pairingPort.onTransportConnected() }
    }


    companion object {
        const val LOG_TAG = "ZCODE-PAIR"
    }
}
