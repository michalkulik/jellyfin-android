package org.jellyfin.mobile.downloads

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.dao.DownloadDao
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.sdk.model.api.MediaType
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class DownloadsViewModel : ViewModel(), KoinComponent {

    private val downloadDao: DownloadDao by inject()
    private val downloadManager: DownloadManager by inject()
    private val activityEventHandler: ActivityEventHandler by inject()
    private val storageManager: StorageManager by inject()

    val downloads: StateFlow<List<DownloadFiles>> = downloadDao
        .getAllDownloadsWithFiles()
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    private val _storageLocation = MutableStateFlow(storageManager.getStorageLocation())
    val storageLocation = _storageLocation.asStateFlow()

    private val _storageLocationAccessible = MutableStateFlow(storageManager.isStorageLocationAccessible())
    val storageLocationAccessible = _storageLocationAccessible.asStateFlow()

    fun openDownload(download: DownloadEntity) {
        when (download.item.mediaType) {
            MediaType.VIDEO -> {
                viewModelScope.launch {
                    val playOptions = withContext(Dispatchers.IO) {
                        buildVideoPlayOptions(download)
                    }
                    activityEventHandler.emit(ActivityEvent.LaunchNativePlayer(playOptions))
                }
            }

            MediaType.AUDIO,
            MediaType.PHOTO,
            MediaType.BOOK,
            MediaType.UNKNOWN -> {
                viewModelScope.launch {
                    withContext(Dispatchers.IO) {
                        val storageLocation = storageManager.getStorageLocation()
                        val itemLocation = storageLocation?.findFile(download.path)
                        if (itemLocation != null && itemLocation.isDirectory) {
                            val filename = download.item.path?.replace(Regex("^.*[\\\\/]"), "")
                            if (filename != null) itemLocation.findFile(filename)?.uri else null
                        } else {
                            null
                        }
                    }?.let {
                        activityEventHandler.emit(ActivityEvent.OpenUrl(it.toString(), true))
                    }
                }
            }
        }
    }

    /**
     * Builds the playback options for a downloaded video.
     *
     * For episodes the queue contains every downloaded episode of the same series, ordered by
     * season and episode number, so playback can continue with the next downloaded episode. The
     * tapped episode is the starting point of the queue.
     *
     * Playback continues where the item stopped before. An item that was watched to the end starts
     * over, but its stored position is kept so watching it again updates the position.
     */
    private suspend fun buildVideoPlayOptions(download: DownloadEntity): PlayOptions {
        val queue = DownloadPlaybackQueue.build(download, downloadDao.getAllDownloadsOnce())

        return PlayOptions(
            ids = queue,
            mediaSourceId = download.itemId.toString(),
            startIndex = queue.indexOf(download.itemId).coerceAtLeast(0),
            startPosition = download.resumePositionTicks?.ticks,
            audioStreamIndex = null,
            subtitleStreamIndex = null,
            playFromDownloads = true,
        )
    }

    fun download(download: DownloadEntity) {
        viewModelScope.launch {
            downloadManager.resume(download)
        }
    }

    /**
     * Cancels a running or queued download, including the server side conversion.
     */
    fun cancelDownload(download: DownloadEntity) {
        viewModelScope.launch {
            downloadManager.cancel(download.id)
        }
    }

    fun removeDownload(download: DownloadEntity, deleteFiles: Boolean) {
        viewModelScope.launch {
            downloadManager.delete(download.id, deleteFiles)
        }
    }

    fun changeStorageLocation(uri: android.net.Uri) {
        storageManager.changeStorageLocation(uri)
        _storageLocation.value = storageManager.getStorageLocation()
        _storageLocationAccessible.value = storageManager.isStorageLocationAccessible()
    }
}
