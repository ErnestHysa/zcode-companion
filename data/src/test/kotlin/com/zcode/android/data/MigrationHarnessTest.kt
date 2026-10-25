package com.zcode.android.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.data.db.ZcodeDatabase
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * §43.2-4.2 migration drill harness (§32.3): schema v1 is FROZEN and every future
 * migration must (a) ship an export, (b) carry a test from every prior shipped
 * version, (c) never be destructive for outbox/journal. The harness itself is proven
 * green here: create-at-v1, validate-open-at-v1, and reopen integrity.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30]) // ATD-class API like the Phase-3 device leg (SDK 37 sandbox needs Java 21)
class MigrationHarnessTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        ZcodeDatabase::class.java,
    )

    @Test
    fun `harness - create at v1 and validate open at v1`() {
        helper.createDatabase(DB, 1).use { db ->
            // v1 schema is live: outbox + journal tables exist (non-destructive classes)
            db.execSQL(
                "INSERT INTO outbox (commandId, endpointId, sessionId, type, payloadJson, baseRevision," +
                    "baseLogEpoch, issuedAtMs, state, attempts, lastTransitionAtMs, ackStatus, ackReasonCode," +
                    "ackMessage, ackRevisionAtDecision) VALUES ('c1','ep',NULL,'stop','{}',NULL,NULL,1," +
                    "'ACCEPTED_LOCALLY',0,1,NULL,NULL,NULL,NULL)",
            )
            db.execSQL(
                "INSERT INTO journal_state (endpointId, helloServerTimeMs, capabilitiesJson, version, updatedAtMs)" +
                    " VALUES ('ep',1.0,'{}',1,1)",
            )
        }
        helper.runMigrationsAndValidate(DB, 1, true).use { db ->
            db.query("SELECT commandId, state FROM outbox").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("c1", cursor.getString(0))
                assertEquals("ACCEPTED_LOCALLY", cursor.getString(1))
            }
        }
    }

    @Test
    fun `current build opens current schema directly`() {
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ZcodeDatabase::class.java,
            "direct-open-test.db",
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        assertEquals(3, db.openHelper.writableDatabase.version)
        db.close()
    }

    /**
     * §43.2-6.2 schema v1 → v2 drill (Phase 6): tasks.workspaceLabel is additive and
     * null-safe — pre-existing v1 rows survive with NULL label; v2 writes carry it.
     */
    @Test
    fun `migration 1 to 2 adds workspaceLabel without data loss`() {
        helper.createDatabase(DB_V12, 1).use { db ->
            db.execSQL(
                "INSERT INTO tasks (endpointId, workspaceKey, taskId, title, statusWire, mode, model," +
                    "archivedAtMs, additions, deletions, files, pinned, createdAtMs, updatedAtMs) VALUES " +
                    "('ep','/ws','t1','Task one','running',NULL,NULL,NULL,NULL,NULL,NULL,0,1,2)",
            )
        }
        helper.runMigrationsAndValidate(DB_V12, 2, true, ZcodeDatabase.MIGRATION_1_2).use { db ->
            // Pre-existing row survives with a NULL label (null-safe additive column).
            db.query("SELECT taskId, title, workspaceLabel FROM tasks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("t1", cursor.getString(0))
                assertEquals("Task one", cursor.getString(1))
                assertEquals(null, cursor.getString(2))
            }
            // v2 rows carry the display label.
            db.execSQL(
                "INSERT INTO tasks (endpointId, workspaceKey, taskId, title, statusWire, mode, model," +
                    "archivedAtMs, additions, deletions, files, pinned, createdAtMs, updatedAtMs, workspaceLabel)" +
                    " VALUES ('ep','/ws','t2','Task two','idle',NULL,NULL,NULL,NULL,NULL,NULL,0,3,4,'demo-api')",
            )
            db.query("SELECT workspaceLabel FROM tasks WHERE taskId = 't2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("demo-api", cursor.getString(0))
            }
        }
    }

    /**
     * Phase 5A (A3.8) schema v2 → v3 drill: tasks.unreadAtMs is additive and null-safe —
     * pre-existing v2 rows survive as READ (NULL), v3 writes carry the desktop's marker;
     * and the whole forward chain v1 → v3 runs through ALL_MIGRATIONS.
     */
    @Test
    fun `migration 2 to 3 adds unreadAtMs without data loss and the full chain from v1 runs`() {
        helper.createDatabase(DB_V23, 2).use { db ->
            db.execSQL(
                "INSERT INTO tasks (endpointId, workspaceKey, taskId, title, statusWire, mode, model," +
                    "archivedAtMs, additions, deletions, files, pinned, createdAtMs, updatedAtMs, workspaceLabel) VALUES " +
                    "('ep','/ws','t1','Task one','running',NULL,NULL,NULL,NULL,NULL,NULL,1,1,2,'demo')",
            )
        }
        helper.runMigrationsAndValidate(DB_V23, 3, true, ZcodeDatabase.MIGRATION_2_3).use { db ->
            db.query("SELECT taskId, pinned, unreadAtMs FROM tasks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("t1", cursor.getString(0))
                assertEquals(1, cursor.getInt(1), "pinned survives the migration")
                assertTrue(cursor.isNull(2), "pre-v3 rows are READ (NULL unreadAtMs)")
            }
            db.execSQL(
                "INSERT INTO tasks (endpointId, workspaceKey, taskId, title, statusWire, mode, model," +
                    "archivedAtMs, additions, deletions, files, pinned, createdAtMs, updatedAtMs, workspaceLabel, unreadAtMs)" +
                    " VALUES ('ep','/ws','t2','Task two','idle',NULL,NULL,NULL,NULL,NULL,NULL,0,3,4,'demo',1700000000000)",
            )
            db.query("SELECT unreadAtMs FROM tasks WHERE taskId = 't2'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1700000000000L, cursor.getLong(0))
            }
        }
        // The full chain from the frozen v1 export.
        helper.createDatabase(DB_V13, 1).use { db ->
            db.execSQL(
                "INSERT INTO tasks (endpointId, workspaceKey, taskId, title, statusWire, mode, model," +
                    "archivedAtMs, additions, deletions, files, pinned, createdAtMs, updatedAtMs) VALUES " +
                    "('ep','/ws','t0','Task zero','idle',NULL,NULL,NULL,NULL,NULL,NULL,0,1,2)",
            )
        }
        helper.runMigrationsAndValidate(DB_V13, 3, true, *ZcodeDatabase.ALL_MIGRATIONS).use { db ->
            db.query("SELECT taskId, workspaceLabel, unreadAtMs FROM tasks").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("t0", cursor.getString(0))
                assertTrue(cursor.isNull(1))
                assertTrue(cursor.isNull(2))
            }
        }
    }

    companion object {
        private const val DB = "migration-test.db"
        private const val DB_V12 = "migration-v1-2.db"
        private const val DB_V23 = "migration-v2-3.db"
        private const val DB_V13 = "migration-v1-3.db"
    }
}
