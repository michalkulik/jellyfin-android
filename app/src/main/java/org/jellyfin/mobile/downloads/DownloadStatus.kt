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
}
