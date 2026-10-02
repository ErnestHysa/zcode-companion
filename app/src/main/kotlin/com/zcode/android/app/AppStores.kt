package com.zcode.android.app

import android.content.Context
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.room.Room
import com.zcode.android.core.notifications.NotificationBroker
import com.zcode.android.data.db.ZcodeDatabase
import com.zcode.android.data.proto.NotificationRecordStoreProto
import com.zcode.android.data.repo.ConversationRepositoryImpl
import com.zcode.android.data.repo.DraftRepositoryImpl
import com.zcode.android.data.repo.InteractionRepositoryImpl
import com.zcode.android.data.repo.JournalRepositoryImpl
import com.zcode.android.data.repo.OutboxRepositoryImpl
import com.zcode.android.data.repo.TaskRepositoryImpl
import com.zcode.android.data.store.AppPreferencesSerializer
import com.zcode.android.data.store.CredentialEnvelopeSerializer
import com.zcode.android.data.store.DataStoreCredentialStore
import com.zcode.android.data.store.DataStoreNotificationRecordStore
import com.zcode.android.data.store.EndpointRepositoryImpl
import com.zcode.android.data.store.EndpointStoreSerializer
import com.zcode.android.data.store.NotificationRecordStoreImpl
import com.zcode.android.data.store.PreferencesRepositoryImpl
import com.zcode.android.domain.repo.ConversationRepository
import com.zcode.android.domain.repo.DraftRepository
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.repo.InteractionRepository
import com.zcode.android.domain.repo.JournalRepository
import com.zcode.android.domain.repo.OutboxRepository
import com.zcode.android.domain.repo.PreferencesRepository
import com.zcode.android.domain.repo.TaskRepository
import java.io.File

/**
 * Process-scoped persistence stores (§43.2 Phase 4 wiring). Room databases and
 * DataStore files are SINGLE-INSTANCE per process (androidx contract: two active
 * DataStore instances on one file crash); activities recreate freely, so the stores
 * live in a lazy holder rooted at the applicationContext. §41.10 allows exactly one
 * global — the AppContainer root; this holder hangs off it.
 *
 * Phase 8: the process also owns the §24 NotificationBroker (its dedupe records +
 * tray state must survive activity recreation) and the process-wide [appScope]
 * that runs the broker's event collectors (they outlive any single activity the
 * way the notification promise does — §24.4 "while the app is in use").
 */
class AppStores private constructor(appContext: Context) {

    val database: ZcodeDatabase = Room.databaseBuilder(appContext, ZcodeDatabase::class.java, ZcodeDatabase.NAME)
        .addMigrations(ZcodeDatabase.MIGRATION_1_2)
        .build()

    // DataStore files under noBackupFilesDir — the §12.4/§27 conservative default
    // (allowBackup=false already excludes everything; this is belt-and-braces).
    private val datastoreDir: File = File(appContext.noBackupFilesDir, "datastore").apply { mkdirs() }

    /** §12.4: sealed credential envelopes — corruption fails CLOSED (no replace handler). */
    val credentialStore = DataStoreCredentialStore(
        DataStoreFactory.create(
            serializer = CredentialEnvelopeSerializer,
            produceFile = { File(datastoreDir, "credential_envelope.pb") },
        ),
    )

    /** Endpoint descriptors (§11.3) — cache-class: corruption → fresh init (§32.3). */
    private val endpointDataStore = DataStoreFactory.create(
        serializer = EndpointStoreSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { com.zcode.android.data.proto.EndpointStoreProto.getDefaultInstance() },
        produceFile = { File(datastoreDir, "endpoint_store.pb") },
    )

    /** App preferences (§17.2/§27) — cache-class re-derivable defaults. */
    private val preferencesDataStore = DataStoreFactory.create(
        serializer = AppPreferencesSerializer,
        corruptionHandler = ReplaceFileCorruptionHandler { com.zcode.android.data.proto.AppPreferencesProto.getDefaultInstance() },
        produceFile = { File(datastoreDir, "preferences.pb") },
    )

    /** §16 NotificationRecords — dedupe/reconciliation keys (cache-class). */
    private val notificationRecordDataStore = DataStoreFactory.create(
        serializer = DataStoreNotificationRecordStore,
        corruptionHandler = ReplaceFileCorruptionHandler { NotificationRecordStoreProto.getDefaultInstance() },
        produceFile = { File(datastoreDir, "notification_records.pb") },
    )

    val taskRepository: TaskRepository = TaskRepositoryImpl(database)
    val conversationRepository: ConversationRepository = ConversationRepositoryImpl(database)
    val interactionRepository: InteractionRepository = InteractionRepositoryImpl(database)
    val outboxRepository: OutboxRepository = OutboxRepositoryImpl(database)
    val journalRepository: JournalRepository = JournalRepositoryImpl(database)
    val draftRepository: DraftRepository = DraftRepositoryImpl(database)
    val endpointRepository: EndpointRepository = EndpointRepositoryImpl(endpointDataStore)
    val preferencesRepository: PreferencesRepository = PreferencesRepositoryImpl(preferencesDataStore)

    /** Process-wide scope for the Phase 8 broker collectors (§28.5 single-threaded broker). */
    val appScope: kotlinx.coroutines.CoroutineScope =
        kotlinx.coroutines.CoroutineScope(
            kotlinx.coroutines.SupervisorJob() +
                kotlinx.coroutines.Dispatchers.Default +
                kotlinx.coroutines.CoroutineName("zcode-app-stores"),
        )

    /** Process-scoped §24.1 broker (single instance — notifications are process truth). */
    val notificationBroker: NotificationBroker by lazy {
        NotificationBroker(
            sink = com.zcode.android.core.notifications.AndroidNotificationSink(appContext),
            records = NotificationRecordStoreImpl(notificationRecordDataStore),
            // Logcat-only logger (the diagnostics ring lives in the activity-scoped
            // AppContainer; notification lifecycle is INFO-level and never carries
            // content — scrubbers still apply).
            log = com.zcode.android.core.common.log.ZLog(
                sink = { entry ->
                    val line = "${entry.event} :: ${entry.details ?: ""}"
                    android.util.Log.println(
                        when (entry.level) {
                            com.zcode.android.core.common.log.ZLog.LogLevel.DEBUG -> android.util.Log.DEBUG
                            com.zcode.android.core.common.log.ZLog.LogLevel.INFO -> android.util.Log.INFO
                            com.zcode.android.core.common.log.ZLog.LogLevel.WARN -> android.util.Log.WARN
                            com.zcode.android.core.common.log.ZLog.LogLevel.ERROR -> android.util.Log.ERROR
                        },
                        "ZCODE-NOTIFY",
                        line,
                    )
                },
            ),
        )
    }

    companion object {
        @Volatile
        private var instance: AppStores? = null

        fun get(appContext: Context): AppStores =
            instance ?: synchronized(this) {
                instance ?: AppStores(appContext.applicationContext).also { instance = it }
            }
    }
}
