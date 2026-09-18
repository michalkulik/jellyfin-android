package org.jellyfin.mobile.downloads

enum class DownloadStatus {
    QUEUED,

    /**
     * The server is converting the item to the requested quality before it can be downloaded.
     */
    CONVERTING,

    DOWNLOADING,
    DOWNLOADED,
    ERROR,
    CANCELLED,
    ;

    /**
     * Whether the download is still running, meaning it is queued, converting or downloading.
     */
    val isActive: Boolean
        get() = this == QUEUED || this == CONVERTING || this == DOWNLOADING
}
