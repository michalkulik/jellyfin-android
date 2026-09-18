package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.imageApi
import org.jellyfin.sdk.model.api.ImageFormat
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.MediaStreamType
import timber.log.Timber
import java.io.IOException

/**
 * Thrown when a download was cancelled by the user while a long running phase was active.
 */
class DownloadCancelledException(val downloadId: Long) : Exception("Download $downloadId was cancelled")

class DownloadQueue(
    private val context: Context,
    private val apiClientController: ApiClientController,
    private val downloadDao: DownloadDao,
    private val downloadNotificationManager: DownloadNotificationManager,
    private val storageManager: StorageManager,
    private val downloadJobClient: DownloadJobClient,
    okHttpClient: OkHttpClient,
) {
    private data class QueuedFile(val file: DownloadFileEntity, val remoteUri: Uri)

    private val _downloader = FileDownloader(okHttpClient)
    private val _downloads = mutableListOf<DownloadFiles>()

    /**
     * Downloads that failed (or were cancelled) during this worker run. They are skipped when the
     * queue is refetched so one broken item cannot block the remaining downloads; they are retried
     * on the next worker run.
     */
    private val _skipped = mutableSetOf<Long>()

    suspend fun prepare(): Boolean {
        val queuedDownloads = downloadDao.getQueuedDownloads()
        _downloads.clear()
        _downloads.addAll(queuedDownloads.filterNot { it.download.id in _skipped })
        return _downloads.any()
    }

    suspend fun process() {
        while (_downloads.any()) {
            val iterator = _downloads.iterator()
            while (iterator.hasNext()) {
                val downloadWithFiles = iterator.next()
                process(downloadWithFiles)
                iterator.remove()
            }

            // Refetch the queued downloads
            prepare()
        }
    }

    private suspend fun process(downloadWithFiles: DownloadFiles) {
        val downloadId = downloadWithFiles.download.id

        // The download may have been cancelled while it was waiting in the queue.
        if (downloadDao.getDownload(downloadId)?.status == DownloadStatus.CANCELLED) return

        setPhase(downloadId, DownloadStatus.DOWNLOADING)

        val api = apiClientController.getApiClient(downloadWithFiles.download.serverId, downloadWithFiles.download.userId)

        try {
            val queuedFiles = prepareFiles(api, downloadWithFiles)

            val notificationProgressCallback = downloadNotificationManager.downloadFile(
                downloadId,
                downloadWithFiles.download.getDisplayName(context).orEmpty(),
            )

            val progressCallback = object : FileDownloader.ProgressCallback {
                override suspend fun onProgress(downloaded: Long, total: Long) {
                    val progress = if (total > 0) ((downloaded * 100) / total).toInt().coerceIn(0, 100) else -1
                    downloadDao.updateProgress(downloadId, progress)
                    notificationProgressCallback.onProgress(downloaded, total)
                }
            }

            // The main file decides the reported progress; the image and subtitles are small extras.
            for (queuedFile in queuedFiles) {
                ensureNotCancelled(downloadId)
                download(api, queuedFile, progressCallback)
            }

            notificationProgressCallback.onEnd()
            setPhase(downloadId, DownloadStatus.DOWNLOADED, progress = 100)

            // Post a persistent, dismissible completion notice (the foreground notification
            // is removed by WorkManager once the worker stops).
            downloadNotificationManager.downloadCompleted(
                downloadId,
                downloadWithFiles.download.getDisplayName(context).orEmpty(),
            )
        } catch (e: DownloadCancelledException) {
            // Nothing to do: the cancel action already stored the cancelled state.
            _skipped += downloadId
            downloadNotificationManager.cancelProgressNotification()
        } catch (e: CancellationException) {
            // The download could've been canceled by the app, in which case we need to refresh it before making changes
            val download = downloadDao.getDownload(downloadId)
            if (download?.status?.isActive == true) {
                setPhase(downloadId, DownloadStatus.QUEUED, progress = -1)
            }
            throw e
        } catch (e: IOException) {
            // Transient problem: keep the download queued for the next worker run, but do not let it
            // block the other queued downloads in this run.
            Timber.w(e, "Download %d failed, will retry later", downloadId)
            setPhase(downloadId, DownloadStatus.QUEUED, progress = -1)
            _skipped += downloadId
        } catch (e: Exception) {
            Timber.e(e, "Download %d failed", downloadId)
            setPhase(downloadId, DownloadStatus.ERROR, progress = -1)
            _skipped += downloadId
        }
    }

    /**
     * Updates the status of a download without touching the columns that are written by other tasks.
     */
    private suspend fun setPhase(
        downloadId: Long,
        status: DownloadStatus,
        jobId: String? = null,
        progress: Int = -1,
    ) {
        downloadDao.updatePhase(downloadId, status, jobId, progress)
    }

    /**
     * Throws when the download was cancelled, so long running phases (especially the server side
     * conversion) stop instead of running to completion.
     */
    private suspend fun ensureNotCancelled(downloadId: Long) {
        if (downloadDao.getDownload(downloadId)?.status == DownloadStatus.CANCELLED) {
            throw DownloadCancelledException(downloadId)
        }
    }

    private suspend fun download(
        api: ApiClient,
        queuedFile: QueuedFile,
        progressCallback: FileDownloader.ProgressCallback,
    ) {
        val (file, remoteUri) = queuedFile

        // Verify downloaded files and skip if valid
        if (file.status == DownloadStatus.DOWNLOADED && file.size > 0) {
            if (storageManager.getFileLength(file.uri) == file.size) return
        }

        val fileDescriptor = storageManager.openFileDescriptor(file.uri)
            ?: error("Unable to open file descriptor for ${file.fileName}")

        downloadDao.updateFile(file.copy(status = DownloadStatus.DOWNLOADING))

        try {
            _downloader.downloadAndSave(
                api = api,
                from = remoteUri,
                to = fileDescriptor,
                progressCallback = progressCallback,
            )

            // Update file record with final size and status
            downloadDao.updateFile(
                file.copy(
                    size = storageManager.getFileLength(file.uri) ?: 0L,
                    status = DownloadStatus.DOWNLOADED,
                ),
            )
        } catch (e: IOException) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.QUEUED))
            throw e
        } catch (e: Exception) {
            downloadDao.updateFile(file.copy(status = DownloadStatus.ERROR))
            throw e
        }
    }

    private suspend fun prepareFiles(api: ApiClient, downloadWithFiles: DownloadFiles): List<QueuedFile> {
        val storageLocation = storageManager.getStorageLocation()
        val itemLocation = storageLocation?.findFile(downloadWithFiles.download.path)
            ?: storageLocation?.createDirectory(downloadWithFiles.download.path)
            ?: error("Unable to find or create folder ${downloadWithFiles.download.path}")

        return buildList {
            // Add image as first item so it can be shown in UI during downloads
            preparePrimaryImageFile(api, downloadWithFiles, itemLocation)?.let(::add)

            // Add main item second as it is (often) the largest and important file
            prepareMainFile(api, downloadWithFiles, itemLocation).let(::add)

            // Add external subtitles so they are available during offline playback
            addAll(prepareSubtitleFiles(api, downloadWithFiles, itemLocation))
        }
    }

    private suspend fun prepareMainFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
    ): QueuedFile {
        val download = downloadWithFiles.download
        val item = download.item

        // Original quality: download the untouched file straight from the server.
        if (download.maxBitrate == null) {
            return QueuedFile(
                file = createOrUpdateFile(
                    filter = { it.type == DownloadFileType.ITEM },
                    downloadWithFiles = downloadWithFiles,
                    itemLocation = itemLocation,
                    type = DownloadFileType.ITEM,
                    fileName = item.path?.replace(Regex("^.*[\\\\/]"), "") ?: error("Missing item path"),
                ),
                remoteUri = downloadJobClient.getDirectDownloadUrl(api, item.id),
            )
        }

        // Converted quality: ask the server to convert the item, wait for it and download the result.
        val job = downloadJobClient.createJob(
            api,
            item.id,
            CreateDownloadRequestDto(
                maxBitrate = download.maxBitrate,
                maxHeight = download.maxHeight,
                container = DEFAULT_CONTAINER,
                mediaSourceId = download.mediaSourceId,
            ),
        )
        Timber.i("Created download job %s for %s", job.id, item.name)

        // Remember the job so the user can cancel the server side conversion from the UI.
        setPhase(download.id, DownloadStatus.CONVERTING, jobId = job.id)

        val readyJob = awaitConversion(api, download, job)

        // The conversion is done; reset the state left over from the conversion phase.
        setPhase(download.id, DownloadStatus.DOWNLOADING, progress = 0)

        return QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.ITEM },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.ITEM,
                fileName = readyJob.fileName ?: "${item.id}.$DEFAULT_CONTAINER",
            ),
            remoteUri = downloadJobClient.getFileUrl(api, item.id, readyJob.id),
        )
    }

    /**
     * Polls the server side conversion job until it is ready and returns the finished job.
     *
     * The status is re-read from the database on every poll, so cancelling the download stops both
     * the polling here and the conversion on the server.
     */
    private suspend fun awaitConversion(
        api: ApiClient,
        download: DownloadEntity,
        job: DownloadJobDto,
    ): DownloadJobDto {
        val notification = downloadNotificationManager.convertFile(
            download.id,
            download.getDisplayName(context).orEmpty(),
        )
        notification.notifyConverting()

        var current = job
        try {
            while (true) {
                when (current.status.lowercase()) {
                    "ready" -> return current
                    "failed", "cancelled" -> error(current.error ?: "Conversion ${current.status}")
                }

                // Stop as soon as the user cancelled, otherwise the conversion would keep running.
                ensureNotCancelled(download.id)

                // The server starts converting only once a conversion slot is free. Report the
                // waiting state so the UI does not claim the conversion already runs. The progress
                // is only reset when the phase changes, so a reported percentage survives the poll.
                val waitingForSlot = current.status.equals("queued", ignoreCase = true)
                val phase = if (waitingForSlot) DownloadStatus.QUEUED else DownloadStatus.CONVERTING
                if (downloadDao.getDownload(download.id)?.status != phase) {
                    downloadDao.updatePhase(download.id, phase, job.id, -1)
                }

                delay(CONVERSION_POLL_INTERVAL_MS)
                current = downloadJobClient.getJob(api, download.item.id, current.id)

                val progress = current.progress?.toInt()?.coerceIn(0, 100) ?: -1
                downloadDao.updateProgress(download.id, progress)
                notification.onProgress(current.progress)
            }
        } finally {
            // Remove the conversion notification; the download progress notification takes over.
            notification.cancel()
        }
    }

    /**
     * Downloads the external subtitles of an item so they can be used during offline playback.
     */
    private suspend fun prepareSubtitleFiles(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
    ): List<QueuedFile> {
        val item = downloadWithFiles.download.item
        val mediaSource = item.mediaSources?.firstOrNull() ?: return emptyList()
        val mediaSourceId = mediaSource.id ?: return emptyList()

        return mediaSource.mediaStreams
            .orEmpty()
            .filter { it.type == MediaStreamType.SUBTITLE && it.isExternal }
            .mapNotNull { stream ->
                val index = stream.index ?: return@mapNotNull null
                val format = stream.codec?.lowercase() ?: "srt"
                val fileName = "subtitle-$index.$format"

                QueuedFile(
                    file = createOrUpdateFile(
                        filter = { it.type == DownloadFileType.SUBTITLE && it.fileName == fileName },
                        downloadWithFiles = downloadWithFiles,
                        itemLocation = itemLocation,
                        type = DownloadFileType.SUBTITLE,
                        fileName = fileName,
                    ),
                    remoteUri = downloadJobClient.getSubtitleUrl(api, item.id, mediaSourceId, index, format),
                )
            }
    }

    private suspend fun preparePrimaryImageFile(
        api: ApiClient,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile
    ): QueuedFile? = downloadWithFiles.download.item.imageTags?.get(ImageType.PRIMARY)?.let { imageTag ->
        QueuedFile(
            file = createOrUpdateFile(
                filter = { it.type == DownloadFileType.IMAGE_PRIMARY },
                downloadWithFiles = downloadWithFiles,
                itemLocation = itemLocation,
                type = DownloadFileType.IMAGE_PRIMARY,
                fileName = "primary.webp",
            ),
            remoteUri = api.imageApi.getItemImageUrl(
                itemId = downloadWithFiles.download.item.id,
                imageType = ImageType.PRIMARY,
                tag = imageTag,
                format = ImageFormat.WEBP,
            ).toUri()
        )
    }

    private suspend fun createOrUpdateFile(
        filter: (DownloadFileEntity) -> Boolean,
        downloadWithFiles: DownloadFiles,
        itemLocation: DocumentFile,
        type: DownloadFileType,
        fileName: String,
    ): DownloadFileEntity {
        var downloadFile = downloadWithFiles.files.firstOrNull(filter)

        val file = itemLocation.findFile(fileName)
            ?: itemLocation.createFile("", fileName)
            ?: error("Unable to create file $fileName")

        if (downloadFile != null) {
            downloadFile = downloadFile.copy(
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )
            downloadDao.updateFile(downloadFile)
            return downloadFile
        } else {
            downloadFile = DownloadFileEntity(
                downloadId = downloadWithFiles.download.id,
                type = type,
                size = 0L,
                fileName = fileName,
                uri = file.uri,
                status = DownloadStatus.QUEUED,
            )

            val id = downloadDao.insertFile(downloadFile)
            downloadFile = downloadFile.copy(id = id)
            return downloadFile
        }
    }

    companion object {
        private const val DEFAULT_CONTAINER = "mp4"
        private const val CONVERSION_POLL_INTERVAL_MS = 2_000L
    }
}
