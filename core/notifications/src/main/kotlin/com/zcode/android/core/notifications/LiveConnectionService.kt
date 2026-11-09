package com.zcode.android.core.notifications

import com.zcode.android.core.notifications.R

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * LiveConnectionService — the §24.5 "Live connection" foreground service and the
 * §25.2 `specialUse` VALIDATION SPIKE CANDIDATE (Phase 8.3). Normative frame:
 *
 *  - USER OPT-IN ONLY, started FROM THE FOREGROUND only (API 31+ background-start
 *    restriction; the Settings toggle is the only entry point — §25.1).
 *  - Candidate type `specialUse` on API 34+ (manifest property + subtype declared);
 *    pre-34 devices run it typeless (no type exists there — that is the platform
 *    contract, not a bypass). `dataSync` is EXCLUDED from the design (§25.1/§25.2).
 *  - The notification IS the required user-visible FGS notification: low-priority
 *    (no sound, no heads-up, §24.5), "Connected to <desktop> — tap to disconnect".
 *  - AUTO-TIMEOUT default 30 min ([PROPOSED] §24.5/§29) — bounded battery exposure.
 *  - NOT sticky: after process death the service stays off and the app falls back
 *    to reconnect-on-wake (§25.3) — no architecture depends on an immortal socket.
 *
 * What it ACTUALLY holds alive (spike scope): the PROCESS at foreground priority,
 * so the OS cached-app freezer / aggressive kills do not suspend the app's own
 * WebSocket + heartbeats (they live on process-lifetime threads/scopes, not in
 * this service). A service-owned connection would be the Phase 10+ evolution.
 */
class LiveConnectionService : Service() {

    /** F-34-F: what the user is told — three honest states, not the transport's nine. */
    enum class LinkStatus { CONNECTED, RECONNECTING, DISCONNECTED }

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        android.util.Log.i(TAG, "auto-timeout reached — stopping")
        stopForegroundAndSelf()
    }

    /**
     * F-34-F (vc52 Phase 8): the notification follows the LIVE connection. It used to be
     * built once at service start — through ten minutes of a DEGRADED transport the shade
     * kept saying "Connected — Live connection active": a false all-clear for the user
     * relying on it to receive approvals.
     */
    private val serviceScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate,
    )
    private var statusJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensure(this)
        startForegroundCompat(currentStatus())
        observeStatus()
        handler.removeCallbacks(timeoutRunnable)
        // §24.5 (vc35 user feedback): the window is user-customizable; 0 = stay
        // connected until the user turns the toggle off (no auto-timeout).
        if (autoTimeoutMs > 0) handler.postDelayed(timeoutRunnable, autoTimeoutMs)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        statusJob?.cancel()
        serviceScope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun currentStatus(): LinkStatus =
        phaseSource?.value?.let(::statusFor) ?: LinkStatus.CONNECTED

    /** Re-post on the SAME id, and only when the user-visible status actually changes. */
    private fun observeStatus() {
        val source = phaseSource ?: return
        statusJob?.cancel()
        statusJob = serviceScope.launch {
            source.map(::statusFor)
                .distinctUntilChanged()
                .drop(1) // the start-command post already shows the current status
                .collect { status -> startForegroundCompat(status) }
        }
    }

    private fun startForegroundCompat(status: LinkStatus) {
        val endpointLabel = endpointLabelProvider(this)
        // The tap target is a convenience — the REQUIRED FGS notification posts
        // even when no launcher activity class is registered (service-first start).
        val pending = LAUNCHER_ACTIVITY?.let { activity ->
            val tapIntent = Intent(this, activity).setFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
            android.app.PendingIntent.getActivity(
                this,
                TAP_REQUEST_CODE,
                tapIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val (title, text) = when (status) {
            LinkStatus.CONNECTED ->
                getString(R.string.fgs_connected_title, endpointLabel) to getString(R.string.fgs_connected_text)
            LinkStatus.RECONNECTING ->
                getString(R.string.fgs_reconnecting_title, endpointLabel) to getString(R.string.fgs_reconnecting_text)
            // "Tap to retry" is literal: the tap opens the app, and the foreground entry
            // IS the retry (hub.foregroundTrigger → a fresh reconnect budget, F-34-B).
            LinkStatus.DISCONNECTED ->
                getString(R.string.fgs_disconnected_title, endpointLabel) to getString(R.string.fgs_disconnected_text)
        }
        val notification = NotificationCompat.Builder(this, NotificationChannels.LIVE_CONNECTION)
            .setSmallIcon(com.zcode.android.core.notifications.R.drawable.ic_notif_shield)
            .setContentTitle(title)
            .setContentText(text)
            .also { b -> pending?.let { b.setContentIntent(it) } }
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
        // `specialUse` exists on API 34+ only; pre-34 the platform has no service
        // types to declare (0 = default). The manifest carries the type + subtype
        // property for the 34+ leg (§25.2 spike evidence lives in the phase gate).
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    companion object {
        const val NOTIFICATION_ID = 4201
        const val TAP_REQUEST_CODE = 4202
        const val AUTO_TIMEOUT_MS_DEFAULT = 30L * 60 * 1000
        private const val TAG = "LiveConnection"

        /** §24.5 auto-timeout (default 30 min); test/ADR-evidence adjustable. */
        @Volatile
        var autoTimeoutMs: Long = AUTO_TIMEOUT_MS_DEFAULT

        /** Notification label provider — the app wires the paired endpoint's name. */
        @Volatile
        var endpointLabelProvider: (Context) -> String = { it.getString(R.string.fgs_default_desktop) }

        /** MainActivity class — set by the app (core:notifications has no app types). */
        @Volatile
        var LAUNCHER_ACTIVITY: Class<*>? = null

        /**
         * F-34-F: the live connection phase (the app wires the hub's phase). Null = no
         * source (a service-first start) → the notification stays at its start status.
         */
        @Volatile
        var phaseSource: kotlinx.coroutines.flow.StateFlow<com.zcode.android.domain.state.ConnectionPhase>? = null

        fun statusFor(phase: com.zcode.android.domain.state.ConnectionPhase): LinkStatus = when (phase) {
            com.zcode.android.domain.state.ConnectionPhase.CONNECTED -> LinkStatus.CONNECTED
            com.zcode.android.domain.state.ConnectionPhase.PAIRING,
            com.zcode.android.domain.state.ConnectionPhase.AUTHENTICATING,
            com.zcode.android.domain.state.ConnectionPhase.CONNECTING,
            com.zcode.android.domain.state.ConnectionPhase.RECONNECTING,
            com.zcode.android.domain.state.ConnectionPhase.RECOVERING,
            -> LinkStatus.RECONNECTING
            // DEGRADED/FAILED need the user (Retry / re-pair); UNPAIRED/CLOSED have no link.
            com.zcode.android.domain.state.ConnectionPhase.DEGRADED,
            com.zcode.android.domain.state.ConnectionPhase.FAILED,
            com.zcode.android.domain.state.ConnectionPhase.CLOSED,
            com.zcode.android.domain.state.ConnectionPhase.UNPAIRED,
            -> LinkStatus.DISCONNECTED
        }

        /** Process-truth for Settings/worker/spike probes. OBSERVABLE (H3.1): the
         *  Settings switch collects this flow, so the 30-min auto-timeout stop flips
         *  the switch live — the old plain `var` was snapshotted into `remember {}`
         *  and left the switch ON until the screen was recreated. The service
         *  lifecycle (onCreate/onDestroy — including the timeout stop path) is the
         *  ONLY writer. */
        val isRunning: kotlinx.coroutines.flow.StateFlow<Boolean>
            get() = _isRunning

        private val _isRunning = kotlinx.coroutines.flow.MutableStateFlow(false)

        fun start(context: Context) {
            NotificationChannels.ensure(context)
            val intent = Intent(context, LiveConnectionService::class.java)
            // §25.1/§25.2: the ONLY caller is the foregrounded Settings surface —
            // startForegroundService from the foreground is the compliant path.
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveConnectionService::class.java))
        }
    }
}
