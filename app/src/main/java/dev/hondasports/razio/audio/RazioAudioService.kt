package dev.hondasports.razio.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hondasports.razio.MainActivity
import dev.hondasports.razio.R
import dev.hondasports.razio.RazioApp

class RazioAudioService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        val readyReceiver = readyReceiver(intent)
        when (intent?.action) {
            ACTION_START -> {
                if (intent.getBooleanExtra(EXTRA_MEDIA_PROJECTION, false)) {
                    when (intent.getIntExtra(EXTRA_PROJECTION_OWNER, OWNER_SPECTRUM)) {
                        OWNER_MONO -> monoRequested = true
                        else -> spectrumRequested = true
                    }
                } else {
                    effectRequested = true
                }
            }
            ACTION_STOP_EFFECT -> effectRequested = false
            ACTION_STOP_SPECTRUM -> spectrumRequested = false
            ACTION_STOP_MONO -> monoRequested = false
            null -> effectRequested = true
        }
        if (!effectRequested && !spectrumRequested && !monoRequested) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
            AudioEffectLog.i("fgs stopped: no owners")
            return START_NOT_STICKY
        }
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                var foregroundTypes = 0
                if (effectRequested) {
                    foregroundTypes = foregroundTypes or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                }
                if (spectrumRequested || monoRequested) {
                    foregroundTypes = foregroundTypes or
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                }
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    foregroundTypes,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AudioEffectLog.i("fgs started")
            readyReceiver?.send(READY_OK, Bundle.EMPTY)
            // A MediaProjection token and the native AudioRecord/AudioTrack chain cannot be
            // reconstructed from a null restart intent. Do not resurrect a stale Mono owner.
            if (monoRequested) START_NOT_STICKY else START_STICKY
        } catch (throwable: Throwable) {
            AudioEffectLog.e("fgs startForeground failed", throwable)
            readyReceiver?.send(READY_FAILED, Bundle.EMPTY)
            stopSelf()
            START_NOT_STICKY
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The service intentionally survives the task for the global effect owner, but a
        // capture/replay PoC must never keep pulling another app's audio after its UI is gone.
        // Stop the application-owned controllers so their AudioRecord/AudioTrack resources and
        // MediaProjection token are released together with the projection FGS lease.
        val app = application as? RazioApp
        if (monoRequested) {
            if (app != null) {
                app.stopMonoPlaybackPoc()
            } else {
                monoRequested = false
            }
        }
        if (spectrumRequested) {
            if (app != null) {
                app.stopSpectrum()
            } else {
                spectrumRequested = false
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val launch = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setContentIntent(launch)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    companion object {
        const val CHANNEL_ID = "razio_audio"
        const val NOTIFICATION_ID = 1
        private const val ACTION_START = "dev.hondasports.razio.action.START"
        private const val ACTION_STOP_EFFECT = "dev.hondasports.razio.action.STOP_EFFECT"
        private const val ACTION_STOP_SPECTRUM = "dev.hondasports.razio.action.STOP_SPECTRUM"
        private const val ACTION_STOP_MONO = "dev.hondasports.razio.action.STOP_MONO"
        private const val EXTRA_MEDIA_PROJECTION =
            "dev.hondasports.razio.extra.MEDIA_PROJECTION"
        private const val EXTRA_PROJECTION_OWNER =
            "dev.hondasports.razio.extra.PROJECTION_OWNER"
        private const val EXTRA_READY_RECEIVER =
            "dev.hondasports.razio.extra.READY_RECEIVER"
        private const val OWNER_SPECTRUM = 1
        private const val OWNER_MONO = 2
        private const val READY_OK = 1
        private const val READY_FAILED = 0

        fun start(
            context: Context,
            includeMediaProjection: Boolean = false,
        ) {
            try {
                val command = Intent(context, RazioAudioService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_MEDIA_PROJECTION, includeMediaProjection)
                ContextCompat.startForegroundService(
                    context,
                    command,
                )
            } catch (throwable: Throwable) {
                AudioEffectLog.e("fgs start failed", throwable)
            }
        }

        /** Starts the projection FGS and invokes [onReady] after startForeground succeeds. */
        fun startForMediaProjection(
            context: Context,
            onReady: (Boolean) -> Unit,
            owner: ProjectionOwner = ProjectionOwner.SPECTRUM,
        ) {
            val receiver = object : ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode: Int, resultData: Bundle?) {
                    onReady(resultCode == READY_OK)
                }
            }
            try {
                val command = Intent(context, RazioAudioService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_MEDIA_PROJECTION, true)
                    .putExtra(EXTRA_PROJECTION_OWNER, owner.code)
                    .putExtra(EXTRA_READY_RECEIVER, receiver)
                ContextCompat.startForegroundService(context, command)
            } catch (throwable: Throwable) {
                AudioEffectLog.e("projection fgs start failed", throwable)
                onReady(false)
            }
        }

        fun stop(context: Context) {
            sendStopCommand(context, ACTION_STOP_EFFECT)
        }

        fun stopSpectrum(context: Context) {
            sendStopCommand(context, ACTION_STOP_SPECTRUM)
        }

        fun stopMono(context: Context) {
            sendStopCommand(context, ACTION_STOP_MONO)
        }

        private fun sendStopCommand(
            context: Context,
            action: String,
        ) {
            try {
                context.startService(
                    Intent(context, RazioAudioService::class.java).setAction(action),
                )
            } catch (throwable: Throwable) {
                AudioEffectLog.e("fgs stop command failed action=$action", throwable)
            }
        }

        fun ensureChannel(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            )
            channel.setShowBadge(false)
            manager.createNotificationChannel(channel)
        }
    }

    private fun readyReceiver(intent: Intent?): ResultReceiver? {
        if (intent == null) return null
        return if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_READY_RECEIVER, ResultReceiver::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_READY_RECEIVER)
        }
    }

    private var effectRequested = false
    private var spectrumRequested = false
    private var monoRequested = false
}

enum class ProjectionOwner(internal val code: Int) {
    SPECTRUM(1),
    MONO(2),
}
