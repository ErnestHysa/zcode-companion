package com.zcode.android.app

import androidx.room.Room
import androidx.datastore.core.DataStoreFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.data.store.AppPreferencesSerializer
import com.zcode.android.data.store.DataStoreCredentialStore
import com.zcode.android.data.store.CredentialEnvelopeSerializer
import com.zcode.android.data.store.EndpointRepositoryImpl
import com.zcode.android.data.store.EndpointStoreSerializer
import com.zcode.android.data.store.PreferencesRepositoryImpl
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * §43.2 Phase 4 acceptance — "ADR-008 revisit triggers evaluated with measurements"
 * (§17.2: drop-to-DataStore ADR path if Room adds > 100 ms to cold start; Q15).
 *
 * Measures ON DEVICE (API-30 ATD emulator): Room open + first DAO write/read, the
 * DataStore first-read cost for the three proto stores, and an outbox accept+apply
 * round-trip. Room initialization is lazy by construction, so the cold-start-relevant
 * number is open + first-touch — exactly what is measured here. The full app cold
 * start (§28.1 budget ≤ 1.2 s P50) is a Phase 6/11 Macrobenchmark gate.
 */
@RunWith(AndroidJUnit4::class)
class PersistenceTimingInstrumentedTest {

    private inline fun ms(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    @Test
    fun measurePersistenceFirstTouch() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val report = StringBuilder("\n==== PHASE-4 ADR-008 MEASUREMENT (device) ====\n")

        // --- Room: open + first write + first read (lazy init = cold-start cost)
        val name = "timing-${System.nanoTime()}.db"
        var db: ZcodeDatabase? = null
        val roomOpen = ms {
            db = Room.databaseBuilder(context, ZcodeDatabase::class.java, name)
                .allowMainThreadQueries().build()
        }
        val dao = db!!.taskDao()
        val roomFirstWrite = ms {
            runBlocking {
                dao.putMeta(com.zcode.android.data.db.TaskWorkspaceMetaEntity("ep", "ws", "\"e\"", 1))
            }
        }
        val roomFirstRead = ms { runBlocking { dao.meta("ep", "ws") } }
        val roomRepoWrite = ms {
            runBlocking {
                OutboxRepositoryImpl(db!!).accept(
                    EndpointId("ep"), "s1", "sendText", "{}", null, null, System.currentTimeMillis(),
                )
            }
        }
        report.appendLine("room.openMs=$roomOpen")
        report.appendLine("room.firstWriteMs=$roomFirstWrite")
        report.appendLine("room.firstReadMs=$roomFirstRead")
        report.appendLine("room.outboxAcceptMs=$roomRepoWrite")

        // --- DataStore: first read per proto store (cold-start cost of the registry)
        val dir = File(context.noBackupFilesDir, "timing-${System.nanoTime()}").apply { mkdirs() }
        val credentialStore = DataStoreCredentialStore(
            DataStoreFactory.create(CredentialEnvelopeSerializer, produceFile = { File(dir, "cred.pb") }),
        )
        val dsCredentialFirstRead = ms { credentialStore.listNames() }
        val endpointRepo = EndpointRepositoryImpl(
            DataStoreFactory.create(EndpointStoreSerializer, produceFile = { File(dir, "ep.pb") }),
        )
        val dsEndpointFirstRead = ms { endpointRepo.endpoints() }
        val prefsRepo = PreferencesRepositoryImpl(
            DataStoreFactory.create(AppPreferencesSerializer, produceFile = { File(dir, "prefs.pb") }),
        )
        val dsPrefsFirstRead = ms { prefsRepo.current() }
        val dsEndpointSave = ms {
            endpointRepo.save(
                Endpoint(
                    endpointId = EndpointId("t-1"), kind = EndpointKind.RELAY_ZAI,
                    displayName = "t", wsUrl = "wss://x", pairedAtMs = 1,
                ),
            )
        }
        report.appendLine("datastore.credentialFirstReadMs=$dsCredentialFirstRead")
        report.appendLine("datastore.endpointFirstReadMs=$dsEndpointFirstRead")
        report.appendLine("datastore.prefsFirstReadMs=$dsPrefsFirstRead")
        report.appendLine("datastore.endpointSaveMs=$dsEndpointSave")

        // --- ADR-008 revisit-trigger verdict (> 100 ms on cold start = reconsider)
        val totalFirstTouch = roomOpen + roomFirstWrite + roomFirstRead +
            dsCredentialFirstRead + dsEndpointFirstRead + dsPrefsFirstRead
        report.appendLine("persistence.firstTouchTotalMs=$totalFirstTouch")
        report.appendLine("adr008.trigger(>100ms cold-start delta): ${if (totalFirstTouch > 100) "TRIPPED — evaluate drop-to-DataStore" else "NOT TRIPPED — Room split confirmed"}")
        report.appendLine("==== END MEASUREMENT ====")
        println(report)

        db!!.close()
    }
}
