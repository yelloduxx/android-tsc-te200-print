package com.example.tscprint

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast

class QuickShareNotifier(private val context: Context) {

    fun showError(message: String) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(context.applicationContext, message, Toast.LENGTH_LONG).show()
            return
        }
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.quick_share_error_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        val notification = android.app.Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(context.getString(R.string.quick_share_error_title))
            .setContentText(message)
            .setStyle(android.app.Notification.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "quick_share_errors"
        private const val NOTIFICATION_ID = 2001
    }
}
