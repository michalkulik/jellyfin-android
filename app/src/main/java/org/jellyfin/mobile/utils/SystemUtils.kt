package org.jellyfin.mobile.utils

import android.Manifest
import android.app.Activity
import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.provider.Settings.System.ACCELEROMETER_ROTATION
import androidx.coordinatorlayout.widget.CoordinatorLayout
import androidx.core.content.getSystemService
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.MainActivity
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.settings.ExternalPlayerPackage
import org.jellyfin.mobile.ui.utils.showDownloadQualityDialog
import org.jellyfin.mobile.ui.utils.showDownloadSettingsDialog
import org.jellyfin.mobile.webapp.WebViewFragment
import org.jellyfin.sdk.model.serializer.toUUID
import org.koin.android.ext.android.get
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.resume

fun WebViewFragment.requestNoBatteryOptimizations(rootView: CoordinatorLayout) {
    val powerManager = requireContext().getSystemService(Activity.POWER_SERVICE) as PowerManager
    if (
        !appPreferences.ignoreBatteryOptimizations &&
        !powerManager.isIgnoringBatteryOptimizations(BuildConfig.APPLICATION_ID)
    ) {
        Snackbar.make(rootView, R.string.battery_optimizations_message, Snackbar.LENGTH_INDEFINITE).apply {
            setAction(android.R.string.ok) {
                try {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                } catch (e: ActivityNotFoundException) {
                    Timber.e(e)
                }

                // Ignore after the user interacted with the snackbar at least once
                appPreferences.ignoreBatteryOptimizations = true
            }
            show()
        }
    }
}

suspend fun MainActivity.requestDownload(itemIds: Collection<UUID>) {
    if (itemIds.isEmpty()) return

    val storageManager: StorageManager = get()
    val downloadManager: DownloadManager = get()

    // App private storage is always available. Only ask the user for a folder when the
    // (legacy) external storage location is selected but not accessible yet.
    if (!storageManager.isStorageLocationAccessible()) {
        showDownloadSettingsDialog()
        if (!storageManager.isStorageLocationAccessible()) return
    }

    // Notification permission is optional; downloads still work without it.
    awaitPermission(Manifest.permission.POST_NOTIFICATIONS)

    val server = mainViewModel.serverState.value.server ?: return
    val user = mainViewModel.userState.value.user ?: return

    // Pre-calculate the download size per quality so the dialog can show how much space is needed.
    val sizeEstimates = runCatching { downloadManager.estimateSizes(itemIds) }
        .onFailure { Timber.w(it, "Unable to estimate download sizes") }
        .getOrDefault(emptyMap())

    // Let the user choose the download quality (Original or a server side conversion).
    val quality = showDownloadQualityDialog(sizeEstimates, itemIds.size) ?: return

    downloadManager.enqueueItems(server, user, itemIds, quality)
}

/**
 * Requests a permission and suspends until the user answered, regardless of the outcome.
 */
private suspend fun Activity.awaitPermission(permission: String) {
    suspendCancellableCoroutine { continuation ->
        requestPermission(permission) {
            if (continuation.isActive) continuation.resume(Unit)
        }
    }
}

fun Activity.isAutoRotateOn() = Settings.System.getInt(contentResolver, ACCELEROMETER_ROTATION, 0) == 1

fun PackageManager.isPackageInstalled(@ExternalPlayerPackage packageName: String) = try {
    packageName.isNotEmpty() && getApplicationInfo(packageName, 0).enabled
} catch (e: PackageManager.NameNotFoundException) {
    false
}

fun Context.createMediaNotificationChannel(notificationManager: NotificationManager) {
    if (AndroidVersion.isAtLeastO) {
        val notificationChannel = NotificationChannel(
            Constants.MEDIA_NOTIFICATION_CHANNEL_ID,
            getString(R.string.app_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Media notifications"
        }
        notificationManager.createNotificationChannel(notificationChannel)
    }
}

val Context.isLowRamDevice: Boolean
    get() = getSystemService<ActivityManager>()!!.isLowRamDevice

fun Uri.extractId(): String {
    val uri = toString()
    val idRegex = Regex("""/([a-f0-9]{32}|[a-f0-9-]{36})/""")
    val idResult = idRegex.find(uri)
    val itemId = idResult?.groups?.get(1)?.value.toString()
    var item = itemId.toUUID().toString()

    val subtitleRegex = Regex("""Subtitles/(\d+)/\d+/Stream.subrip|/(\d+).subrip""")
    val subtitleResult = subtitleRegex.find(uri)
    if (subtitleResult != null) {
        item += ":${subtitleResult.groups[1]?.value ?: subtitleResult.groups[2]?.value}"
    }

    return item
}
