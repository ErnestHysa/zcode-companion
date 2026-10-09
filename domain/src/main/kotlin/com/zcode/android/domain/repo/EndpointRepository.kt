package com.zcode.android.domain.repo

import com.zcode.android.domain.ids.EndpointId
import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.model.Endpoint
import kotlinx.coroutines.flow.Flow

/**
 * Endpoint registry + app preferences persistence (§4.6/§17.2): DataStore-proto
 * stores. Endpoint descriptors are metadata-only — credential material stays behind
 * the sealed envelope store (core:security CredentialStore port; §12.4).
 */
interface EndpointRepository {
    fun observeEndpoints(): Flow<List<Endpoint>>
    suspend fun endpoints(): List<Endpoint>
    suspend fun save(endpoint: Endpoint)
    suspend fun delete(endpointId: EndpointId)
    suspend fun deleteAll()
}

/** §17.2/§27 preference store (user-visible privacy + notification posture). */
interface PreferencesRepository {
    fun observe(): Flow<AppPreferences>
    suspend fun current(): AppPreferences
    suspend fun update(transform: (AppPreferences) -> AppPreferences)
}
