package org.jellyfin.mobile.downloads

/**
 * A selectable download quality.
 *
 * When [maxBitrate] is null the original file is downloaded without any server side conversion.
 * Otherwise the server converts the item to at most [maxBitrate] bits per second (and at most
 * [maxHeight] pixels high) before it is downloaded.
 */
data class DownloadQuality(
    val maxBitrate: Int?,
    val maxHeight: Int?,
) {
    val isOriginal: Boolean get() = maxBitrate == null

    companion object {
        val Original = DownloadQuality(maxBitrate = null, maxHeight = null)

        /**
         * Mirrors the quality presets offered by the Emby mobile apps:
         * Original, 15, 12, 8, 4 and 1.5 Mbps (plus a custom value).
         */
        val PRESETS: List<DownloadQuality> = listOf(
            Original,
            DownloadQuality(15_000_000, 1080),
            DownloadQuality(12_000_000, 1080),
            DownloadQuality(8_000_000, 720),
            DownloadQuality(4_000_000, 720),
            DownloadQuality(1_500_000, 480),
        )
    }
}
