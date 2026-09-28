package com.zcode.android.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.zcode.android.app.theme.ZCodeTheme
import com.zcode.android.features.pairing.PairingViewModel
import com.zcode.android.features.pairing.flow.PairingFlow
import com.zcode.android.features.pairing.ui.PairingHost
import com.zcode.android.features.pairing.ui.QrScanner

/**
 * Single-activity root (§18.2). Phase 3 destination: the pairing flow only.
 * extends FragmentActivity — BiometricPrompt (androidx.biometric) requires it.
 */
class MainActivity : FragmentActivity() {

    private lateinit var vm: PairingViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val container = AppContainer(this)
        vm = PairingViewModel(container.pairingPort, lifecycleScope)
        val view = ComposeView(this)
        setContentView(view)
        view.setContent {
            ZCodeTheme {
                PairingHost(vm, scannerContent = { ScannerWithPermission() })
            }
        }
    }

    @Composable
    private fun ScannerWithPermission() {
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            vm.dispatch(if (granted) PairingFlow.Event.CameraPermissionGranted else PairingFlow.Event.CameraPermissionDenied)
        }
        val granted = remember {
            ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        }
        LaunchedEffect(Unit) {
            if (granted) {
                vm.dispatch(PairingFlow.Event.CameraPermissionGranted)
            } else {
                launcher.launch(Manifest.permission.CAMERA)
            }
        }
        if (vm.state.value is PairingFlow.State.Scanning && granted) {
            QrScanner(lifecycleOwner = this, onDecoded = { raw -> vm.dispatch(PairingFlow.Event.QrDecoded(raw)) })
        }
    }
}
