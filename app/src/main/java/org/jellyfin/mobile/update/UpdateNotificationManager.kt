package org.jellyfin.mobile.update

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.getSystemService
import androidx.work.ForegroundInfo
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion

/**
 * Notification shown while a new version of the app is being downloaded.
 *
 * Uses its own channel and id so it never collides with the media download notifications.
 */
class UpdateNotificationManager(
    private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "app_updates"
        const val NOTIFICATION_ID = 69
    }

    private val notificationManager = requireNotNull(context.getSystemService<NotificationManager>())

    init {
        if (AndroidVersion.isAtLeastO) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.update_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo(): ForegroundInfo = ForegroundInfo(
        NOTIFICATION_ID,
        buildNotification().build(),
        if (AndroidVersion.isAtLeastQ) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    fun createProgressNotification(version: String) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(version = version).build(),
        )
    }

    fun updateProgress(version: String, progress: Int) {
        notificationManager.notify(
            NOTIFICATION_ID,
            buildNotification(version = version)
                .setProgress(UpdateManager.PROGRESS_MAX, progress, progress <= 0)
                .setContentText(context.getString(R.string.update_downloading_progress, progress))
                .build(),
        )
    }

    fun cancelProgressNotification() {
        notificationManager.cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(version: String? = null) = NotificationCompat.Builder(context, CHANNEL_ID).apply {
        setContentTitle(context.getString(R.string.update_downloading_title))
        setContentText(version ?: context.getString(R.string.update_downloading))
        setSmallIcon(android.R.drawable.stat_sys_download)
        setPriority(NotificationCompat.PRIORITY_LOW)
        setOngoing(true)
    }
}
