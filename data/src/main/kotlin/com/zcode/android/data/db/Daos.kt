package com.zcode.android.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Upsert
    suspend fun upsertAll(tasks: List<TaskEntity>)

    @Query("SELECT * FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey ORDER BY pinned DESC, updatedAtMs DESC")
    fun observe(endpointId: String, workspaceKey: String): Flow<List<TaskEntity>>

    /** Phase 6 §18.2: the task list spans every workspace of the endpoint (grouped in UI). */
    @Query("SELECT * FROM tasks WHERE endpointId = :endpointId ORDER BY pinned DESC, updatedAtMs DESC")
    fun observeByEndpoint(endpointId: String): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey ORDER BY pinned DESC, updatedAtMs DESC")
    suspend fun list(endpointId: String, workspaceKey: String): List<TaskEntity>

    @Query("DELETE FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey AND taskId IN (:taskIds)")
    suspend fun deleteByIds(endpointId: String, workspaceKey: String, taskIds: List<String>)

    /** §17.3: stale > 7 d dropped (archive-safe: archived tasks sweep by updatedAt too). */
    @Query("DELETE FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey AND updatedAtMs < :cutoffMs AND pinned = 0")
    suspend fun deleteStale(endpointId: String, workspaceKey: String, cutoffMs: Long): Int

    /**
     * F-P38-A: keys the connect snapshot no longer serves age out WHOLE — rows under
     * every workspace key outside the keep set (pinned included: the key itself is
     * gone, the desktop is the source of truth).
     */
    @Query("DELETE FROM tasks WHERE endpointId = :endpointId AND workspaceKey NOT IN (:keepKeys)")
    suspend fun deleteWorkspacesNotIn(endpointId: String, keepKeys: Set<String>): Int

    /** F-P38-A: the pruned keys' meta rows go with them (the ETag cache of a dead key). */
    @Query("DELETE FROM task_workspace_meta WHERE endpointId = :endpointId AND workspaceKey NOT IN (:keepKeys)")
    suspend fun deleteMetaWorkspacesNotIn(endpointId: String, keepKeys: Set<String>): Int

    /** Rows beyond the newest :keep — the LRU eviction victim set (oldest first). */
    @Query(
        "SELECT taskId FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey " +
            "AND pinned = 0 ORDER BY updatedAtMs DESC LIMIT -1 OFFSET :keep",
    )
    suspend fun beyondCap(endpointId: String, workspaceKey: String, keep: Int): List<String>

    @Query("SELECT COUNT(*) FROM tasks WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey")
    suspend fun count(endpointId: String, workspaceKey: String): Int

    @Query("SELECT * FROM task_workspace_meta WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey")
    suspend fun meta(endpointId: String, workspaceKey: String): TaskWorkspaceMetaEntity?

    @Upsert
    suspend fun putMeta(meta: TaskWorkspaceMetaEntity)
}

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversation_session_meta WHERE sessionId = :sessionId")
    suspend fun meta(sessionId: String): ConversationSessionMetaEntity?

    @Query("SELECT logEpoch FROM conversation_session_meta WHERE sessionId = :sessionId")
    fun observeEpochInternal(sessionId: String): Flow<String?>

    @Upsert
    suspend fun putMeta(meta: ConversationSessionMetaEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRows(rows: List<ConversationRowEntity>)

    @Query("SELECT * FROM conversation_rows WHERE sessionId = :sessionId ORDER BY rowId ASC")
    fun observeRows(sessionId: String): Flow<List<ConversationRowEntity>>

    @Query("SELECT * FROM conversation_rows WHERE sessionId = :sessionId ORDER BY rowId ASC")
    suspend fun listRows(sessionId: String): List<ConversationRowEntity>

    /** row.removed — truncates from fromRowId upward (delta.ts semantics). */
    @Query("DELETE FROM conversation_rows WHERE sessionId = :sessionId AND rowId >= :fromRowId")
    suspend fun deleteFromRow(sessionId: String, fromRowId: Long): Int

    @Query("DELETE FROM conversation_rows WHERE sessionId = :sessionId")
    suspend fun deleteAllRows(sessionId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_rows WHERE sessionId = :sessionId")
    suspend fun countRows(sessionId: String): Int

    /** F-P36-A: the cache's rowId bounds — the hasMore/total re-derivation inputs. */
    @Query("SELECT MIN(rowId) FROM conversation_rows WHERE sessionId = :sessionId")
    suspend fun minRowId(sessionId: String): Long?

    @Query("SELECT MAX(rowId) FROM conversation_rows WHERE sessionId = :sessionId")
    suspend fun maxRowId(sessionId: String): Long?

    /** Oldest rowIds beyond the newest :keep — §17.3 bound victim set. */
    @Query(
        "SELECT rowId FROM conversation_rows WHERE sessionId = :sessionId ORDER BY rowId DESC LIMIT -1 OFFSET :keep",
    )
    suspend fun rowIdsBeyondCap(sessionId: String, keep: Int): List<Long>

    @Query("DELETE FROM conversation_rows WHERE sessionId = :sessionId AND rowId IN (:rowIds)")
    suspend fun deleteRowIds(sessionId: String, rowIds: List<Long>)

    @Query("SELECT json FROM conversation_rows WHERE sessionId = :sessionId AND rowId = :rowId")
    suspend fun rowJson(sessionId: String, rowId: Long): String?

    @Query("UPDATE conversation_rows SET json = :json WHERE sessionId = :sessionId AND rowId = :rowId")
    suspend fun updateRowJson(sessionId: String, rowId: Long, json: String)

    @Transaction
    suspend fun appendText(sessionId: String, rowId: Long, transform: (String) -> String): Boolean {
        val current = rowJson(sessionId, rowId) ?: return false
        updateRowJson(sessionId, rowId, transform(current))
        return true
    }
}

@Dao
interface InteractionDao {
    @Upsert
    suspend fun upsert(entity: InteractionEntity)

    /**
     * F-36-B/F-33-E (vc52 Phase 7): open selection is LIFECYCLE-based — the caller
     * passes [InteractionLifecycle.OPEN_ATTENTION] verbatim, so the badge/inbox/
     * repost mirror share the domain's one predicate (a STALE/EXPIRED/FAILED/
     * CANCELLED row with no stamp is closed, the pass-36 ghost's shape).
     */
    @Query("SELECT * FROM interactions WHERE endpointId = :endpointId AND lifecycle IN (:lifecycles) ORDER BY createdAtMs DESC")
    fun observeOpen(endpointId: String, lifecycles: Set<String>): Flow<List<InteractionEntity>>

    /** One-shot read of the same open set (the coordinator's cold-start reconcile). */
    @Query("SELECT * FROM interactions WHERE endpointId = :endpointId AND lifecycle IN (:lifecycles) ORDER BY createdAtMs DESC")
    suspend fun listOpen(endpointId: String, lifecycles: Set<String>): List<InteractionEntity>

    @Query("SELECT * FROM interactions WHERE endpointId = :endpointId AND interactionId = :interactionId")
    suspend fun get(endpointId: String, interactionId: String): InteractionEntity?

    @Query("UPDATE interactions SET lifecycle = :lifecycle, resolvedAtMs = :resolvedAtMs WHERE endpointId = :endpointId AND interactionId = :interactionId")
    suspend fun resolve(endpointId: String, interactionId: String, lifecycle: String, resolvedAtMs: Long?): Int

    @Query("SELECT * FROM interactions WHERE endpointId = :endpointId ORDER BY createdAtMs ASC")
    suspend fun listAll(endpointId: String): List<InteractionEntity>

    @Query("DELETE FROM interactions WHERE endpointId = :endpointId AND interactionId IN (:ids)")
    suspend fun deleteByIds(endpointId: String, ids: List<String>): Int

    @Query("SELECT COUNT(*) FROM interactions WHERE endpointId = :endpointId")
    suspend fun count(endpointId: String): Int
}

@Dao
interface OutboxDao {
    @Upsert
    suspend fun upsert(entity: OutboxEntity)

    @Query("SELECT * FROM outbox WHERE commandId = :commandId")
    suspend fun get(commandId: String): OutboxEntity?

    @Query("SELECT * FROM outbox WHERE endpointId = :endpointId AND state IN ('ACCEPTED_LOCALLY','TRANSMITTED','UNKNOWN','FAILED_RETRYABLE') ORDER BY issuedAtMs ASC")
    fun observePending(endpointId: String): Flow<List<OutboxEntity>>

    @Query("SELECT * FROM outbox WHERE endpointId = :endpointId AND state IN ('ACCEPTED_LOCALLY','TRANSMITTED','UNKNOWN','FAILED_RETRYABLE') ORDER BY issuedAtMs ASC")
    suspend fun liveList(endpointId: String): List<OutboxEntity>

    @Query("UPDATE outbox SET state = 'EXPIRED', lastTransitionAtMs = :atMs WHERE endpointId = :endpointId AND state IN ('ACCEPTED_LOCALLY','TRANSMITTED','UNKNOWN','FAILED_RETRYABLE') AND issuedAtMs < :cutoffMs")
    suspend fun expireOlderThan(endpointId: String, cutoffMs: Long, atMs: Long): Int

    @Query("SELECT COUNT(*) FROM outbox WHERE endpointId = :endpointId AND state = :state")
    suspend fun countByState(endpointId: String, state: String): Int

    /** N-BE-2: purge terminal rows (full payloads at rest) past the retention window. */
    @Query(
        "DELETE FROM outbox WHERE endpointId = :endpointId AND state IN " +
            "('ACKED','FAILED_TERMINAL','FAILED_STALE','EXPIRED') AND lastTransitionAtMs < :cutoffMs",
    )
    suspend fun deleteTerminalBefore(endpointId: String, cutoffMs: Long): Int
}

@Dao
interface JournalDao {
    @Upsert
    suspend fun putState(state: JournalStateEntity)

    @Query("SELECT * FROM journal_state WHERE endpointId = :endpointId")
    suspend fun state(endpointId: String): JournalStateEntity?

    @Query("DELETE FROM journal_subscriptions WHERE endpointId = :endpointId")
    suspend fun clearSubscriptions(endpointId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSubscriptions(rows: List<JournalSubscriptionEntity>)

    @Query("SELECT * FROM journal_subscriptions WHERE endpointId = :endpointId ORDER BY topic ASC")
    suspend fun subscriptions(endpointId: String): List<JournalSubscriptionEntity>

    @Query("DELETE FROM journal_pending_commands WHERE endpointId = :endpointId")
    suspend fun clearPending(endpointId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPending(rows: List<JournalPendingCommandEntity>)

    @Query("SELECT * FROM journal_pending_commands WHERE endpointId = :endpointId ORDER BY issuedAtMs ASC")
    suspend fun pending(endpointId: String): List<JournalPendingCommandEntity>

    @Query("DELETE FROM journal_state WHERE endpointId = :endpointId")
    suspend fun deleteState(endpointId: String): Int

    @Transaction
    suspend fun clearEndpoint(endpointId: String) {
        deleteState(endpointId)
        clearSubscriptions(endpointId)
        clearPending(endpointId)
    }
}

@Dao
interface DraftDao {
    @Upsert
    suspend fun upsert(entity: DraftEntity)

    @Query("SELECT * FROM drafts WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey AND taskId = :taskId")
    fun observe(endpointId: String, workspaceKey: String, taskId: String): Flow<DraftEntity?>

    @Query("SELECT * FROM drafts WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey AND taskId = :taskId")
    suspend fun get(endpointId: String, workspaceKey: String, taskId: String): DraftEntity?

    @Query("DELETE FROM drafts WHERE endpointId = :endpointId AND workspaceKey = :workspaceKey AND taskId = :taskId")
    suspend fun delete(endpointId: String, workspaceKey: String, taskId: String): Int
}
