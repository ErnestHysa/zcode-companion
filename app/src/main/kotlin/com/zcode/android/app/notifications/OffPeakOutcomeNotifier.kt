package com.zcode.android.app.notifications

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.core.notifications.NotificationBroker
import com.zcode.android.core.notifications.StoreDiffs
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.model.OffPeakTaskRow

/**
 * G63 (vc52 Phase 13): idle-time (off-peak) run outcomes never notified — the
 * desktop notifies both outcomes ("Idle-time task finished / failed") while the
 * phone's task-terminal diff only saw the MAIN task projection; the
 * `off-peak-task.list` channel was viewer-only (AutomationsSection).
 *
 * The wiring's CONNECTED entry calls [onConnected]; the diff follows
 * [StoreDiffs]' first-emission seeding (a cold process's first list never
 * notifies yesterday's terminal states) and posts through the ONE broker on the
 * task-status channel. The outcomes gate (notifyTaskOutcomes) silences the plane
 * WITHOUT losing the baseline — an already-consumed completion must not
 * resurrect on an unmute. A provider fault is a logged no-op: a viewer-only
 * channel never crashes the notification plane.
 */
class OffPeakOutcomeNotifier(
    private val broker: NotificationBroker,
    /** The desktop-global off-peak list (the host serves it without args). */
    private val provider: suspend () -> List<OffPeakTaskRow>,
    /** `notifyTaskOutcomes` — refreshed by the wiring on each reconnect. */
    private val outcomesEnabled: suspend () -> Boolean,
    private val log: ZLog,
) {

    /** Previous snapshot shape (offPeakTaskId → raw status), like taskStatuses. */
    @Volatile
    private var statuses: Map<String, String> = emptyMap()

    @Volatile
    private var baselineSeeded = false

    suspend fun onConnected() {
        val rows = try {
            provider()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("offpeak.notifyFault", "${e.javaClass.simpleName}: ${e.message?.take(120)}")
            return
        }
        val previous = statuses
        val enabled = try {
            outcomesEnabled()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            true // the gate failing open matches every other notify* toggle's absent-read
        }
        if (enabled) {
            val events = if (baselineSeeded) {
                StoreDiffs.offPeakTerminalTransitions(previous, rows)
            } else {
                emptyList() // first emission seeds — never notifies
            }
            for ((row, kind) in events) {
                runCatching { broker.onEvent(kind, row.offPeakTaskId, null, row.title) }
                    .onFailure { log.warn("offpeak.postFault", it.javaClass.simpleName) }
            }
        }
        // The baseline ALWAYS moves (gated or not): a completion the gate consumed
        // must not resurrect when the user later unmutes.
        statuses = rows.associate { it.offPeakTaskId to it.status }
        baselineSeeded = true
    }
}
