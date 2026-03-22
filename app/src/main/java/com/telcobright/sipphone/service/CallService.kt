package com.telcobright.sipphone.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.telcobright.sipphone.R
import com.telcobright.sipphone.ui.CallActivity

/**
 * Foreground service to keep the call alive when the app is in background.
 */
class CallService : Service() {

    companion object {
        private const val CHANNEL_ID = "sipphone_call"
        private const val NOTIFICATION_ID = 1
    }

    inner class CallBinder : Binder() {
        fun getService(): CallService = this@CallService
    }

    private val binder = CallBinder()

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification("In call")
        startForeground(NOTIFICATION_ID, notification)
        return START_STICKY
    }

    fun updateNotification(status: String) {
        val notification = buildNotification(status)
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(status: String): Notification {
        val intent = Intent(this, CallActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent, PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SIP Phone")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active Call",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows when a call is active"
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }
}
