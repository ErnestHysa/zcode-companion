package com.zcode.android.core.ui.components

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.launch

/**
 * In-app notices (B3-states: Toast ×N → Snackbar). The app root provides a
 * Snackbar-backed [Notifier]; every screen calls `LocalNotifier.current.show(...)`
 * instead of `Toast.makeText` (StatesDisciplineTest gates the Toast class out of
 * screens). The default is a no-op so previews/tests without a host never crash.
 */
@Immutable
class Notifier(private val onShow: (message: String, long: Boolean) -> Unit) {
    fun show(message: String, long: Boolean = false) = onShow(message, long)

    companion object {
        val NoOp = Notifier { _, _ -> }
    }
}

val LocalNotifier = staticCompositionLocalOf { Notifier.NoOp }

/** A Snackbar-backed notifier bound to a host state (the app root's SnackbarHost). */
@Composable
fun rememberSnackbarNotifier(host: SnackbarHostState): Notifier {
    val scope = rememberCoroutineScope()
    return remember(host) {
        Notifier { message, long ->
            scope.launch {
                host.showSnackbar(message = message, duration = if (long) SnackbarDuration.Long else SnackbarDuration.Short)
            }
        }
    }
}
