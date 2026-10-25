package com.zcode.android.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import com.zcode.android.app.theme.applySystemBarsFor
import com.zcode.android.core.ui.theme.resolveDark
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.zcode.android.app.nav.ZCodeApp
import com.zcode.android.app.theme.ZCodeTheme
import com.zcode.android.features.pairing.PairingViewModel
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.tasks.TasksViewModel
import com.zcode.android.features.pairing.ui.QrScanner
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen

/**
 * Single-activity root (§18.2). Phase 6 destination: pairing → tasks → conversation
 * (+ connection center / settings), one NavHost, edge-to-edge + predictive back
 * (§18.1). extends FragmentActivity — BiometricPrompt (androidx.biometric) requires it.
 */
/**
 * C6-taskdesc: the recents card shows the open task's name (the label is the ONE
 * thing the OS recents surface reads), never a generic app label. No-op on a
 * blank label — never a wiped description.
 */
internal fun android.app.Activity.updateRecentsTaskLabel(label: String?) {
    if (label.isNullOrBlank()) return
    @Suppress("DEPRECATION")
    setTaskDescription(android.app.ActivityManager.TaskDescription(label))
}

/**
 * F-PERM (field 2026-10-25): the registry key for the camera-permission launcher.
 * The legacy requestPermissions path validates the key's HASH against the 16-bit
 * request-code contract; Compose-generated slot keys overflowed it, crashing every
 * fresh install at the camera prompt (release + debug, both devices). Any
 * two-character alphanumeric key hashes inside the contract — "zx" → 3902.
 */
internal const val CAMERA_PERMISSION_REGISTRY_KEY = "zx"

class MainActivity : FragmentActivity() {

    // internal: the instrumented suites assert wiring state directly (no product surface).
    internal lateinit var container: AppContainer
    private lateinit var pairingVm: PairingViewModel
    private lateinit var tasksVm: TasksViewModel
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // B3-splash (N4.1): the branded launch window — must run BEFORE super.onCreate
        // so the first visible frame is the splash (never the blank white window).
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // FIELD (2026-09-30, vc12): the ring is persisted line-by-line; capture the
        // CRASH ITSELF (thread + stack) so the export after a relaunch carries the
        // evidence, then delegate to the platform handler. Never throws.
        val platformHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching { container.diagnosticsStore.writeCrash(t.name, e.stackTraceToString()) }
            platformHandler?.uncaughtException(t, e)
        }
        container = AppContainer(this)
        container.log.info("app.lifecycle", "onCreate savedInstanceState=${savedInstanceState != null}")
        // §43.2-11.1: benchmark-only seeding (BuildConfig.BENCHMARK_SEEDING is true
        // ONLY in the benchmark build type) — seals a direct-endpoint credential for
        // the loopback MockZCode the benchmark process runs, so the measured launch
        // is the real §28.1 budget path: cold start → task list (warm cache).
        if (com.zcode.android.app.BuildConfig.BENCHMARK_SEEDING &&
            intent?.getBooleanExtra("zcode.benchmark.seed", false) == true
        ) {
            seedForBenchmark(intent)
        }
        enableEdgeToEdge()
        pairingVm = PairingViewModel(container.pairingPort, lifecycleScope)
        tasksVm = TasksViewModel(container.tasksPort, lifecycleScope)
        container.hub.start()
        // C6 (gate-before-first-frame): hold the splash until §11.6's active-endpoint
        // selection has run — the first composed frame then reads a settled paired
        // gate, so a cold start with an existing pairing never flashes Pairing.
        splash.setKeepOnScreenCondition { !container.hub.gateResolved.value }
        registerNetworkProbe()
        handleDeepLink(intent)
        handleShortcut(intent)
        setContent {
            val windowSize = androidx.compose.material3.windowsizeclass.calculateWindowSizeClass(this)
            // Phase 4F (H1.4-phase1): the theme override applies WITHOUT restart
            // (the pref is a StateFlow read; recomposition follows the toggle).
            val prefs by container.stores.preferencesRepository.observe()
                .collectAsState(com.zcode.android.domain.model.AppPreferences())
            val themeMode = when (prefs.themeMode) {
                com.zcode.android.domain.model.AppPreferences.ThemeMode.LIGHT -> com.zcode.android.core.ui.theme.ThemeMode.LIGHT
                com.zcode.android.domain.model.AppPreferences.ThemeMode.DARK -> com.zcode.android.core.ui.theme.ThemeMode.DARK
                com.zcode.android.domain.model.AppPreferences.ThemeMode.SYSTEM -> com.zcode.android.core.ui.theme.ThemeMode.SYSTEM
            }
            // U7 (verification pass 2): the window's system bars follow the RESOLVED theme.
            // onCreate's enableEdgeToEdge() reads the SYSTEM uiMode once — an in-app Dark
            // override on a light system left dark status icons on the dark background
            // and the light nav scrim under the dark app. Re-applied whenever it flips.
            val dark = themeMode.resolveDark()
            LaunchedEffect(dark) { applySystemBarsFor(dark) }
            ZCodeTheme(themeMode = themeMode) {
                androidx.compose.runtime.CompositionLocalProvider(
                    com.zcode.android.core.ui.components.LocalWindowWidthClass provides windowSize.widthSizeClass,
                ) {
                ZCodeApp(
                    container = container,
                    pairingVm = pairingVm,
                    tasksVm = tasksVm,
                    scope = lifecycleScope,
                    scannerContent = { ScannerWithPermission() },
                )
                }
            }
        }
    }

    /**
     * §11.3/§12.4 benchmark seeding path: identical storage writes as the real
     * add-direct flow (Tink-sealed token + registry row + persisted selection),
     * fed from the benchmark intent extras (mock-only, INV-15).
     */
    private fun seedForBenchmark(intent: Intent) {
        val url = intent.getStringExtra("zcode.benchmark.url") ?: return
        val token = intent.getStringExtra("zcode.benchmark.token") ?: return
        val name = intent.getStringExtra("zcode.benchmark.name") ?: "BenchBook"
        runCatching {
            val stores = container.stores
            val vault = com.zcode.android.core.security.vault.CredentialVault(
                stores.credentialStore,
                com.zcode.android.core.security.vault.AndroidKeystoreKeyWrap(biometricBound = false),
                container.log.let { com.zcode.android.core.common.log.ZLog(sink = {}) },
            )
            val endpointId = "benchmark-direct-endpoint"
            vault.sealDirectToken(
                token,
                com.zcode.android.core.security.vault.DirectEndpointMetadata(
                    endpointId = endpointId,
                    displayName = name,
                    wsUrl = url,
                    serverId = "benchmark-mock",
                    serverVersion = "3.14.3",
                    addedAtMs = System.currentTimeMillis(),
                ),
            )
            kotlinx.coroutines.runBlocking {
                stores.endpointRepository.save(
                    com.zcode.android.domain.model.Endpoint(
                        endpointId = com.zcode.android.domain.ids.EndpointId(endpointId),
                        kind = com.zcode.android.domain.model.EndpointKind.DIRECT_SERVER,
                        displayName = name,
                        wsUrl = url,
                        pairedAtMs = System.currentTimeMillis(),
                    ),
                )
                stores.preferencesRepository.update {
                    it.copy(selectedEndpointId = endpointId)
                }
            }
            container.log.info("benchmark.seed", "seeded endpoint url=${url.take(40)}")
        }.onFailure { container.log.warn("benchmark.seedFailed", it.javaClass.simpleName) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // §24.7 singleTask: a notification tap re-enters the LIVE activity here.
        container.log.info("app deeplink", "onNewIntent data=${intent.data?.toString()?.take(120)}")
        handleDeepLink(intent)
        handleShortcut(intent)
    }

    /** §24.7: a deep link is a routing HINT — parse here, the nav graph resolves. */
    /** H2.2: launcher shortcuts ride the notifications seam like deep links. */
    internal fun handleShortcut(intent: Intent?) {
        val target = com.zcode.android.app.notifications.AppShortcuts.intake(intent) ?: return
        container.log.info("app.shortcut", "target=${target.id}")
        container.notifications.offerShortcut(target)
    }

    internal fun handleDeepLink(intent: Intent?) {
        val candidate = com.zcode.android.app.notifications.DeepLinks.intake(intent) ?: return
        container.notifications.offerDeepLink(candidate.taskId, candidate.interactionId)
    }

    override fun onStart() {
        super.onStart()
        container.log.info("app.lifecycle", "onStart")
        container.hub.foregroundTrigger()
        // §9.9/Phase 8: the broker suppresses while foregrounded and re-posts still-
        // open attention items on the next background entry.
        container.notifications.onForeground()
    }

    override fun onStop() {
        container.log.info("app.lifecycle", "onStop")
        container.notifications.onBackground()
        super.onStop()
    }

    override fun onDestroy() {
        container.log.info("app.lifecycle", "onDestroy isFinishing=$isFinishing")
        networkCallback?.let { cb ->
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
        }
        super.onDestroy()
    }

    /** §15-S3/S4: foreground wake + network-restored probes skip the backoff. */
    private fun registerNetworkProbe() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                container.log.info("network.available", "network=$network")
                container.hub.networkAvailable()
            }

            override fun onLost(network: Network) {
                container.log.info("network.lost", "network=$network")
            }
        }
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                callback,
            )
            networkCallback = callback
        }
    }

    @Composable
    private fun ScannerWithPermission() {
        // FIELD FIX (2026-09-30, camera dead after first-time grant): `granted` was a
        // plain remember{} (frozen at composition) and the pairing state was read via
        // .value (unsubscribed) — after the user granted the permission NOTHING
        // recomposed, so the scanner never appeared until a full app restart.
        var granted by androidx.compose.runtime.remember {
            androidx.compose.runtime.mutableStateOf(
                ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
            )
        }
        val state by pairingVm.state.collectAsState()
        // F-PERM: stable, 16-bit-safe registry key (see CAMERA_PERMISSION_REGISTRY_KEY).
        val registry = androidx.activity.compose.LocalActivityResultRegistryOwner.current!!.activityResultRegistry
        val launcher = remember(registry) {
            registry.register(
                CAMERA_PERMISSION_REGISTRY_KEY,
                ActivityResultContracts.RequestPermission(),
            ) { g ->
                granted = g
                pairingVm.dispatch(if (g) PairingFlow.Event.CameraPermissionGranted else PairingFlow.Event.CameraPermissionDenied)
            }
        }
        androidx.compose.runtime.DisposableEffect(launcher) { onDispose { launcher.unregister() } }
        LaunchedEffect(Unit) {
            if (granted) {
                pairingVm.dispatch(PairingFlow.Event.CameraPermissionGranted)
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
        if (state is PairingFlow.State.Scanning && granted) {
            QrScanner(lifecycleOwner = this, onDecoded = { raw -> pairingVm.dispatch(PairingFlow.Event.QrDecoded(raw)) })
        }
    }
}
