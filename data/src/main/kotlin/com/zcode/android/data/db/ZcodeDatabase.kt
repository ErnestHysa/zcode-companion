package com.zcode.android.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

/**
 * ZcodeDatabase — schema (§43.2-4.2 exit gate: "schema v1 frozen" at Phase 4).
 * Exports to data/schemas/ per §32.3; all future migrations are forward migrations
 * with tests from every shipped version; NEVER destructive for outbox/journal (§32.3).
 *
 * v1 → v2 (Phase 6, §43.2-6.2): tasks.workspaceLabel — additive display-only column
 * (INV-12: never an identity); forward ALTER TABLE, null-safe for existing rows.
 *
 * v2 → v3 (gaps-and-polish Phase 5A, A3.8): tasks.unreadAtMs — the desktop's unread
 * marker; additive + null-safe (NULL = read, which is what every pre-v3 row was).
 */
@Database(
    version = 3,
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

        /** v1 → v2 (Phase 6): tasks.workspaceLabel — additive, null-safe (§32.3). */
        val MIGRATION_1_2 = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN workspaceLabel TEXT")
            }
        }

        /** v2 → v3 (Phase 5A, A3.8): tasks.unreadAtMs — additive, null-safe (§32.3). */
        val MIGRATION_2_3 = object : androidx.room.migration.Migration(2, 3) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN unreadAtMs INTEGER")
            }
        }

        /** Every forward migration, in order — the one list the app builder registers. */
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}
