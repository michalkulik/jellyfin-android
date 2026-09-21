package org.jellyfin.mobile.downloads

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.ApiClientController
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.data.entity.UserEntity
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.itemsApi
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ItemFields
import timber.log.Timber
import java.util.UUID

class DownloadManager(
    private val context: Context,
    private val api: ApiClient,
    private val downloadDao: DownloadDao,
    private val appPreferences: AppPreferences,
    private val storageManager: StorageManager,
    private val apiClientController: ApiClientController,
    private val downloadJobClient: DownloadJobClient,
) {
    companion object {
        /**
         * How many items can be processed at once in [enqueueItems]. If more items are enqueued at once they will be
         * split into separate download chunks.
         */
        private const val ITEMS_BATCH = 25
    }

    /**
     * Remembers where the playback of a downloaded item stopped, so it can be continued later.
     *
     * The position is stored even when the item was watched to the end, so watching it again replaces
     * it with the new position.
     */
    suspend fun updatePlaybackState(itemId: UUID, positionTicks: Long, played: Boolean) =
        withContext(Dispatchers.IO) {
            downloadDao.updatePlaybackState(
                itemId = itemId,
                positionTicks = positionTicks.coerceAtLeast(0L),
                played = played,
            )
        }

    suspend fun enqueueItems(
        server: ServerEntity,
        user: UserEntity,
        items: Collection<UUID>,
        quality: DownloadQuality = DownloadQuality.Original,
    ) = withContext(Dispatchers.IO) {        Timber.i("Enqueueing %d item(s) at quality %s", items.size, quality)

        for (itemsChunk in items.chunked(ITEMS_BATCH)) {
            val existingItems = downloadDao.getDownloadsByItemIds(itemsChunk)
                .filter { it.serverId == server.id }
                .associateBy { it.itemId }

            val response by api.itemsApi.getItems(
                ids = itemsChunk,
                fields = setOf(ItemFields.MEDIA_SOURCES, ItemFields.PATH),
            )

            // Sanity check, this shouldn't happen really
            if (response.items.size != itemsChunk.size) {
                error(
                    "Requested ${itemsChunk.size} items but only got ${response.items.size}. Indicating one or multiple items do not exist.",
                )
            }

            for (item in response.items) {
                var downloadEntity = existingItems[item.id]
                if (downloadEntity != null) {
                    // If the item already exists we just update the local information for it and requeue it
                    // this will force the download worker to recheck the local file in case it is missing or changed
                    // Requesting a different quality re-converts the item on the server.
                    downloadEntity = downloadEntity.copy(
                        item = item,
                        maxBitrate = quality.maxBitrate,
                        maxHeight = quality.maxHeight,
                        mediaSourceId = item.mediaSources?.firstOrNull()?.id?.toString(),
                        jobId = null,
                        progress = -1,
                        status = DownloadStatus.QUEUED,
                        modifiedAt = System.currentTimeMillis(),
                    )
                    downloadDao.update(downloadEntity)
                } else {
                    // Otherwise we create a new one
                    downloadEntity = DownloadEntity(
                        serverId = server.id,
                        userId = user.id,
                        itemId = item.id,
                        item = item,
                        path = uniquePath(item),
                        maxBitrate = quality.maxBitrate,
                        maxHeight = quality.maxHeight,
                        mediaSourceId = item.mediaSources?.firstOrNull()?.id?.toString(),
                    )
                    downloadDao.insert(downloadEntity)
                }
            }
        }

        if (!DownloadWorker.isActive(context)) {
            DownloadWorker.start(context, appPreferences)
        }
    }

    /**
     * Estimates the download size of the given items for every quality preset, so the user can see
     * how much disk space a download will take before starting it.
     */
    suspend fun estimateSizes(items: Collection<UUID>): Map<DownloadQuality, Long?> = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext emptyMap()

        val response by api.itemsApi.getItems(
            ids = items,
            fields = setOf(ItemFields.MEDIA_SOURCES),
        )

        val inputs = response.items.map { it.toDownloadSizeInput() }

        DownloadQuality.PRESETS.associateWith { quality ->
            DownloadSizeEstimator.estimateTotal(inputs, quality)
        }
    }

    suspend fun resume(downloadEntity: DownloadEntity) = withContext(Dispatchers.IO) {
        downloadDao.update(
            downloadEntity.copy(
                status = DownloadStatus.QUEUED,
                jobId = null,
                progress = -1,
                modifiedAt = System.currentTimeMillis(),
            ),
        )

        if (!DownloadWorker.isActive(context)) {
            DownloadWorker.start(context, appPreferences)
        }
    }

    /**
     * Restarts unfinished downloads after the app was closed. WorkManager cancels the download worker
     * together with the app, which would otherwise leave downloads stuck forever.
     */
    suspend fun resumeActiveDownloads() = withContext(Dispatchers.IO) {
        // Don't touch a worker that is still running (the app can be reopened while it downloads).
        if (DownloadWorker.isActive(context)) return@withContext

        // A conversion started by a previous app run keeps occupying one of the limited conversion
        // slots on the server, so stop it before starting a new one.
        downloadDao.getQueuedDownloads()
            .map { it.download }
            .filter { it.jobId != null }
            .forEach { cancelServerJob(it) }

        // The state left behind by the killed worker is stale: requeue so the conversion starts over.
        downloadDao.requeueActiveDownloads()

        if (downloadDao.getQueuedDownloads().isNotEmpty()) {
            Timber.i("Resuming unfinished downloads")
            DownloadWorker.start(context, appPreferences)
        }
    }

    suspend fun cancel(id: Long) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(id) ?: return@withContext

        // Stop the server side conversion first: otherwise it keeps running even though the user
        // cancelled the download (the app would just stop polling it).
        cancelServerJob(download)

        downloadDao.update(
            download.copy(
                status = DownloadStatus.CANCELLED,
                jobId = null,
                progress = -1,
                modifiedAt = System.currentTimeMillis(),
            ),
        )

        // Restart the worker so it drops the cancelled item and moves on to the next one.
        if (download.status.isActive) {
            DownloadWorker.restart(context, appPreferences)
        }
    }

    /**
     * Directory used to store the files of an item. Items from different series can share a name
     * (for example "Episode 1"), so the id is appended when the name would collide with an
     * existing download of another item.
     */
    private suspend fun uniquePath(item: BaseItemDto): String {
        val name = item.name ?: item.id.toString()

        val existing = downloadDao.getDownloadsByPath(name)
        val collidesWithOtherItem = existing.any { it.itemId != item.id }

        return if (collidesWithOtherItem) {
            "$name (${item.id.toString().take(8)})"
        } else {
            name
        }
    }

    private suspend fun cancelServerJob(download: DownloadEntity) {
        val jobId = download.jobId ?: return

        runCatching {
            val api = apiClientController.getApiClient(download.serverId, download.userId)
            downloadJobClient.cancelJob(api, download.itemId, jobId)
            Timber.i("Cancelled conversion job %s for %s", jobId, download.item.name)
        }.onFailure {
            Timber.w(it, "Unable to cancel conversion job %s", jobId)
        }
    }

    suspend fun delete(id: Long, deleteFiles: Boolean) = withContext(Dispatchers.IO) {
        val download = downloadDao.getDownload(id) ?: return@withContext

        // A running conversion keeps the server busy, so stop it together with the download.
        cancelServerJob(download)

        downloadDao.delete(id)

        if (download.status.isActive) {
            DownloadWorker.restart(context, appPreferences)
        }

        if (deleteFiles) {
            val storageLocation = storageManager.getStorageLocation()
            storageLocation?.findFile(download.path)?.delete()
        }
    }
}
