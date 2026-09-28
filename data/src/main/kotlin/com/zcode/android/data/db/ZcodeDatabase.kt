package com.zcode.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * ZcodeDatabase — schema v1 (§43.2-4.2 exit gate: "schema v1 frozen"). Exports to
 * data/schemas/ per §32.3; all future migrations are forward migrations with tests
 * from every shipped version; NEVER destructive for outbox/journal (§32.3).
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        TaskEntity::class,
        TaskWorkspaceMetaEntity::class,
        ConversationSessionMetaEntity::class,
        ConversationRowEntity::class,
        InteractionEntity::class,
        OutboxEntity::class,
        JournalStateEntity::class,
        JournalSubscriptionEntity::class,
        JournalPendingCommandEntity::class,
        DraftEntity::class,
    ],
)
abstract class ZcodeDatabase : RoomDatabase() {
    abstract fun taskDao(): TaskDao
    abstract fun conversationDao(): ConversationDao
    abstract fun interactionDao(): InteractionDao
    abstract fun outboxDao(): OutboxDao
    abstract fun journalDao(): JournalDao
    abstract fun draftDao(): DraftDao

    companion object {
        const val NAME = "zcode.db"
    }
}
