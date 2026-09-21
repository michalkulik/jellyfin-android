package org.jellyfin.mobile.downloads

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jellyfin.mobile.player.source.LocalJellyfinMediaSource
import org.jellyfin.sdk.model.extensions.inWholeTicks

/**
 * Remembers where the playback of a downloaded item stopped.
 *
 * Downloaded items live on the device, so their position is stored locally and survives without a
 * network. The behaviour mirrors what the server does for streamed items, so a downloaded episode
 * continues, or is reported as watched, just like the same episode played from the server.
 */
class DownloadPlaybackTracker(
    private val downloadManager: DownloadManager,
) {
    /**
     * Stores the position of a running playback.
     */
    suspend fun onProgress(mediaSource: LocalJellyfinMediaSource, positionTicks: Long) {
        downloadManager.updatePlaybackState(
            itemId = mediaSource.itemId,
            positionTicks = positionTicks,
            played = false,
        )
    }

    /**
     * Stores where the playback stopped and whether the item now counts as watched.
     *
     * The position is kept even when the item was watched to the end: that only decides whether the
     * next playback starts over or continues, so watching the item again replaces the stored position.
     *
     * @param positionTicks position where the playback stopped.
     * @param hasFinished whether the player reached the end of the item.
     */
    fun onStopped(mediaSource: LocalJellyfinMediaSource, positionTicks: Long, hasFinished: Boolean) {
        val played = DownloadPlaybackState.isWatched(
            positionTicks = positionTicks,
            runtimeTicks = mediaSource.runTime.inWholeTicks,
            hasFinished = hasFinished,
        )

        // The view model scope may already be cancelled at this point, so this one is independent.
        CoroutineScope(Dispatchers.IO).launch {
            downloadManager.updatePlaybackState(mediaSource.itemId, positionTicks, played)
        }
    }
}
