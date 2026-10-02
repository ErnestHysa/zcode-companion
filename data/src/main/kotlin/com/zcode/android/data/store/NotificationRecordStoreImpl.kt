package com.zcode.android.data.store

import androidx.datastore.core.DataStore
import com.zcode.android.data.proto.NotificationRecordProto
import com.zcode.android.data.proto.NotificationRecordStoreProto
import com.zcode.android.domain.model.NotificationRecord
import kotlinx.coroutines.flow.first

/**
 * DataStore-proto binding of the §16 NotificationRecord store (the broker's
 * [com.zcode.android.core.notifications.NotificationBroker.RecordStore] port — the
 * §9.2 data → core/notifications edge). Corruption → fresh init (§32.3 cache-class:
 * lost records only degrade dedupe, never credentials or projections).
 */
class NotificationRecordStoreImpl(
    private val dataStore: DataStore<NotificationRecordStoreProto>,
) : com.zcode.android.core.notifications.NotificationBroker.RecordStore {

    override suspend fun load(): List<NotificationRecord> =
        dataStore.data.first().recordsList.map { it.toDomain() }

    override suspend fun save(all: List<NotificationRecord>) {
        dataStore.updateData { current ->
            NotificationRecordStoreProto.newBuilder()
                .setSchemaVersion(current.schemaVersion.coerceAtLeast(SCHEMA_VERSION))
                .addAllRecords(all.map { it.toProto() })
                .build()
        }
    }

    private fun NotificationRecordProto.toDomain() = NotificationRecord(
        notificationKey = notificationKey,
        taskId = if (hasTaskId()) taskId else null,
        kind = when (kind) {
            "APPROVAL_NEEDED" -> NotificationRecord.Kind.APPROVAL_NEEDED
            "USER_INPUT_NEEDED" -> NotificationRecord.Kind.USER_INPUT_NEEDED
            "TASK_FINISHED" -> NotificationRecord.Kind.TASK_FINISHED
            "TASK_FAILED" -> NotificationRecord.Kind.TASK_FAILED
            "CONNECTION_LOST" -> NotificationRecord.Kind.CONNECTION_LOST
            else -> NotificationRecord.Kind.CONNECTION_LOST // tolerant: drop-class, never crash (INV-8)
        },
        createdAtMs = createdAtMs,
        seenAtMs = if (hasSeenAtMs()) seenAtMs else null,
        interactionId = if (hasInteractionId()) interactionId else null,
    )

    private fun NotificationRecord.toProto() = NotificationRecordProto.newBuilder()
        .setNotificationKey(notificationKey)
        .setKind(kind.name)
        .setTaskId(taskId ?: "")
        .setInteractionId(interactionId ?: "")
        .setCreatedAtMs(createdAtMs)
        .setSeenAtMs(seenAtMs ?: 0L)
        .build()

    companion object {
        const val SCHEMA_VERSION = 1
    }
}
