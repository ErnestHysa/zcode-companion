package com.zcode.android.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import android.content.Intent
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.zcode.android.app.MainActivity
import com.zcode.android.app.R
import com.zcode.android.domain.model.NotificationRecord

/**
 * §43.2-11.4 — the pending-approvals Glance widget ("glanceable approvals", §44).
 * Its ENTIRE data source is the §24.1 broker's record flow (`recordsFlow`):
 * it never touches the transport, the repositories, or any projection — the
 * broker is the single notification decision point, and the widget is one of
 * its consumers. Updates are pushed by [PendingApprovalsWidgetUpdater] whenever
 * the broker's record set changes (event-driven; §29 no polling).
 */
class PendingApprovalsWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val records = NotificationBrokerSource.current(context)
        val state = WidgetState.from(records)
        provideContent { Content(state, records) }
    }

    @Composable
    private fun Content(state: WidgetState, records: List<NotificationRecord>) {
        val context = LocalContext.current
        val openApp = actionStartActivity(launchIntent(context, records), actionParametersOf())
        GlanceTheme {
            Box(
                modifier = GlanceModifier.fillMaxSize().background(GlanceTheme.colors.background),
                contentAlignment = Alignment.CenterStart,
            ) {
                Column(modifier = GlanceModifier.fillMaxWidth().padding(12.dp)) {
                    Text(
                        text = context.getString(R.string.widget_approvals_title),
                        style = TextStyle(
                            color = GlanceTheme.colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1,
                    )
                    Spacer(GlanceModifier.padding(vertical = 3.dp))
                    Text(
                        text = state.headline(context),
                        style = TextStyle(
                            color = if (state.count > 0) {
                                ColorProvider(Color(0xFFE6A23C))
                            } else {
                                GlanceTheme.colors.onSurfaceVariant
                            },
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        ),
                        maxLines = 2,
                    )
                }
                // Whole-widget tap → the app (the task list's approvals badge is
                // the in-app follow-up; §24.7 deep links remain notification-only).
                Box(modifier = GlanceModifier.fillMaxSize().clickable(openApp)) {}
            }
        }
    }
}

/** The widget's whole-tap launch intent (H3.2a seam — pure, unit-pinnable). */
internal fun launchIntent(context: Context, records: List<NotificationRecord>): Intent =
    Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Pure derivation — unit-tested without Glance (§35.1). */
data class WidgetState(val count: Int) {
    fun headline(context: Context): String =
        if (count <= 0) context.getString(R.string.widget_none)
        else context.resources.getQuantityString(R.plurals.widget_approvals_count, count, count)

    companion object {
        /**
         * The widget counts OPEN attention items: unseen APPROVAL/USER-INPUT
         * records (the §24.3 "one per open interaction" set). Seen/informational
         * records and the connection-lost slot are not approvals.
         */
        fun from(records: List<NotificationRecord>): WidgetState = WidgetState(
            count = records.count {
                it.seenAtMs == null &&
                    (it.kind == NotificationRecord.Kind.APPROVAL_NEEDED ||
                        it.kind == NotificationRecord.Kind.USER_INPUT_NEEDED)
            },
        )
    }
}

/** Access seam so the widget composition reads broker state without app wiring. */
object NotificationBrokerSource {
    @Volatile
    var current: (Context) -> List<NotificationRecord> = { emptyList() }
}

/** Manifest receiver (app manifest registers it with the APPWIDGET_UPDATE filter). */
class PendingApprovalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingApprovalsWidget()
}
