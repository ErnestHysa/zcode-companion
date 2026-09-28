package com.zcode.android.data.store

import androidx.datastore.core.DataStore
import com.zcode.android.data.StorageCorruption
import com.zcode.android.data.proto.AppPreferencesProto
import com.zcode.android.data.proto.EndpointDescriptorProto
import com.zcode.android.data.proto.EndpointStoreProto
import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.Endpoint
import com.zcode.android.domain.model.EndpointKind
import com.zcode.android.domain.repo.EndpointRepository
import com.zcode.android.domain.repo.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * DataStore-proto endpoint registry + preference store (§4.6/§17.2). Additive-first
 * mapping (§32.3): absent optional fields fall back to domain defaults; enum values
 * unknown to this build fail closed to the documented default (never guessed).
 */
class EndpointRepositoryImpl(private val dataStore: DataStore<EndpointStoreProto>) : EndpointRepository {

    override fun observeEndpoints(): Flow<List<Endpoint>> =
        dataStore.data.map { it.endpointsList.map { p -> p.toDomain() } }

    override suspend fun endpoints(): List<Endpoint> = dataStore.data.first().endpointsList.map { it.toDomain() }

    override suspend fun save(endpoint: Endpoint) {
        dataStore.updateData { store ->
            val others = store.endpointsList.filter { it.endpointId != endpoint.endpointId.raw }
            store.toBuilder()
                .clearEndpoints()
                .addAllEndpoints(others)
                .addEndpoints(endpoint.toProto())
                .setSchemaVersion(SCHEMA_VERSION)
                .build()
        }
    }

    override suspend fun delete(endpointId: EndpointId) {
        dataStore.updateData { store ->
            store.toBuilder()
                .clearEndpoints()
                .addAllEndpoints(store.endpointsList.filter { it.endpointId != endpointId.raw })
                .build()
        }
    }

    override suspend fun deleteAll() {
        dataStore.updateData { it.toBuilder().clearEndpoints().build() }
    }

    private fun EndpointDescriptorProto.toDomain(): Endpoint {
        val id = endpointId ?: throw StorageCorruption("endpoint row without endpoint_id")
        val kind = when (kind) {
            "DIRECT_SERVER" -> EndpointKind.DIRECT_SERVER
            "RELAY_ZAI", null, "" -> EndpointKind.RELAY_ZAI // relay is the verified default class
            else -> throw StorageCorruption("endpoint kind `$kind` unknown")
        }
        return Endpoint(
            endpointId = EndpointId(id),
            kind = kind,
            displayName = displayName.orEmpty(),
            wsUrl = wsUrl.orEmpty(),
            pairedAtMs = pairedAtMs ?: 0L,
            deviceSid = deviceSid,
            deviceMid = deviceMid,
            desktopAppVersion = desktopAppVersion,
            candidates = candidatesList,
            workspacePath = workspacePath,
            workspaceIdentity = workspaceIdentity,
            lastHealthyAtMs = if (hasLastHealthyAtMs()) lastHealthyAtMs else null,
        )
    }

    private fun Endpoint.toProto(): EndpointDescriptorProto {
        // NOTE: no `apply {}` here — the generated builder exposes properties with the
        // SAME names (deviceSid, …), which shadow the Endpoint's fields inside the
        // lambda and write empty strings (caught by the round-trip test).
        val builder = EndpointDescriptorProto.newBuilder()
            .setEndpointId(endpointId.raw)
            .setKind(kind.name)
            .setDisplayName(displayName)
            .setWsUrl(wsUrl)
            .setPairedAtMs(pairedAtMs)
        deviceSid?.let { builder.deviceSid = it }
        deviceMid?.let { builder.deviceMid = it }
        desktopAppVersion?.let { builder.desktopAppVersion = it }
        builder.addAllCandidates(candidates)
        workspacePath?.let { builder.workspacePath = it }
        workspaceIdentity?.let { builder.workspaceIdentity = it }
        lastHealthyAtMs?.let { builder.lastHealthyAtMs = it }
        return builder.build()
    }

    companion object { const val SCHEMA_VERSION = 1 }
}

class PreferencesRepositoryImpl(private val dataStore: DataStore<AppPreferencesProto>) : PreferencesRepository {

    override fun observe(): Flow<AppPreferences> = dataStore.data.map { it.toDomain() }

    override suspend fun current(): AppPreferences = dataStore.data.first().toDomain()

    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        dataStore.updateData { proto -> transform(proto.toDomain()).toProto() }
    }

    private fun AppPreferencesProto.toDomain(): AppPreferences = AppPreferences(
        notificationContentLevel = when (notificationContentLevel) {
            "CONTENT" -> AppPreferences.NotificationContentLevel.CONTENT
            else -> AppPreferences.NotificationContentLevel.MINIMAL // absent/unknown → §24.2 default
        },
        // §24.1/§27 privacy defaults when the field was never written (additive-first:
        // unset optional field → domain default, never the proto zero-value).
        screenshotProtection = if (hasScreenshotProtection()) screenshotProtection else true,
        conversationCacheEnabled = if (hasConversationCacheEnabled()) conversationCacheEnabled else true,
    )

    private fun AppPreferences.toProto() = AppPreferencesProto.newBuilder()
        .setSchemaVersion(EndpointRepositoryImpl.SCHEMA_VERSION)
        .setNotificationContentLevel(notificationContentLevel.name)
        .setScreenshotProtection(screenshotProtection)
        .setConversationCacheEnabled(conversationCacheEnabled)
        .build()
}
