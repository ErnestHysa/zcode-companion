package com.zcode.android.data.repo

import androidx.room.withTransaction
import com.zcode.android.data.db.JournalPendingCommandEntity
import com.zcode.android.data.db.JournalStateEntity
import com.zcode.android.data.db.JournalSubscriptionEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.journal.RecoveryJournal
import com.zcode.android.domain.repo.JournalRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * Recovery journal persistence (§15.4/§43.2-4.5): the WHOLE journal snapshot is
 * written inside ONE Room transaction at every recorded transition (§15.4 "persisted
 * transactionally at each transition"); a crash mid-write leaves the previous
 * complete snapshot (atomicity, §32.3 "journal updates single-transaction").
 * Cold start (§15-S2) replays via [load].
 */
class JournalRepositoryImpl(
    private val db: ZcodeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : JournalRepository {

    private val dao = db.journalDao()
    private val json = Json

    override suspend fun record(journal: RecoveryJournal) {
        db.withTransaction {
            dao.putState(
                JournalStateEntity(
                    endpointId = journal.endpointId,
                    helloServerTimeMs = journal.helloServerTimeMs,
                    capabilitiesJson = journal.capabilitiesSeen?.toString(),
                    version = journal.version,
                    updatedAtMs = clock(),
                ),
            )
            dao.clearSubscriptions(journal.endpointId)
            dao.insertSubscriptions(
                journal.subscriptions.map {
                    JournalSubscriptionEntity(journal.endpointId, it.topic, it.sessionId, it.afterSeq, it.logEpoch)
                },
            )
            dao.clearPending(journal.endpointId)
            dao.insertPending(
                journal.pendingOutbox.map {
                    JournalPendingCommandEntity(it.commandId, journal.endpointId, it.type, it.payloadRef, it.issuedAtMs)
                },
            )
        }
    }

    override suspend fun load(endpointId: EndpointId): RecoveryJournal? {
        val state = dao.state(endpointId.raw) ?: return null
        val subscriptions = dao.subscriptions(endpointId.raw)
        return RecoveryJournal(
            endpointId = state.endpointId,
            helloServerTimeMs = state.helloServerTimeMs,
            capabilitiesSeen = state.capabilitiesJson?.let {
                runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull()
            },
            subscriptions = subscriptions.map {
                RecoveryJournal.Subscription(it.topic, it.sessionId, it.afterSeq, it.logEpoch)
            },
            pendingOutbox = dao.pending(endpointId.raw).map {
                RecoveryJournal.PendingCommandRef(it.commandId, it.type, it.payloadRef, it.issuedAtMs)
            },
            lastAppliedPerTopic = subscriptions.associate { it.topic to it.afterSeq },
            version = state.version,
        )
    }

    override suspend fun clear(endpointId: EndpointId) = dao.clearEndpoint(endpointId.raw)
}
