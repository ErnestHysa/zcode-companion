package com.zcode.android.app.notifications

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.zcode.android.app.MainActivity
import com.zcode.android.app.R

/**
 * H2.2 (gaps-and-polish 2F): launcher shortcuts. The STATIC set lives in
 * `res/xml/shortcuts.xml` (New task, Approvals, Connection) and every shortcut
 * intent carries [EXTRA] so MainActivity routes it like a deep link (the
 * NotificationWiring pending-shortcut seam → ZCodeApp navigates). The Approvals
 * entry is ALSO published dynamically with the live "needs you" count whenever the
 * broker's record set changes (PendingApprovalsWidgetUpdater — the same event source
 * as the widget; §29 no polling).
 */
object AppShortcuts {
    const val EXTRA = "zcode.shortcut"
    const val ACTION = "com.zcode.android.app.action.SHORTCUT"
    const val ID_APPROVALS = "approvals"

    enum class Target(val id: String) {
        NEW_TASK("new_task"),
        APPROVALS("approvals"),
        CONNECTION("connection"),
    }

    /** The shortcut target carried by [intent], or null for a plain launch / deep link. */
    fun intake(intent: Intent?): Target? {
        val raw = intent?.getStringExtra(EXTRA) ?: return null
        return Target.entries.firstOrNull { it.id == raw }
    }

    /** Publish (or refresh) the dynamic Approvals shortcut with the live open count. */
    fun publishApprovalsCount(context: Context, count: Int) {
        val label = if (count > 0) context.getString(R.string.shortcut_approvals_count, count)
        else context.getString(R.string.shortcut_approvals)
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION)
            .putExtra(EXTRA, Target.APPROVALS.id)
        val shortcut = ShortcutInfoCompat.Builder(context, ID_APPROVALS)
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_launcher_foreground))
            .setIntent(intent)
            .setRank(0)
            .build()
        runCatching { ShortcutManagerCompat.pushDynamicShortcut(context, shortcut) }
    }
}
