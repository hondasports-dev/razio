package dev.hondasports.razio.audio

import android.util.Log

internal object AudioEffectLog {
    const val TAG = "RAZIO/AudioEffect"

    fun i(message: String) {
        Log.i(TAG, message)
    }

    fun e(
        message: String,
        throwable: Throwable? = null,
    ) {
        if (throwable == null) {
            Log.e(TAG, message)
        } else {
            Log.e(TAG, message, throwable)
        }
    }
}
