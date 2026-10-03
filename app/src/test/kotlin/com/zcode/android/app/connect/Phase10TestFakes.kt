package com.zcode.android.app.connect

import com.zcode.android.domain.model.AppPreferences
import com.zcode.android.domain.repo.PreferencesRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** Phase 10 fake — in-memory §17.2 preferences (selectedEndpointId included). */
internal class FakePreferencesRepository : PreferencesRepository {
    private val state = MutableStateFlow(AppPreferences())
    override fun observe(): Flow<AppPreferences> = state.map { it }
    override suspend fun current(): AppPreferences = state.value
    override suspend fun update(transform: (AppPreferences) -> AppPreferences) {
        state.value = transform(state.value)
    }
}
