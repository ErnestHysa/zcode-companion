package com.zcode.android.core.notifications

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * ReconnectProbeWorker — the §25.3/§15-S5 periodic low-rate reachability probe for
 * OPTED-IN "auto-reconnect" endpoints. Battery rules (§29): the ONLY periodic work
 * in the app; ≥ 15 min period (JobScheduler quota compliance by construction) and
 * NETWORK_CONNECTED-constrained, so it never fires without a usable network.
 *
 * If the app process is dead, the worker is a no-op success — there is nothing to
 * reconnect; cold start owns recovery (§15-S2). If the process is alive with a
 * registered hub, the probe triggers the same networkAvailable() path a network
 * callback would (no polling of task status — §29 explicit rejection).
 */
class ReconnectProbeWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val probe = ProbeHook.probe ?: return Result.success()
        runCatching { probe() }
            .onFailure { android.util.Log.w(TAG, "probe failed: ${it.javaClass.simpleName}") }
        return Result.success()
    }

    /**
     * Process-local bridge to the connection hub. WorkManager runs in-process
     * here; when the process is gone this is null and the worker no-ops.
     */
    object ProbeHook {
        @Volatile
        var probe: (() -> Unit)? = null
    }

    companion object {
        private const val TAG = "ReconnectProbe"
        private const val UNIQUE_NAME = "zcode-reconnect-probe"

        /** §29/§25.3: ≥ 15 min, network-constrained — quota-compliant by construction. */
        const val PERIOD_MINUTES = 15L

        /** The spec is exposed for the quota-compliance test (§43.2-8.4). */
        fun buildRequest(): androidx.work.PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ReconnectProbeWorker>(PERIOD_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .build()

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                buildRequest(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
        }
    }
}
