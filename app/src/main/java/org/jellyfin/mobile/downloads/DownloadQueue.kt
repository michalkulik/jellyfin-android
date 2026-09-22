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
import org.jellyfin.sdk.model.api.BaseItemDto
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

    /** Finished downloads whose group artwork still has to be fetched. */
    private val _artworkDownloads = mutableListOf<DownloadEntity>()

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

    /**
     * Downloads that were already finished before the group artwork existed, so the series and
     * season posters of the downloads screen can be filled in without re-downloading the videos.
     */
    suspend fun prepareArtwork(): Boolean {
        _artworkDownloads.clear()
        _artworkDownloads.addAll(
            downloadDao.getAllDownloadsOnce().filter { download ->
                download.status == DownloadStatus.DOWNLOADED && artworkFiles(download.item).any { !it.isPresent }
            },
        )
        return _artworkDownloads.any()
    }

    suspend fun processArtwork() {
        for (download in _artworkDownloads) {
            val api = apiClientController.getApiClient(download.serverId, download.userId)
            runCatching { downloadArtwork(api, download.item) }
                .onFailure { err -> Timber.w(err, "Unable to download the group artwork of ${download.item.name}") }
        }

        _artworkDownloads.clear()
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
            val itemLocation = getItemLocation(downloadWithFiles)

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

            // The image is downloaded first so the thumbnail is visible while the (possibly long)
            // server side conversion of the main file runs.
            preparePrimaryImageFile(api, downloadWithFiles, itemLocation)?.let { queuedFile ->
                ensureNotCancelled(downloadId)
                download(api, queuedFile, progressCallback)
            }

            // The series and season posters are shared by every episode, so they are fetched once
            // and stored outside of the item folder.
            downloadArtwork(api, downloadWithFiles.download.item)

            // Subtitles are small and do not depend on the conversion either.
            for (queuedFile in prepareSubtitleFiles(api, downloadWithFiles, itemLocation)) {
                ensureNotCancelled(downloadId)
                download(api, queuedFile, progressCallback)
            }

            // The main file decides the reported progress; it may need a server side conversion first.
            val mainFile = prepareMainFile(api, downloadWithFiles, itemLocation)
            ensureNotCancelled(downloadId)
            download(api, mainFile, progressCallback)

            // The converted file on the server was only needed for this download, so release it.
            releaseServerFile(api, downloadWithFiles.download)

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
     * Tells the server that the converted file is no longer needed so it can free the disk space.
     * The original file is never removed by this call.
     */
    private suspend fun releaseServerFile(api: ApiClient, download: DownloadEntity) {
        val jobId = downloadDao.getDownload(download.id)?.jobId ?: return

        runCatching { downloadJobClient.completeJob(api, download.itemId, jobId) }
            .onSuccess { Timber.i("Released the converted file of job %s", jobId) }
            .onFailure { Timber.w(it, "Unable to release the converted file of job %s", jobId) }
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

    private fun getItemLocation(downloadWithFiles: DownloadFiles): DocumentFile {
        val storageLocation = storageManager.getStorageLocation()
        return storageLocation?.findFile(downloadWithFiles.download.path)
            ?: storageLocation?.createDirectory(downloadWithFiles.download.path)
            ?: error("Unable to find or create folder ${downloadWithFiles.download.path}")
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

        // The conversion is done; move on to downloading while keeping the job id so the converted
        // file can be released on the server once this download finished.
        setPhase(download.id, DownloadStatus.DOWNLOADING, jobId = job.id, progress = 0)

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

    /**
     * Artwork of the groups the item belongs to: the poster of its series and the poster of its
     * season. Nothing is requested when the file is already there, so every group is fetched once.
     *
     * A failure here only means the downloads screen falls back to the item image, so it never
     * fails the item download itself.
     */
    private suspend fun downloadArtwork(api: ApiClient, item: BaseItemDto) {
        for (artwork in artworkFiles(item)) {
            if (artwork.isPresent) continue

            val remoteUri = api.imageApi.getItemImageUrl(
                itemId = artwork.itemId,
                imageType = ImageType.PRIMARY,
                tag = artwork.tag,
                format = ImageFormat.WEBP,
                maxWidth = ARTWORK_MAX_WIDTH,
            ).toUri()

            runCatching {
                artwork.file.parentFile?.mkdirs()
                // ParcelFileDescriptor.open does not create the file, unlike DocumentFile.createFile.
                artwork.file.createNewFile()
                val fileDescriptor = storageManager.openFileDescriptor(artwork.uri)
                    ?: error("Unable to open ${artwork.file}")
                fileDescriptor.use { _downloader.downloadAndSave(api, remoteUri, it) }
            }.onFailure { err ->
                Timber.w(err, "Unable to download the artwork of ${item.name}")
                artwork.file.delete()
            }
        }
    }

    private fun artworkFiles(item: BaseItemDto): List<DownloadArtwork.Artwork> = listOfNotNull(
        DownloadArtwork.seriesArtwork(context, item),
        DownloadArtwork.seasonArtwork(context, item),
    )

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

        /** Series and season posters are only shown as a thumbnail, so a small image is enough. */
        private const val ARTWORK_MAX_WIDTH = 400
    }
}
