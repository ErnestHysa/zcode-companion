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
        /** LEDGER-SMALLS (a): connection-scoped posts tap through to the connection
         *  screen (`zcode://connection`) — the shade entry used to be a dead end. */
        val deepLinkConnection: Boolean = false,
        /** C3 (Phase 4E): shade-level actions (Approve-once / Deny on approvals). */
        val actions: List<SpecAction> = emptyList(),
        /** G77 (vc52 Phase 7): the auto-resolution deadline — the sink renders a native
         *  countdown chronometer to it; null = no fuse. */
        val deadlineAtMs: Long? = null,
    )

    /**
     * A shade action the SINK renders (the broker stays transport-independent; the
     * sink turns id+labels into PendingIntents against the app's receiver).
     */
    data class SpecAction(
        val id: String,
        val title: String,
        val interactionId: String,
        val taskId: String?,
        /** The resolved option ("allowOnce" / "deny") — semantic, optionId-classified. */
        val optionId: String,
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
    suspend fun onEvent(
        kind: NotificationRecord.Kind,
        taskId: String?,
        interactionId: String?,
        taskTitle: String?,
        /** C3: a PERMISSION notification carries the shade Approve-once/Deny actions. */
        permission: Boolean = false,
        /** F-33-H: what the approval/question is about (shown privately under CONTENT). */
        subject: String? = null,
        /** G77: the auto-resolution deadline (epoch ms) — the shade counts down to it. */
        deadlineAtMs: Long? = null,
    ) {
        val key = NotificationPlan.notificationKey(kind, taskId, interactionId)
            ?: run {
                log.warn("notify.noKey", "kind=$kind")
                return
            }
        val decision = NotificationPlan.decide(
            NotificationPlan.Event(kind, taskTitle, subject),
            NotificationPlan.Policy(appForeground = foreground, contentLevel = contentLevel(), soundOn = soundEnabledProvider()),
            copy,
        )
        // §16 seen semantics: "seen" = the user has plausibly consumed it in-app.
        // ATTENTION records stay unseen on arrival (a foreground-suppressed approval
        // must re-post on the next background entry, §15-S13); informational records
        // are seen on delivery (no in-app counterpart is required).
        val attention = kind.isAttention
        upsertRecord(key, kind, taskId, interactionId, seen = !attention)
        publishRecords()
        if (!decision.show) {
            log.debug("notify.suppressed", "key=${key.take(24)} foreground=$foreground")
            return
        }
        // F-33-H: Approve/Deny ride the shade only beside the subject they answer.
        val actions = if (permission && decision.shadeActions && interactionId != null && taskId != null) {
            listOf(
                SpecAction(NotificationActions.ID_APPROVE, copy.approveAction(), interactionId, taskId, "allowOnce"),
                SpecAction(NotificationActions.ID_DENY, copy.denyAction(), interactionId, taskId, "deny"),
            )
        } else {
            emptyList()
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
                deepLinkConnection = kind == NotificationRecord.Kind.CONNECTION_LOST,
                actions = actions,
                deadlineAtMs = deadlineAtMs,
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
                .filter { it.kind.isAttention }
                .toList()
        }
        for (record in open) {
            // F-36-B: a record whose interaction the authoritative open set reports
            // CLOSED never re-posts — the ghost approval was re-notified on every
            // background entry and (worse) stayed answerable onto the live wire.
            val gateOpen = record.interactionId?.let { id -> runCatching { repostGate(id) }.getOrNull() } ?: true
            if (!gateOpen) {
                log.info("notify.repostSkip", "key=${record.notificationKey.take(24)} (interaction closed)")
                continue
            }
            // F-33-H: the record carries no subject — the wiring resolves it from the live
            // interaction store; when it cannot, the re-post stays subject-less AND
            // action-less (never a blind approve).
            val subject = record.interactionId?.let { id -> runCatching { subjectProvider(id) }.getOrNull() }
            val decision = NotificationPlan.decide(
                NotificationPlan.Event(record.kind, subject = subject),
                NotificationPlan.Policy(appForeground = false, contentLevel = contentLevel(), soundOn = soundEnabledProvider()),
                copy,
            )
            if (!decision.show) continue
            // A12 (vc51, pass-9 Y2): the re-post replicates the fresh path's permission-gated
            // actions — an approval arriving while foregrounded is §9.9-suppressed at post
            // time, so this actions-less re-post was the ONLY post such an approval ever got
            // (the shade buttons existed only in the rare already-backgrounded race). The
            // record already holds kind/interactionId/taskId — the same SpecAction builder,
            // gated by the same F-33-H shadeActions rule as the fresh path.
            val actions = if (record.kind == NotificationRecord.Kind.APPROVAL_NEEDED && decision.shadeActions &&
                record.interactionId != null && record.taskId != null
            ) {
                listOf(
                    SpecAction(NotificationActions.ID_APPROVE, copy.approveAction(), record.interactionId!!, record.taskId!!, "allowOnce"),
                    SpecAction(NotificationActions.ID_DENY, copy.denyAction(), record.interactionId!!, record.taskId!!, "deny"),
                )
            } else {
                emptyList()
            }
            val deadline = record.interactionId?.let { id -> runCatching { deadlineProvider(id) }.getOrNull() }
            sink.show(
                Spec(
                    key = record.notificationKey,
                    channelId = decision.channelId,
                    publicText = decision.publicText,
                    detailText = decision.detailText,
                    groupKey = groupKey,
                    deepLinkTaskId = record.taskId,
                    deepLinkInteractionId = record.interactionId,
                    actions = actions,
                    deadlineAtMs = deadline,
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
                .filter { it.kind.isAttention }
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

    /**
     * G63 (vc52 Phase 13): the notification-sound mute (the wiring refreshes it from
     * preferences). Default ON — the historical audible behavior.
     */
    @Volatile
    var soundEnabledProvider: suspend () -> Boolean = { true }

    /**
     * F-33-H: an open interaction's subject for the background re-post (records persist no
     * subject — the command text never sits in notification_records.pb). Null = unknown.
     */
    @Volatile
    var subjectProvider: suspend (interactionId: String) -> String? = { null }

    /**
     * F-36-B (vc52 Phase 7): is this interaction still OPEN attention? The wiring binds
     * its authoritative open-set projection. `true` = open (re-post as before); `false` =
     * known-closed (the re-post is SKIPPED — a terminal row must never resurface, the
     * pass-36 two-day-old STALE approval); `null` = unknown (before the wiring's first
     * projection emission) — never skips, the §15-S13 contract stands.
     */
    @Volatile
    var repostGate: suspend (interactionId: String) -> Boolean? = { null }

    /**
     * G77: an open interaction's auto-resolution deadline for the background re-post
     * (records persist no deadline — the wiring resolves it from its live projection).
     * Null = no fuse known.
     */
    @Volatile
    var deadlineProvider: suspend (interactionId: String) -> Long? = { null }

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
