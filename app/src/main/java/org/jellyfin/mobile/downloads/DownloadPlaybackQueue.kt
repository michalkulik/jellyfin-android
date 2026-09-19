package org.jellyfin.mobile.downloads

import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.MediaType

/**
 * Builds the playback queue for downloaded videos.
 *
 * Episodes of the same series are ordered by season and episode number so playback can continue
 * with the next downloaded episode. Only fully downloaded episodes are part of the queue.
 */
object DownloadPlaybackQueue {
    /**
     * Returns the ids of the downloaded episodes of the same series as [download], ordered by
     * season and episode number.
     *
     * Falls back to the single item when it is not an episode, when the series is unknown or when
     * no sibling episode is downloaded.
     */
    fun build(download: DownloadEntity, allDownloads: List<DownloadEntity>): List<UUID> {
        val seriesId = download.item.seriesId ?: return listOf(download.itemId)

        val episodes = allDownloads
            .filter { it.item.seriesId == seriesId }
            .filter { it.item.mediaType == MediaType.VIDEO }
            .filter { it.status == DownloadStatus.DOWNLOADED }
            .sortedWith(compareBy({ it.item.parentIndexNumber ?: 0 }, { it.item.indexNumber ?: 0 }))
            .map { it.itemId }

        return if (download.itemId in episodes) episodes else listOf(download.itemId)
    }
}
