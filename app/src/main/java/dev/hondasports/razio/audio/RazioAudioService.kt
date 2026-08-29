package dev.hondasports.razio.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.hondasports.razio.MainActivity
import dev.hondasports.razio.R

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
        val notification = buildNotification()
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            AudioEffectLog.i("fgs started")
            START_STICKY
        } catch (throwable: Throwable) {
            AudioEffectLog.e("fgs startForeground failed", throwable)
            stopSelf()
            START_NOT_STICKY
        }
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

        fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(
                    context,
                    Intent(context, RazioAudioService::class.java),
                )
            } catch (throwable: Throwable) {
                AudioEffectLog.e("fgs start failed", throwable)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, RazioAudioService::class.java))
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
}
