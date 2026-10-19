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

    private val handler = Handler(Looper.getMainLooper())
    private val timeoutRunnable = Runnable {
        android.util.Log.i(TAG, "auto-timeout reached — stopping")
        stopForegroundAndSelf()
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NotificationChannels.ensure(this)
        startForegroundCompat()
        handler.removeCallbacks(timeoutRunnable)
        // §24.5 (vc35 user feedback): the window is user-customizable; 0 = stay
        // connected until the user turns the toggle off (no auto-timeout).
        if (autoTimeoutMs > 0) handler.postDelayed(timeoutRunnable, autoTimeoutMs)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeoutRunnable)
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
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
        val notification = NotificationCompat.Builder(this, NotificationChannels.LIVE_CONNECTION)
            .setSmallIcon(com.zcode.android.core.notifications.R.drawable.ic_notif_shield)
            .setContentTitle(getString(R.string.fgs_connected_title, endpointLabel))
            .setContentText(getString(R.string.fgs_connected_text))
            .also { b -> pending?.let { b.setContentIntent(it) } }
            .setOngoing(true)
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
