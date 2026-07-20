package com.svpn.app.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.svpn.app.R

/**
 * Shows a simple status notification while the tunnel is up ("PeladuVPN
 * работает.") and clears it once disconnected. Separate from whatever
 * notification the backend library itself might post for the underlying
 * foreground VpnService — this one is purely user-facing status text we
 * fully control.
 */
object PeladuNotifier {

    private const val CHANNEL_ID = "peladu_status"
    private const val NOTIFICATION_ID = 4201

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "PeladuVPN",
                    NotificationManager.IMPORTANCE_LOW
                )
                manager.createNotificationChannel(channel)
            }
        }
    }

    fun showConnected(context: Context) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("PeladuVPN")
            .setContentText(context.getString(R.string.notification_connected))
            .setOngoing(true)
            .setAutoCancel(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS not granted (API 33+) — the VPN itself still
            // works fine, the person just won't see the status notification.
        }
    }

    fun clear(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        } catch (_: SecurityException) {
            // no-op
        }
    }
}
