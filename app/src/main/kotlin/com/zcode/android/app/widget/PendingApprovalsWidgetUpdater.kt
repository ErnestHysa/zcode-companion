package com.zcode.android.app.widget

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.zcode.android.core.notifications.NotificationBroker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Pushes the widget whenever the broker's record set changes (event-driven;
 * §29 no polling — there is no periodic widget refresh and no transport access).
 * Widget updates only run while the process is alive; after a process death the
 * next broker mutation re-syncs the widget (documented limitation, gate doc).
 */
class PendingApprovalsWidgetUpdater(
    private val context: Context,
    private val broker: NotificationBroker,
    private val scope: CoroutineScope,
) {
    private var started = false

    fun start() {
        if (started) return
        started = true
        NotificationBrokerSource.current = { broker.visible() }
        scope.launch {
            broker.recordsFlow.collectLatest { records ->
                // H2.2: the dynamic Approvals shortcut carries the live count (same event source).
                com.zcode.android.app.notifications.AppShortcuts.publishApprovalsCount(context, WidgetState.from(records).count)
                runCatching { PendingApprovalsWidget().updateAll(context) }
                    .onFailure { com.zcode.android.core.common.log.ZLog(sink = {}).warn("widget.updateFailed", it.javaClass.simpleName) }
            }
        }
    }
}
