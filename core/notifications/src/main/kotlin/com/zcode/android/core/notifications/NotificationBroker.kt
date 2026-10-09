package com.zcode.android.core.notifications

import com.zcode.android.core.common.log.ZLog
import com.zcode.android.domain.model.NotificationRecord
import com.zcode.android.domain.recovery.NotificationReconciler

/**
 * NotificationBroker — the ONE notification decision + delivery point (§24.1):
 * evaluates [NotificationPlan], keeps the §16 NotificationRecord set (dedupe keys,
 * 7-day retention, bounded), posts/cancels through the [Sink], and runs the
 * §24.3/S12 reconciliation (dismiss records whose authoritative state consumed
 * them). UI components never create or cancel notifications directly — they only
 * ever see this broker's effects on the system tray.
 *
 * Records persist through [RecordStore] (DataStore-proto, §16/§14.2) so dedupe and
 * reconciliation survive process restarts; the SYSTEM notifications themselves are
 * transient — after a process death the broker re-posts still-open attention items
 * on the next background entry (§15-S13: a permission that arrived while the app
 * was away must still surface, either live or on recovery).
 *
 * Threading: all entry points are main-confined by the app wiring (single-threaded
 * broker, §28.5); state is a plain map guarded by [this] for test flexibility.
 */
class NotificationBroker(
    private val sink: Sink,
    private val records: RecordStore? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val retentionMs: Long = RETENTION_MS,
    private val log: ZLog = ZLog(sink = {}),
) {

    /** Delivery boundary over NotificationManager (swappable in tests). */
    interface Sink {
        fun show(spec: Spec)
        fun cancel(key: String)
    }

    /** §16 NotificationRecord persistence (DataStore-proto; additive schema §32.3). */
    interface RecordStore {
        suspend fun load(): List<NotificationRecord>
        suspend fun save(all: List<NotificationRecord>)
    }

    data class Spec(
        val key: String,
        val channelId: String,
        /** Lock-screen-safe text (public version; §24.2 privacy). */
        val publicText: String,
        /** Unlocked content text; null = no content beyond [publicText]. */
        val detailText: String?,
        /** Per-endpoint group key (§24.2 group summaries); null = ungrouped. */
        val groupKey: String?,
        val deepLinkTaskId: String?,
        val deepLinkInteractionId: String?,
    )

    /** §31: user-visible copy (default English; the wiring binds resources). */
    @Volatile
    var copy: NotificationPlan.NotificationCopy = NotificationPlan.DefaultNotificationCopy

    private val visibleRecords = LinkedHashMap<String, NotificationRecord>()
    private var foreground = true
    private var loaded = false

    /**
     * §43.2-11.4 (Glance widget): an observable mirror of the broker's record set.
     * The widget reads THIS — never the interaction store, never the transport
     * (the widget's entire data source is broker state, §24.1's broker-as-decision-
     * point made literal). Emissions coalesce per mutation burst via StateFlow.
     */
    private val _recordsFlow = kotlinx.coroutines.flow.MutableStateFlow<List<NotificationRecord>>(emptyList())
    val recordsFlow: kotlinx.coroutines.flow.StateFlow<List<NotificationRecord>> = _recordsFlow

    private fun publishRecords() {
        _recordsFlow.value = synchronized(this) { visibleRecords.values.toList() }
    }

    /** Load persisted records (call once at wiring start; idempotent). */
    suspend fun load() {
        if (loaded) return
        val stored = records?.load().orEmpty()
        synchronized(this) {
            visibleRecords.clear()
            for (r in stored) visibleRecords[r.notificationKey] = r
            loaded = true
        }
        publishRecords()
        log.info("notify.brokerLoad", "records=${stored.size}")
    }

    // ------------------------------------------------------------- event entries

    /** Attention event: approval / user-input / task outcome / connection lost. */
    suspend fun onEvent(kind: NotificationRecord.Kind, taskId: String?, interactionId: String?, taskTitle: String?) {
        val key = NotificationPlan.notificationKey(kind, taskId, interactionId)
            ?: run {
                log.warn("notify.noKey", "kind=$kind")
                return
            }
        val decision = NotificationPlan.decide(
            NotificationPlan.Event(kind, taskTitle),
            NotificationPlan.Policy(appForeground = foreground, contentLevel = contentLevel()),
            copy,
        )
        // §16 seen semantics: "seen" = the user has plausibly consumed it in-app.
        // ATTENTION records stay unseen on arrival (a foreground-suppressed approval
        // must re-post on the next background entry, §15-S13); informational records
        // are seen on delivery (no in-app counterpart is required).
        val attention = kind == NotificationRecord.Kind.APPROVAL_NEEDED || kind == NotificationRecord.Kind.USER_INPUT_NEEDED
        upsertRecord(key, kind, taskId, interactionId, seen = !attention)
        publishRecords()
        if (!decision.show) {
            log.debug("notify.suppressed", "key=${key.take(24)} foreground=$foreground")
            return
        }
        sink.show(
            Spec(
                key = key,
                channelId = decision.channelId,
                publicText = decision.publicText,
                detailText = decision.detailText,
                groupKey = groupKey,
                deepLinkTaskId = taskId,
                deepLinkInteractionId = interactionId,
            ),
        )
        log.info("notify.show", "key=${key.take(24)} channel=${decision.channelId}")
    }

    /** The app entered the BACKGROUND — attention becomes relevant (§9.9's complement). */
    suspend fun onBackgroundEntry() {
        foreground = false
        // §15-S13 + process-restart gap: EVERY still-open interaction (not just new
        // ones) re-evaluates — a request that arrived while foregrounded or before a
        // process death still surfaces the moment the app is away.
        val open = synchronized(this) {
            visibleRecords.values
                .filter { it.seenAtMs == null }
                .filter { it.kind == NotificationRecord.Kind.APPROVAL_NEEDED || it.kind == NotificationRecord.Kind.USER_INPUT_NEEDED }
                .toList()
        }
        for (record in open) {
            val decision = NotificationPlan.decide(
                NotificationPlan.Event(record.kind),
                NotificationPlan.Policy(appForeground = false, contentLevel = contentLevel()),
            )
            if (!decision.show) continue
            sink.show(
                Spec(
                    key = record.notificationKey,
                    channelId = decision.channelId,
                    publicText = decision.publicText,
                    detailText = decision.detailText,
                    groupKey = groupKey,
                    deepLinkTaskId = record.taskId,
                    deepLinkInteractionId = record.interactionId,
                ),
            )
            log.info("notify.repost", "key=${record.notificationKey.take(24)} (background entry)")
        }
    }

    /** The app is FOREGROUND again — in-app surfaces own attention now (§9.9). */
    suspend fun onForegroundEntry() {
        foreground = true
        val attention = synchronized(this) {
            visibleRecords.values
                .filter { it.kind == NotificationRecord.Kind.APPROVAL_NEEDED || it.kind == NotificationRecord.Kind.USER_INPUT_NEEDED }
                .map { it.notificationKey }
        }
        for (key in attention) {
            sink.cancel(key)
            markSeen(key)
        }
    }

    /** Connection restored — the CONNECTION_LOST slot clears (§24.2 update-in-place). */
    fun onConnectionRestored() {
        sink.cancel(CONNECTION_LOST_KEY)
    }

    // ------------------------------------------------------------- reconciliation

    /**
     * §24.3 / §15-S12: dismiss records whose authoritative state consumed them.
     * Mirrors the RecoveryCoordinator's NotificationRecordPort contract.
     */
    suspend fun reconcile(consumedTaskIds: Set<String>, resolvedInteractionIds: Set<String>): List<String> {
        val current = synchronized(this) { visibleRecords.values.toList() }
        val dismissed = NotificationReconciler.dismissals(current, consumedTaskIds, resolvedInteractionIds)
        for (key in dismissed) {
            sink.cancel(key)
            synchronized(this) { visibleRecords.remove(key) }
        }
        if (dismissed.isNotEmpty()) {
            persist()
            publishRecords()
        }
        return dismissed
    }

    /** Record snapshot for the RecoveryCoordinator port (§15-S12). */
    fun visible(): List<NotificationRecord> = synchronized(this) { visibleRecords.values.toList() }

    /** Drop records + their notifications (reconciler / unpair wipe). */
    suspend fun dismiss(keys: List<String>) {
        for (key in keys) {
            sink.cancel(key)
            synchronized(this) { visibleRecords.remove(key) }
        }
        persist()
        publishRecords()
    }

    /** §16 7-day retention sweep (call at wiring start / app start). */
    suspend fun sweepRetention() {
        val now = clock()
        val expired = synchronized(this) {
            visibleRecords.values.filter { now - it.createdAtMs > retentionMs }.map { it.notificationKey }
        }
        if (expired.isNotEmpty()) dismiss(expired)
    }

    suspend fun discardAll() {
        val keys = synchronized(this) { visibleRecords.keys.toList() }
        dismiss(keys)
    }

    // ------------------------------------------------------------- internals

    /** Per-endpoint group (§24.2 "group summaries per endpoint"); single-endpoint MVP. */
    var groupKey: String? = null

    /** Content level is user policy — the wiring refreshes it from preferences. */
    @Volatile
    var contentLevelProvider: suspend () -> com.zcode.android.domain.model.AppPreferences.NotificationContentLevel =
        { com.zcode.android.domain.model.AppPreferences.NotificationContentLevel.MINIMAL }

    private suspend fun contentLevel() = contentLevelProvider()

    private suspend fun upsertRecord(key: String, kind: NotificationRecord.Kind, taskId: String?, interactionId: String?, seen: Boolean) {
        synchronized(this) {
            val existing = visibleRecords[key]
            visibleRecords[key] = NotificationRecord(
                notificationKey = key,
                taskId = taskId ?: existing?.taskId,
                kind = kind,
                createdAtMs = existing?.createdAtMs ?: clock(),
                seenAtMs = if (seen) existing?.seenAtMs ?: clock() else null,
                interactionId = interactionId ?: existing?.interactionId,
            )
            enforceBound()
        }
        persist()
    }

    private suspend fun markSeen(key: String) {
        synchronized(this) {
            val existing = visibleRecords[key] ?: return
            if (existing.seenAtMs == null) {
                visibleRecords[key] = existing.copy(seenAtMs = clock())
            }
        }
        persist()
        publishRecords()
    }

    /** INV-5: the record set is bounded; oldest records drop first. */
    private fun enforceBound(max: Int = MAX_RECORDS) {
        while (visibleRecords.size > max) {
            val oldest = visibleRecords.values.minByOrNull { it.createdAtMs }?.notificationKey ?: break
            visibleRecords.remove(oldest)
        }
    }

    private suspend fun persist() {
        val store = records ?: return
        val snapshot = synchronized(this) { visibleRecords.values.toList() }
        // Persistence failures never break delivery (§33 storage class: quarantine
        // happens at the store layer; here a failed save degrades dedupe only).
        runCatching { store.save(snapshot) }
            .onFailure { log.warn("notify.persistFailed", it.javaClass.simpleName) }
    }

    companion object {
        /** §16 NotificationRecord lifecycle: 7-day retention. */
        const val RETENTION_MS: Long = 7L * 24 * 60 * 60 * 1000
        const val MAX_RECORDS = 256
        const val CONNECTION_LOST_KEY = "connection-lost"
    }
}
