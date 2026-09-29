package com.zcode.android.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
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

/**
 * Single-activity root (§18.2). Phase 6 destination: pairing → tasks → conversation
 * (+ connection center / settings), one NavHost, edge-to-edge + predictive back
 * (§18.1). extends FragmentActivity — BiometricPrompt (androidx.biometric) requires it.
 */
class MainActivity : FragmentActivity() {

    private lateinit var container: AppContainer
    private lateinit var pairingVm: PairingViewModel
    private lateinit var tasksVm: TasksViewModel
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        container = AppContainer(this)
        container.log.info("app.lifecycle", "onCreate savedInstanceState=${savedInstanceState != null}")
        enableEdgeToEdge()
        pairingVm = PairingViewModel(container.pairingPort, lifecycleScope)
        tasksVm = TasksViewModel(container.tasksPort, lifecycleScope)
        container.hub.start()
        registerNetworkProbe()
        setContent {
            ZCodeTheme {
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

    override fun onStart() {
        super.onStart()
        container.log.info("app.lifecycle", "onStart")
        container.hub.foregroundTrigger()
    }

    override fun onStop() {
        container.log.info("app.lifecycle", "onStop")
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
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pairingVm.dispatch(if (granted) PairingFlow.Event.CameraPermissionGranted else PairingFlow.Event.CameraPermissionDenied)
        }
        val granted = remember {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        LaunchedEffect(Unit) {
            if (granted) {
                pairingVm.dispatch(PairingFlow.Event.CameraPermissionGranted)
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
        if (pairingVm.state.value is PairingFlow.State.Scanning && granted) {
            QrScanner(lifecycleOwner = this, onDecoded = { raw -> pairingVm.dispatch(PairingFlow.Event.QrDecoded(raw)) })
        }
    }
}
