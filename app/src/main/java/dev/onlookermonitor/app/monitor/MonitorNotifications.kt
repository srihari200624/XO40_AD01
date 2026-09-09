package dev.onlookermonitor.app.monitor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dev.onlookermonitor.app.MainActivity
import dev.onlookermonitor.app.R
import dev.onlookermonitor.app.core.MonitorState

class MonitorNotifications(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    fun createChannels() {
        val monitoring = NotificationChannel(
            MONITORING_CHANNEL,
            context.getString(R.string.notification_channel_monitoring),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_monitoring_description)
            setShowBadge(false)
        }
        val alerts = NotificationChannel(
            ALERT_CHANNEL,
            context.getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_alerts_description)
            enableVibration(true)
        }
        manager.createNotificationChannels(listOf(monitoring, alerts))
    }

    fun foreground(state: MonitorState, message: String): Notification {
        val title = when (state) {
            MonitorState.STARTING -> context.getString(R.string.notification_starting)
            MonitorState.ACTIVE -> context.getString(R.string.notification_active)
            MonitorState.CANDIDATE_DETECTED -> context.getString(R.string.notification_checking)
            MonitorState.SHIELD_ACTIVE -> context.getString(R.string.notification_shield)
            MonitorState.DEGRADED -> context.getString(R.string.notification_degraded)
            MonitorState.DISARMED -> context.getString(R.string.notification_stopped)
        }
        return NotificationCompat.Builder(context, MONITORING_CHANNEL)
            .setSmallIcon(R.drawable.ic_visibility)
            .setContentTitle(title)
            .setContentText(message)
            .setContentIntent(activityIntent())
            .addAction(R.drawable.ic_stop, context.getString(R.string.stop), stopIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    fun showAlert() {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_visibility)
            .setContentTitle(context.getString(R.string.notification_shield))
            .setContentText(context.getString(R.string.shield_notification_message))
            .setContentIntent(activityIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(ALERT_NOTIFICATION_ID, notification)
    }

    fun showDegradedAlert(message: String) {
        if (!canPostNotifications()) return
        val notification = NotificationCompat.Builder(context, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_visibility)
            .setContentTitle(context.getString(R.string.notification_degraded))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(activityIntent())
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()
        manager.notify(DEGRADED_NOTIFICATION_ID, notification)
    }

    fun clearAlert() {
        manager.cancel(ALERT_NOTIFICATION_ID)
    }

    fun clearDegradedAlert() {
        manager.cancel(DEGRADED_NOTIFICATION_ID)
    }

    private fun canPostNotifications(): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun activityIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun stopIntent(): PendingIntent = PendingIntent.getService(
        context,
        1,
        Intent(context, OnlookerMonitorService::class.java).setAction(OnlookerMonitorService.ACTION_STOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val FOREGROUND_NOTIFICATION_ID = 1001
        private const val ALERT_NOTIFICATION_ID = 1002
        private const val DEGRADED_NOTIFICATION_ID = 1003
        private const val MONITORING_CHANNEL = "monitoring"
        private const val ALERT_CHANNEL = "onlooker_alerts"
    }
}
