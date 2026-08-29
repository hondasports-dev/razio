package dev.hondasports.razio.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hondasports.razio.audio.preset.AudioPreset
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

    val preset: Flow<AudioPreset> = dataStore.data.map { prefs ->
        AudioPreset.fromId(prefs[PRESET_ID])
    }

    suspend fun savedPowerOn(): Boolean = powerOn.first()

    suspend fun setPowerOn(value: Boolean) {
        dataStore.edit { prefs ->
            prefs[POWER_ON] = value
        }
    }

    suspend fun savedPreset(): AudioPreset = preset.first()

    suspend fun setPreset(value: AudioPreset) {
        dataStore.edit { prefs ->
            prefs[PRESET_ID] = value.id
        }
    }

    private companion object {
        val POWER_ON = booleanPreferencesKey("power_on")
        val PRESET_ID = stringPreferencesKey("preset_id")
    }
}
