package com.zcode.android.data.repo

import androidx.room.withTransaction
import com.zcode.android.data.db.ConversationRowEntity
import com.zcode.android.data.db.ConversationSessionMetaEntity
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.domain.ids.SessionId
import com.zcode.android.domain.model.ConversationRow
import com.zcode.android.domain.model.RowDecodeException
import com.zcode.android.domain.repo.ConversationCacheBounds
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.ConversationStoreOp
import com.zcode.android.domain.repo.StoreApplyResult
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Conversation page-cache repository (§17.3 row 2) — the PERSISTENT store behind the
 * Phase 5 projection reducers. Store-level rules implemented here (§15.5/§19.4):
 *  - snapshots REPLACE atomically (all rows for the session in one transaction);
 *  - ANY other op whose logEpoch ≠ cached epoch is REJECTED with zero mutation
 *    (never merge ambiguous state — §15-S9/S10; the caller escalates to resync);
 *  - per-session row bound enforced on every write (§17.3 "≤ 2 × render window +
 *    1 page"; INV-5), evicting the OLDEST cached rows beyond the newest bound;
 *  - decode faults on cached rows are dropped + COUNTED (INV-8), never crash reads.
 */
class ConversationRepositoryImpl(
    private val db: ZcodeDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
) : ConversationRepository {

    private val dao = db.conversationDao()
    private val jsonParser = Json
    private val droppedDecodeCounter = AtomicLong(0)

    /** INV-8 observability: cached rows that failed tolerant decode (diagnostics). */
    val droppedDecodeCount: Long get() = droppedDecodeCounter.get()

    override fun observeRows(sessionId: SessionId): Flow<List<ConversationRow>> =
        dao.observeRows(sessionId.raw).map { rows -> rows.mapNotNull { it.toDomain() } }

    override fun observeEpoch(sessionId: SessionId): Flow<String?> = dao.observeEpochInternal(sessionId.raw)

    override suspend fun currentEpoch(sessionId: SessionId): String? = dao.meta(sessionId.raw)?.logEpoch

    override suspend fun apply(op: ConversationStoreOp): StoreApplyResult = when (op) {
        is ConversationStoreOp.SnapshotArrived -> applySnapshot(op)
        is ConversationStoreOp.PageFetched -> applyGuarded(op) { rows ->
            dao.insertRows(rows.map { it.toEntity(op) })
        }
        is ConversationStoreOp.RowUpserted -> applyGuarded(op) { rows ->
            dao.insertRows(rows.map { it.toEntity(op) })
        }
        is ConversationStoreOp.RowRemoved -> applyGuardedUnit(op) {
            dao.deleteFromRow(op.sessionId.raw, op.fromRowId)
            true
        }
        is ConversationStoreOp.RowTextAppended -> applyGuardedUnit(op) {
            dao.appendText(op.sessionId.raw, op.rowId) { currentJson ->
                appendTextToJson(currentJson, op.append)
            }
        }
    }

    private suspend fun applySnapshot(op: ConversationStoreOp.SnapshotArrived): StoreApplyResult {
        val previous = dao.meta(op.sessionId.raw)?.logEpoch
        db.withTransaction {
            dao.deleteAllRows(op.sessionId.raw)
            dao.putMeta(ConversationSessionMetaEntity(op.sessionId.raw, op.logEpoch, clock()))
            dao.insertRows(op.rows.map { it.toEntity(op) })
            enforceBound(op.sessionId.raw)
        }
        return StoreApplyResult.Replaced(previous)
    }

    /** Epoch-guarded ops carrying rows: adopt epoch when the session is fresh. */
    private suspend fun applyGuarded(
        op: ConversationStoreOp,
        write: suspend (rows: List<ConversationRow>) -> Unit,
    ): StoreApplyResult {
        val current = dao.meta(op.sessionId.raw)
        if (current != null && current.logEpoch != op.logEpoch) {
            return StoreApplyResult.EpochRejected(current.logEpoch)
        }
        val rows = op.rowsList()
        db.withTransaction {
            if (current == null) {
                dao.putMeta(ConversationSessionMetaEntity(op.sessionId.raw, op.logEpoch, clock()))
            }
            write(rows)
            enforceBound(op.sessionId.raw)
        }
        return StoreApplyResult.Applied
    }

    private suspend fun applyGuardedUnit(
        op: ConversationStoreOp,
        write: suspend () -> Boolean,
    ): StoreApplyResult {
        val current = dao.meta(op.sessionId.raw)
        if (current != null && current.logEpoch != op.logEpoch) {
            return StoreApplyResult.EpochRejected(current.logEpoch)
        }
        return db.withTransaction {
            if (current == null) {
                dao.putMeta(ConversationSessionMetaEntity(op.sessionId.raw, op.logEpoch, clock()))
            }
            if (write()) StoreApplyResult.Applied else StoreApplyResult.Dropped
        }
    }

    /** §17.3 bound: keep the newest [ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION]. */
    private suspend fun enforceBound(sessionId: String) {
        val victims = dao.rowIdsBeyondCap(sessionId, ConversationCacheBounds.MAX_CACHED_ROWS_PER_SESSION)
        if (victims.isNotEmpty()) dao.deleteRowIds(sessionId, victims)
    }

    override suspend fun cachedRowCount(sessionId: SessionId): Int = dao.countRows(sessionId.raw)

    // ------------------------------------------------------------------ mapping

    private fun ConversationRowEntity.toDomain(): ConversationRow? = try {
        val obj = jsonParser.parseToJsonElement(this.json)
        ConversationRow.Decoder.decode(obj as? JsonObject ?: return null.also { droppedDecodeCounter.incrementAndGet() })
    } catch (e: Exception) {
        // corrupted cached row (bad JSON or known-kind schema violation): dropped +
        // COUNTED, never crashes the read (INV-8/§39.3 — safe reject; resync is the cure)
        droppedDecodeCounter.incrementAndGet()
        null
    }

    private fun ConversationRow.toEntity(op: ConversationStoreOp): ConversationRowEntity = ConversationRowEntity(
        sessionId = op.sessionId.raw,
        rowId = rowId,
        turnId = turnId,
        kind = kind,
        json = raw.toString(),
        createdAtSeq = createdAtSeq,
    )

    private fun ConversationStoreOp.rowsList(): List<ConversationRow> = when (this) {
        is ConversationStoreOp.PageFetched -> rows
        is ConversationStoreOp.RowUpserted -> listOf(row)
        else -> emptyList()
    }

    /** row.delta append: patch the `text` string field of the cached row JSON. */
    private fun appendTextToJson(currentJson: String, append: String): String {
        val obj = runCatching { jsonParser.parseToJsonElement(currentJson) as? JsonObject }.getOrNull()
            ?: return currentJson
        val text = (obj["text"] as? JsonPrimitive)?.content ?: return currentJson
        val patched = JsonObject(obj.toMap() + ("text" to JsonPrimitive(text + append)))
        return patched.toString()
    }
}
