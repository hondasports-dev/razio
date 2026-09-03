package dev.hondasports.razio.audio

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.hondasports.razio.audio.preset.AudioPreset
import dev.hondasports.razio.audio.preset.AudioPresetTuning
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

    suspend fun savedTunings(): Map<AudioPreset, AudioPresetTuning> {
        val prefs = dataStore.data.first()
        return AudioPreset.entries.associateWith { preset ->
            AudioPresetTuning.decode(
                raw = prefs[tuningKey(preset)],
                fallback = preset.defaultTuning(),
            )
        }
    }

    suspend fun setTuning(
        preset: AudioPreset,
        tuning: AudioPresetTuning,
    ) {
        dataStore.edit { prefs ->
            prefs[tuningKey(preset)] = tuning.sanitized().encoded()
        }
    }

    suspend fun savedNoiseSettings(): NoiseOverlaySettings {
        val prefs = dataStore.data.first()
        return NoiseOverlaySettings(
            hissEnabled = prefs[HISS_ENABLED] ?: false,
            crackleEnabled = prefs[CRACKLE_ENABLED] ?: false,
            hissGainDb = prefs[HISS_GAIN_DB]
                ?: prefs[HISS_LEVEL]?.let(NoiseGainRange::fromLegacyLevel)
                ?: NoiseGainRange.DEFAULT_DB,
            crackleGainDb = prefs[CRACKLE_GAIN_DB]
                ?: prefs[CRACKLE_LEVEL]?.let(NoiseGainRange::fromLegacyLevel)
                ?: NoiseGainRange.DEFAULT_DB,
        )
    }

    suspend fun setNoiseSettings(settings: NoiseOverlaySettings) {
        dataStore.edit { prefs ->
            prefs[HISS_ENABLED] = settings.hissEnabled
            prefs[CRACKLE_ENABLED] = settings.crackleEnabled
            prefs[HISS_GAIN_DB] = NoiseGainRange.sanitize(settings.hissGainDb)
            prefs[CRACKLE_GAIN_DB] = NoiseGainRange.sanitize(settings.crackleGainDb)
            prefs.remove(HISS_LEVEL)
            prefs.remove(CRACKLE_LEVEL)
        }
    }

    private companion object {
        val POWER_ON = booleanPreferencesKey("power_on")
        val PRESET_ID = stringPreferencesKey("preset_id")
        val HISS_ENABLED = booleanPreferencesKey("hiss_enabled")
        val CRACKLE_ENABLED = booleanPreferencesKey("crackle_enabled")
        val HISS_GAIN_DB = floatPreferencesKey("hiss_gain_db")
        val CRACKLE_GAIN_DB = floatPreferencesKey("crackle_gain_db")
        val HISS_LEVEL = floatPreferencesKey("hiss_level")
        val CRACKLE_LEVEL = floatPreferencesKey("crackle_level")

        fun tuningKey(preset: AudioPreset) = stringPreferencesKey("tuning_${preset.id}")
    }
}
