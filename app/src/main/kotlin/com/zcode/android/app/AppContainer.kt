package com.zcode.android.app

import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.flow.collect
import java.io.File
import kotlinx.coroutines.launch
import com.zcode.android.app.connect.ConnectionHub
import com.zcode.android.app.diagnostics.DiagnosticsFileStore
import com.zcode.android.app.connect.DefaultCodingPlanPort
import com.zcode.android.app.connect.DefaultConversationPort
import com.zcode.android.app.connect.DefaultFilesPort
import com.zcode.android.app.connect.DefaultGitPort
import com.zcode.android.app.connect.DefaultAutomationsPort
import com.zcode.android.app.connect.DefaultPluginsPort
import com.zcode.android.app.connect.DefaultBotsPort
import com.zcode.android.app.connect.DefaultProvidersPort
import com.zcode.android.app.connect.DefaultSubagentsPort
import com.zcode.android.app.connect.DefaultMcpPort
import com.zcode.android.app.connect.DefaultHooksPort
import com.zcode.android.app.connect.DefaultCommandsPort
import com.zcode.android.app.connect.DefaultMemoryPort
import com.zcode.android.app.connect.DefaultSkillsPort
import com.zcode.android.app.connect.DefaultUsagePort
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
 *
 * F-33-A (vc52 Phase 8): ONE container per PROCESS ([forProcess]) — it used to be built
 * per Activity, so after any configuration-change recreate the retained conversation VM
 * kept driving the DESTROYED container's port while the hub's scope (the old Activity's
 * lifecycleScope) was cancelled: the zombie screen (sends dead, status still "Connected").
 * Everything here now hangs off the APPLICATION context and the process [processScope];
 * the few surfaces that genuinely need an Activity (BiometricPrompt, the share/install
 * chooser) read the CURRENT host through a weak reference ([attachHost]/[detachHost]) —
 * the container never retains a destroyed Activity (F-33-B).
 *
 * Phase 4 (§43.2): the §12.4 credential-envelope binding is the DataStore-proto store
 * (ADR-008); Room/DataStore singletons live in the process-scoped [AppStores] holder.
 */
class AppContainer(context: Context) {

    /** F-33-A: the APPLICATION context — the container outlives every Activity. */
    internal val appContext: Context = context.applicationContext

    /**
     * F-33-A: the container's own process-lifetime scope. Main.immediate is the SAME
     * dispatcher the hub ran on when it borrowed an Activity's lifecycleScope — only the
     * lifetime changed: a recreate no longer cancels the hub's collectors.
     */
    val processScope: kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() +
            kotlinx.coroutines.Dispatchers.Main.immediate +
            kotlinx.coroutines.CoroutineName("zcode-app-container"),
    )

    @Volatile private var hostRef: java.lang.ref.WeakReference<FragmentActivity>? = null

    /** The Activity currently hosting the UI (onCreate binds it, onDestroy releases it). */
    fun attachHost(activity: FragmentActivity) {
        hostRef = java.lang.ref.WeakReference(activity)
    }

    fun detachHost(activity: FragmentActivity) {
        if (hostRef?.get() === activity) hostRef = null
    }

    /** The live host, or null between Activities (never a destroyed/finishing one). */
    internal fun host(): FragmentActivity? =
        hostRef?.get()?.takeUnless { it.isDestroyed || it.isFinishing }

    /** Starts [intent] from the live host, else from the application (as a new task). */
    private fun startFromHost(intent: android.content.Intent) {
        val host = host()
        if (host != null) {
            host.startActivity(intent)
        } else {
            appContext.startActivity(intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Test-only: a replaced process container (a fresh Application) stops its own work. */
    private fun retire() {
        processScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    init {
        // Phase 4E (C3): the shade Approve/Deny actions answer through the SAME
        // fail-closed inbox gate; the scope is the app-level stores scope (the
        // receiver outlives any screen).
        com.zcode.android.app.notifications.NotificationActionReceiver.answerDelegate = { interactionId, optionId, _ ->
            stores.appScope.launch {
                runCatching { conversationPort.answerFromInbox(interactionId, optionId, null) }
                    .onFailure { log.warn("notif.action", "${it.javaClass.simpleName}: ${it.message?.take(120)}") }
            }
        }
        // F-37-B: a shade decision that arrived while the device was locked was discarded.
        com.zcode.android.app.notifications.NotificationActionReceiver.lockedRefusalDelegate = { interactionId ->
            log.info("notif.action", "discarded: device locked interaction=${interactionId.take(12)}…")
        }
    }

    /**
     * Round-7b: the ring is PERSISTED across process restarts (redacted lines only).
     * The field workflow "first launch fails → force-close → reopen" previously lost
     * the failing session's diagnostics entirely.
     */
    val diagnosticsStore: DiagnosticsFileStore = DiagnosticsFileStore.inDir(
        File(appContext.cacheDir, "diagnostics"),
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
        val pm = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
        log.info(
            "app.start",
            "version=${pm.versionName} (vc${pm.longVersionCode}) " +
                "model=${android.os.Build.MANUFACTURER}/${android.os.Build.MODEL} " +
                "api=${android.os.Build.VERSION.SDK_INT}",
        )
    }

    val credentialStore = AppStores.get(appContext).credentialStore
    val keyWrap = AndroidKeystoreKeyWrap(biometricBound = false)
    val vault = CredentialVault(credentialStore, keyWrap, log)
    val gate = AndroidBiometricGate(appContext, hostActivity = ::host)
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
    val stores: AppStores = AppStores.get(appContext)

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
        scope = processScope,
        log = log,
    )

    init {
        // Persist a structured auto-report at the moment of Degraded (round-7e).
        hub.onDegradedReport = { text -> diagnosticsStore.appendAutoBlock("DEGRADED", text) }
    }

    val tasksPort: DefaultTasksPort = DefaultTasksPort(hub, vault, stores.taskRepository, rePair = {
        // Unpair is suspend (vault wipe + transport close) — launch on the process scope.
        processScope.launch {
            pairingPort.unpair()
            hub.onUnpaired()
        }
    })
    val conversationPort: DefaultConversationPort = DefaultConversationPort(
        hub,
        stores.conversationRepository,
        vault,
        attachmentCacheDir = java.io.File(appContext.cacheDir, "attachments").also { it.mkdirs() },
    )

    /** Phase 9 (§43.2-9): the advanced read-only surfaces + view-only terminal. */
    val gitPort: DefaultGitPort = DefaultGitPort(hub)

    /** Phase 3 viewers (GAP-5..9; v4-wire.md §12): read-only §13.3-sanctioned reads. */
    val usagePort: DefaultUsagePort = DefaultUsagePort(hub)
    val automationsPort: DefaultAutomationsPort = DefaultAutomationsPort(hub)
    val pluginsPort: DefaultPluginsPort = DefaultPluginsPort(hub)
    val skillsPort: DefaultSkillsPort = DefaultSkillsPort(hub)
    val subagentsPort: DefaultSubagentsPort = DefaultSubagentsPort(hub)
    val providersPort: DefaultProvidersPort = DefaultProvidersPort(hub)
    val botsPort: DefaultBotsPort = DefaultBotsPort(hub)
    val mcpPort: DefaultMcpPort = DefaultMcpPort(hub)
    val hooksPort: DefaultHooksPort = DefaultHooksPort(hub)
    val commandsPort: DefaultCommandsPort = DefaultCommandsPort(hub)

    /** Phase 4 (GAP-3/4; ADR-020, v4-wire.md §13): the coding-plan quota plane. */
    val quotaPort: DefaultCodingPlanPort = DefaultCodingPlanPort(hub)

    /**
     * GAP-14 (android-gaps roadmap): per-workspace task-group collapse state,
     * PERSISTED across process restarts (desktop collapsedGroupIds parity).
     */
    val workspaceCollapseStore: com.zcode.android.features.tasks.ui.WorkspaceCollapseFileStore =
        com.zcode.android.features.tasks.ui.WorkspaceCollapseFileStore(
            java.io.File(appContext.filesDir, "ui/workspace-collapse.txt"),
        )
    val filesPort: DefaultFilesPort = DefaultFilesPort(
        hub,
        cacheDir = java.io.File(appContext.cacheDir, "downloads").also { it.mkdirs() },
        onShare = { file, mime ->
            // §23.4 share-out via FileProvider (app-private storage only, INV-16).
            runCatching {
                val shareUri = androidx.core.content.FileProvider.getUriForFile(
                    appContext,
                    appContext.packageName + ".fileprovider",
                    file,
                )
                val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                    type = mime
                    putExtra(android.content.Intent.EXTRA_STREAM, shareUri)
                    addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startFromHost(android.content.Intent.createChooser(intent, "Share file"))
            }.onFailure { log.warn("files.share", "${it.javaClass.simpleName}: ${it.message?.take(100)}") }
        },
    )
    val memoryPort: DefaultMemoryPort = DefaultMemoryPort(hub, filesPort)
    val terminalPort: DefaultTerminalPort = DefaultTerminalPort(
        controller = com.zcode.android.data.session.TerminalController(hub.activeTransport(), log),
        preferences = stores.preferencesRepository,
        log = log,
    )

    /**
     * AAP-3 (android-gaps roadmap; §14.2 T4, §37): the foreground GitHub-Releases
     * update check. The connection center triggers it on entry (no polling); the
     * gate is `desktop.version > CompatibilityGate.MAX_TESTED_DESKTOP` AND a newer
     * release (versionCode) exists. Install is the user-consented system-installer
     * handoff — never silent (§37).
     */
    val appUpdate: com.zcode.android.app.update.AppUpdateController =
        com.zcode.android.app.update.AppUpdateController(
            checker = com.zcode.android.app.update.AppUpdateChecker(
                releasesUrl = "https://api.github.com/repos/$UPDATE_RELEASE_REPO/releases/latest",
                installedVersionCode = run {
                    val pm = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
                    pm.longVersionCode
                },
            ),
            downloader = com.zcode.android.app.update.ApkDownloader(
                http = okhttp3.OkHttpClient(),
                updatesDir = File(appContext.cacheDir, "updates").also { it.mkdirs() },
                log = log,
            ),
            scope = processScope,
            log = log,
            installer = { apk ->
                com.zcode.android.app.update.AppUpdateController.ensureUpdatesChannel(appContext)
                // The installer intent carries FLAG_ACTIVITY_NEW_TASK — the host (when
                // present) keeps the system installer in the app's own task.
                com.zcode.android.app.update.ApkInstaller.install(host() ?: appContext, apk)
            },
            // G43-P24: the skip marker gates the availability notification (the
            // banner gate composes UpdateSkipGate at the Connection screen).
            isVersionSkipped = { vc ->
                // The gate is called from the controller's own scope (suspend-safe).
                runCatching { stores.preferencesRepository.current().skippedUpdateVersionCode == vc }
                    .getOrDefault(false)
            },
            // G43-P24: the accept moment — record the release for the What's-new
            // sheet (shown once when this build IS that version). The record write
            // rides the process scope (the hook itself is called from updateNow's IO).
            onUpdateAccepted = { release ->
                processScope.launch {
                    runCatching {
                        stores.preferencesRepository.update { p ->
                            p.copy(
                                whatsNewVersionCode = release.versionCode,
                                whatsNewVersionName = release.versionName,
                                whatsNewNotes = release.notes,
                                whatsNewShown = false,
                                // Installing supersedes a stale skip.
                                skippedUpdateVersionCode = null,
                            )
                        }
                    }
                }
            },
            notify = { title, body ->
                com.zcode.android.app.update.AppUpdateController.ensureUpdatesChannel(appContext)
                val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                val n = NotificationCompat.Builder(appContext, com.zcode.android.app.update.AppUpdateController.UPDATES_CHANNEL)
                    .setSmallIcon(android.R.drawable.stat_sys_download_done)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setContentIntent(
                        android.app.PendingIntent.getActivity(
                            appContext,
                            0,
                            android.content.Intent(appContext, MainActivity::class.java)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                        ),
                    )
                    .setAutoCancel(true)
                    .build()
                if (manager != null) runCatching { manager.notify(NOTIF_ID_UPDATE, n) }
            },
        )

    init {
        // FIELD (2026-10-12): the update check runs on EVERY app open + endpoint
        // change (throttled in the controller) and posts a system notification when
        // a newer release exists — no longer gated on the desktop version.
        // LEDGER-SMALLS (c): gated through UpdateCheckGate so the paired app skips the
        // vault-SEED emission that raced the bootstrap (update.check desktop=null).
        processScope.launch {
            kotlinx.coroutines.flow.combine(hub.endpoint, hub.phase) { endpoint, phase ->
                endpoint to phase
            }.collect { (endpoint, phase) ->
                if (com.zcode.android.app.update.UpdateCheckGate.shouldFire(endpoint, phase)) {
                    appUpdate.checkForUpdate(endpoint?.desktopAppVersion)
                }
            }
        }
    }

    /**
     * Phase 8 (§43.2-8.1): the §24.1 broker wiring — the sole notification decision
     * point fed by the authoritative repositories (interactions, task projection,
     * connection phase) plus the §24.7 deep-link intake. Channels are created at
     * first run here (§24.2); the FGS/worker hooks bind the app identity.
     */
    /**
     * G78 (vc52 Phase 11): the pending code-viewer line comment — the Files screen
     * offers it, the conversation route consumes it (prefillDraft on entry) and
     * clears it exactly once. One-slot hand-off (the PendingDeepLink pattern).
     */
    val pendingLineComment = kotlinx.coroutines.flow.MutableStateFlow<LineComment?>(null)

    /**
     * G66 (vc52 Phase 13): the error card's "Report issue" one-shot prefill — the
     * raw error rides to the Settings feedback form (the pendingLineComment
     * pattern: the route layer sets it, Settings consumes it exactly once).
     */
    val pendingFeedbackPrefill = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    data class LineComment(val taskId: String, val quoted: String)

    // G63 (vc52 Phase 13): idle-time outcome notifications — the unfiltered
    // off-peak list diffed on every CONNECTED transition, gated by notifyTaskOutcomes.
    val offPeakOutcomeNotifier = com.zcode.android.app.notifications.OffPeakOutcomeNotifier(
        broker = stores.notificationBroker,
        provider = { hub.viewerTools().offPeakTaskRowsAll() },
        outcomesEnabled = { stores.preferencesRepository.current().notifyTaskOutcomes },
        log = log,
    )

    val notifications: NotificationWiring = NotificationWiring(
        stores = stores,
        broker = stores.notificationBroker,
        phase = hub.phase,
        endpointFlow = hub.endpoint,
        scope = stores.appScope,
        log = log,
        offPeakNotifier = offPeakOutcomeNotifier,
    )

    init {
        NotificationChannels.ensure(appContext)
        notifications.start()
        // §43.2-11.4: the widget is a broker-state consumer (event-driven updates).
        com.zcode.android.app.widget.PendingApprovalsWidgetUpdater(
            context = appContext,
            broker = stores.notificationBroker,
            scope = stores.appScope,
        ).start()
        // §15-S12: the recovery coordinator's reconciliation port is the broker.
        hub.notificationRecords = notifications.recordsPort()
        // §24.5/§25.2: the FGS notification names the paired desktop; the tap target
        // is the launcher activity. The worker hook is the §25.3 process bridge.
        // F-33-B: the provider reads the SERVICE context it is handed — it used to close
        // over the creating Activity (one more retention path per recreate).
        com.zcode.android.core.notifications.LiveConnectionService.endpointLabelProvider = { ctx ->
            vault.loadMetadata()?.desktopName
                ?: ctx.getString(com.zcode.android.core.notifications.R.string.fgs_default_desktop)
        }
        com.zcode.android.core.notifications.LiveConnectionService.LAUNCHER_ACTIVITY = MainActivity::class.java
        // F-34-F: the Live-connection notification tracks the LIVE phase (it was built
        // once at service start and read "Connected" through ten minutes of DEGRADED).
        com.zcode.android.core.notifications.LiveConnectionService.phaseSource = hub.phase
        com.zcode.android.core.notifications.ReconnectProbeWorker.ProbeHook.probe = {
            hub.networkAvailable()
        }
    }

    init {
        // Background self-heal success → flip the pairing gate (the QR flow's connect
        // may have failed retryably; the recovery loop connecting later IS pairing).
        hub.onConnected = { pairingPort.onTransportConnected() }
    }

    init {
        registerNetworkProbe()
    }

    /**
     * §15-S3/S4: network-restored probe → skip the backoff. F-33-B: a PROCESS concern,
     * registered ONCE on the application context — it was registered per Activity as an
     * anonymous object closing over the Activity (`this$0`), one more path that pinned
     * recreated Activities; and between Activities (the FGS-only process) there was none.
     */
    private fun registerNetworkProbe() {
        val cm = appContext.getSystemService(android.net.ConnectivityManager::class.java) ?: return
        val callback = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) {
                log.info("network.available", "network=$network")
                hub.networkAvailable()
            }

            override fun onLost(network: android.net.Network) {
                log.info("network.lost", "network=$network")
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder()
                    .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }.onFailure { log.warn("network.probe", "register failed: ${it.javaClass.simpleName}") }
    }


    companion object {
        const val LOG_TAG = "ZCODE-PAIR"

        /** §37 primary channel — the GitHub repo whose Releases carry the APK. */
        const val UPDATE_RELEASE_REPO = "ErnestHysa/zcode-companion"

        private const val NOTIF_ID_UPDATE = 4102

        @Volatile
        private var process: AppContainer? = null

        /**
         * F-33-A: THE process container. Built once per Application instance — once per
         * process in production; a Robolectric test's fresh Application gets a fresh one
         * (the replaced container stops its own work). Every Activity binds to it.
         */
        fun forProcess(context: Context): AppContainer {
            val app = context.applicationContext
            process?.let { if (it.appContext === app) return it }
            return synchronized(this) {
                process?.takeIf { it.appContext === app } ?: AppContainer(app).also { fresh ->
                    process?.retire()
                    process = fresh
                    ProcessCrashCapture.install { process?.diagnosticsStore }
                }
            }
        }
    }
}

/**
 * F-33-B (FIELD 2026-09-30, vc12 — the crash capture itself): the ring is persisted
 * line-by-line; capture the CRASH (thread + stack) so the export after a relaunch carries
 * the evidence, then delegate to the platform handler. Never throws.
 *
 * Installed ONCE per process: it used to be installed in EVERY MainActivity.onCreate,
 * chaining the previous handler and closing over `this` — the heap-proven path that kept
 * every recreated Activity and its whole container alive. The handler reads the PROCESS
 * container's diagnostics store through [store]; it never references an Activity.
 */
internal object ProcessCrashCapture {
    private val installed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun install(store: () -> DiagnosticsFileStore?) {
        if (!installed.compareAndSet(false, true)) return
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { store()?.writeCrash(t.name, e.stackTraceToString()) }
            platformHandler?.uncaughtException(t, e)
        }
    }
}
