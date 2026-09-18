package org.jellyfin.mobile.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import org.jellyfin.mobile.R
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadStatus
import timber.log.Timber
import java.io.File

class StorageManager(
    private val context: Context,
    private val appPreferences: AppPreferences
) {
    val defaultStorageLocation
        get() = Environment.getExternalStorageDirectory().resolve(context.getString(R.string.app_name_short)).toUri()

    /**
     * App private directory used when app private storage is enabled. Files here are only visible inside the app.
     */
    val appPrivateDirectory: File
        get() = File(context.filesDir, "downloads").apply {
            if (!exists()) mkdirs()
        }

    fun getStorageLocation(): DocumentFile? = if (appPreferences.useAppPrivateStorage) {
        DocumentFile.fromFile(appPrivateDirectory)
    } else {
        appPreferences.storageLocation?.toUri()?.let { DocumentFile.fromTreeUri(context, it) }
    }

    fun isStorageLocationAccessible(): Boolean {
        if (appPreferences.useAppPrivateStorage) return true

        val documentFile = getStorageLocation()
        return documentFile != null && documentFile.exists() && documentFile.canWrite()
    }

    fun changeStorageLocation(location: Uri): Boolean {
        if (appPreferences.storageLocation?.toUri() == location) return true

        return runCatching {
            context.contentResolver.takePersistableUriPermission(
                location,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )

            appPreferences.storageLocation = location.toString()
            appPreferences.useAppPrivateStorage = false
            getStorageLocation()?.let(::ensureNoMedia)
        }.onFailure { err ->
            Timber.e(err, "Failed to change storage location to $location")
        }.isFailure
    }

    /**
     * Opens the given file for reading and writing. Supports both app private and SAF locations.
     */
    fun openFileDescriptor(uri: Uri): ParcelFileDescriptor? = if (uri.scheme == "file") {
        uri.path?.let { ParcelFileDescriptor.open(File(it), ParcelFileDescriptor.MODE_READ_WRITE) }
    } else {
        context.contentResolver.openFileDescriptor(uri, "rw")
    }

    /**
     * Returns the size of the given file, or null when it does not exist.
     */
    fun getFileLength(uri: Uri): Long? = if (uri.scheme == "file") {
        uri.path?.let { File(it) }?.takeIf { it.exists() }?.length()
    } else {
        DocumentFile.fromSingleUri(context, uri)?.takeIf { it.exists() }?.length()
    }

    fun verify(download: DownloadFiles): Boolean {
        if (download.files.isEmpty()) return false

        for (file in download.files) {
            if (file.status != DownloadStatus.DOWNLOADED) return false
            if (getFileLength(file.uri) != file.size) return false
        }

        return true
    }

    private fun ensureNoMedia(documentFile: DocumentFile) {
        if (documentFile.findFile(NOMEDIA_FILE) == null) {
            documentFile.createFile("", NOMEDIA_FILE)
        }
    }

    companion object {
        const val NOMEDIA_FILE = ".nomedia"
    }
}
