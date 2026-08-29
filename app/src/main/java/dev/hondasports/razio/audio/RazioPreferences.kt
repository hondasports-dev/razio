package dev.hondasports.razio.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.razioDataStore: DataStore<Preferences> by preferencesDataStore(name = "razio")

class RazioPreferences(
    context: Context,
) {
    private val dataStore = context.applicationContext.razioDataStore

    val powerOn: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[POWER_ON] ?: false
    }

    suspend fun savedPowerOn(): Boolean = powerOn.first()

    suspend fun setPowerOn(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[POWER_ON] = value
        }
    }

    private companion object {
        val POWER_ON = booleanPreferencesKey("power_on")
    }
}
