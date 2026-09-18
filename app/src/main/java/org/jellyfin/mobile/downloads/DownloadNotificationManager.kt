package org.jellyfin.mobile.downloads

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.content.getSystemService
import androidx.work.ForegroundInfo
import org.jellyfin.mobile.R
import org.jellyfin.mobile.utils.AndroidVersion

class DownloadNotificationManager(
    val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "downloads"
        const val NOTIFICATION_ID = 67
    }

    private val notificationManager = requireNotNull(context.getSystemService<NotificationManager>())

    init {
        if (AndroidVersion.isAtLeastO) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.downloads),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    fun createForegroundInfo() = ForegroundInfo(
        67,
        NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.downloads))
            setSmallIcon(android.R.drawable.stat_sys_download)
        }.build(),
        if (AndroidVersion.isAtLeastQ) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
    )

    fun downloadFile(id: Long, name: String) = NotificationProgressCallback(context, notificationManager, id, name)

    /**
     * Notification shown while the server is converting the item to the requested quality.
     */
    fun convertFile(id: Long, name: String) = ConversionProgressCallback(context, notificationManager, id, name)

    /**
     * Notification shown when a download finishes successfully. Stays until dismissed by the user.
     */
    fun downloadCompleted(id: Long, name: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.download_completed))
            setContentText(name)
            setSmallIcon(android.R.drawable.stat_sys_download_done)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setAutoCancel(true)
            setOngoing(false)
        }

        notificationManager.notify(id.toInt(), builder.build())
    }
}

/**
 * Shared notification basics for downloads (cancel action, icons, progress).
 */
internal object DownloadNotificationBuilder {
    fun cancelAction(context: Context, downloadId: Long) = NotificationCompat.Action.Builder(
        null,
        context.getString(R.string.download_cancel),
        PendingIntentCompat.getBroadcast(
            context,
            0,
            DownloadBroadcastReceiver.cancelDownloadIntent(context, downloadId),
            0,
            false,
        ),
    ).build()
}

/**
 * Progress notification for the server side conversion phase.
 */
class ConversionProgressCallback(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val downloadId: Long,
    private val name: String,
) {
    private var lastProgress = -1

    private val builder by lazy {
        NotificationCompat.Builder(context, DownloadNotificationManager.CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.converting_title, name))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOnlyAlertOnce(true)
            setOngoing(true)
            setProgress(100, 0, true)
            addAction(DownloadNotificationBuilder.cancelAction(context, downloadId))
        }
    }

    fun notifyConverting() {
        notificationManager.notify(DownloadNotificationManager.NOTIFICATION_ID, builder.build())
    }

    fun onProgress(percent: Double?) {
        val progress = percent?.toInt()?.coerceIn(0, 100) ?: lastProgress
        if (progress == lastProgress) return
        lastProgress = progress

        if (progress >= 0) {
            builder.setProgress(100, progress, false)
            builder.setSubText(context.getString(R.string.converting_progress, progress))
        }

        notificationManager.notify(DownloadNotificationManager.NOTIFICATION_ID, builder.build())
    }

    fun cancel() {
        notificationManager.cancel(DownloadNotificationManager.NOTIFICATION_ID)
    }
}

class NotificationProgressCallback(
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val downloadId: Long,
    private val name: String,
) : FileDownloader.ProgressCallback {
    private var lastProgress = -1

    private val builder by lazy {
        NotificationCompat.Builder(context, DownloadNotificationManager.CHANNEL_ID).apply {
            setContentTitle(context.getString(R.string.downloading_title, name))
            setSmallIcon(android.R.drawable.stat_sys_download)
            setPriority(NotificationCompat.PRIORITY_LOW)
            setOnlyAlertOnce(true)
            setOngoing(true)
            setProgress(100, 0, true)
            addAction(DownloadNotificationBuilder.cancelAction(context, downloadId))
        }
    }

    override suspend fun onProgress(downloaded: Long, total: Long) {
        val progress = (downloaded.toFloat() / (total.toFloat()) * 100).toInt().coerceIn(0, 100)

        if (lastProgress == progress) return
        lastProgress = progress

        if (progress == 100) {
            builder.apply {
                setContentTitle(context.getString(R.string.download_completed))
                setContentText(name)
                setSubText(null)
                setProgress(0, 0, false)
                setSmallIcon(android.R.drawable.stat_sys_download_done)
                setOngoing(false)
                setAutoCancel(true)
                clearActions()
            }
        } else {
            builder.apply {
                setContentText(null)
                setSubText(context.getString(R.string.download_progress, progress))
                setProgress(100, progress, false)
                setOngoing(true)
            }
        }

        notificationManager.notify(DownloadNotificationManager.NOTIFICATION_ID, builder.build())
    }

    suspend fun onEnd() = onProgress(Long.MAX_VALUE, Long.MAX_VALUE)
}
