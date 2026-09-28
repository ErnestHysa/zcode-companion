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
    fun `current build opens schema v1 directly`() {
        val db = Room.databaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ZcodeDatabase::class.java,
            "direct-open-test.db",
        ).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        assertEquals(1, db.openHelper.writableDatabase.version)
        db.close()
    }

    companion object {
        private const val DB = "migration-test.db"
    }
}
