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
import com.zcode.android.app.notifications.DeepLinks
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
        // H3.2c: a fresh process (reboot / process death) has no attached broker yet —
        // render the persisted last count instead of resetting to zero.
        val records = if (NotificationBrokerSource.attached) NotificationBrokerSource.current(context) else emptyList()
        val state = WidgetState.resolve(context)
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

/**
 * H3.2a: the widget's whole-tap launch intent. With open approvals it carries the
 * VALIDATED `zcode://task/{taskId}#interaction={id}` deep link of the NEWEST open
 * attention record (the §24.7 intake parser is the validation gate — a link that
 * does not parse degrades to a plain launch, never a malformed intent); with
 * nothing pending it is the plain launcher intent.
 */
internal fun launchIntent(context: Context, records: List<NotificationRecord>): Intent {
    val base = Intent(context, MainActivity::class.java)
        .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    val target = records
        .filter {
            it.seenAtMs == null && it.taskId != null &&
                (it.kind == NotificationRecord.Kind.APPROVAL_NEEDED ||
                    it.kind == NotificationRecord.Kind.USER_INPUT_NEEDED)
        }
        .maxByOrNull { it.createdAtMs } ?: return base
    val uri = android.net.Uri.parse("zcode://task/${target.taskId}").buildUpon()
    if (!target.interactionId.isNullOrBlank()) uri.fragment("interaction=${target.interactionId}")
    base.action = Intent.ACTION_VIEW
    base.data = uri.build()
    return if (DeepLinks.intake(base) != null) base else {
        // Fail closed to the plain launch — never a malformed deep link.
        Intent(context, MainActivity::class.java)
            .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}

/** Pure derivation — unit-tested without Glance (§35.1). */
data class WidgetState(val count: Int) {
    fun headline(context: Context): String =
        if (count <= 0) context.getString(R.string.widget_none)
        else context.resources.getQuantityString(R.plurals.widget_approvals_count, count, count)

    companion object {
        private const val PREFS = "pending_approvals_widget"
        private const val KEY_LAST_COUNT = "last_count"

        /**
         * H3.2c: the state the widget renders. With an attached broker the live record
         * set is authoritative (and refreshes the persisted count); before the app has
         * run in this process the PERSISTED last count stands in — never a reset to zero.
         */
        fun resolve(context: Context): WidgetState =
            if (NotificationBrokerSource.attached) {
                from(NotificationBrokerSource.current(context)).also { persist(context, it.count) }
            } else {
                WidgetState(persistedCount(context))
            }

        fun persist(context: Context, count: Int) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putInt(KEY_LAST_COUNT, count).apply()
        }

        fun persistedCount(context: Context): Int =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LAST_COUNT, 0)

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
        private set

    /** H3.2c: false until the in-process updater attaches the live broker. */
    @Volatile
    var attached: Boolean = false
        private set

    fun attach(source: (Context) -> List<NotificationRecord>) {
        current = source
        attached = true
    }

    /** Test seam — the post-reboot static default. */
    fun detach() {
        current = { emptyList() }
        attached = false
    }
}

/** Manifest receiver (app manifest registers it with the APPWIDGET_UPDATE filter). */
class PendingApprovalsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PendingApprovalsWidget()
}
