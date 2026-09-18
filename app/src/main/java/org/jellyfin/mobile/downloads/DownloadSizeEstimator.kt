package org.jellyfin.mobile.downloads

import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.MediaStreamType

/**
 * The information about an item that is needed to estimate its download size.
 */
data class DownloadSizeInput(
    /**
     * The runtime of the item in ticks (100ns).
     */
    val runtimeTicks: Long?,
    /**
     * The exact size of the original file in bytes, when the server reports it.
     */
    val originalSizeBytes: Long?,
    /**
     * The bitrate of the primary video stream in bits per second.
     */
    val videoBitrate: Int?,
    /**
     * The bitrate of the primary audio stream in bits per second.
     */
    val audioBitrate: Int?,
)

/**
 * Estimates how much disk space a download will use.
 *
 * The original quality uses the exact size reported by the server. Converted qualities are estimated
 * from the bitrate and the runtime of the item, which is accurate enough to give the user a rough
 * idea of the download size before starting it.
 */
object DownloadSizeEstimator {
    private const val TICKS_PER_SECOND = 10_000_000.0
    private const val BITS_PER_BYTE = 8L

    /**
     * Fallback video bitrate (bits per second) used when the video stream bitrate is unknown.
     */
    private const val DEFAULT_VIDEO_BITRATE = 2_000_000

    /**
     * Fallback audio bitrate (bits per second) used when the audio stream bitrate is unknown.
     */
    private const val DEFAULT_AUDIO_BITRATE = 192_000

    /**
     * Upper bound for the audio bitrate. The server downmixes high channel count audio, so using the
     * raw stream bitrate could overestimate the result significantly.
     */
    private const val MAX_AUDIO_BITRATE = 640_000

    /**
     * Estimates the download size of a single item in bytes, or null when it cannot be determined.
     */
    fun estimate(input: DownloadSizeInput, quality: DownloadQuality): Long? {
        // The original file is served as-is, so the reported size is exact.
        if (quality.isOriginal) {
            return input.originalSizeBytes?.takeIf { it > 0 }
        }

        val maxBitrate = quality.maxBitrate?.takeIf { it > 0 } ?: return null
        val runtimeSeconds = input.runtimeTicks
            ?.takeIf { it > 0 }
            ?.let { it / TICKS_PER_SECOND }
            ?: return null

        // The server never exceeds the requested bitrate, and an item that already sits below it is
        // not upscaled, so the smaller of the two is the best available approximation.
        val videoBitrate = minOf(input.videoBitrate?.takeIf { it > 0 } ?: DEFAULT_VIDEO_BITRATE, maxBitrate)
        val audioBitrate = input.audioBitrate?.coerceIn(0, MAX_AUDIO_BITRATE) ?: DEFAULT_AUDIO_BITRATE

        val totalBitrate = videoBitrate.toLong() + audioBitrate
        return (totalBitrate * runtimeSeconds / BITS_PER_BYTE).toLong()
    }

    /**
     * Estimates the total download size of multiple items (for example every episode of a season).
     * Returns null when the size of at least one item is unknown.
     */
    fun estimateTotal(inputs: Collection<DownloadSizeInput>, quality: DownloadQuality): Long? {
        if (inputs.isEmpty()) return null

        var total = 0L
        for (input in inputs) {
            val size = estimate(input, quality) ?: return null
            total += size
        }

        return total
    }
}

/**
 * Extracts the values needed to estimate the download size of this item.
 */
fun BaseItemDto.toDownloadSizeInput(): DownloadSizeInput {
    val mediaSource = mediaSources?.firstOrNull()
    val streams = mediaSource?.mediaStreams.orEmpty()

    return DownloadSizeInput(
        runtimeTicks = runTimeTicks?.takeIf { it > 0 } ?: mediaSource?.runTimeTicks,
        originalSizeBytes = mediaSource?.size,
        videoBitrate = streams.firstOrNull { it.type == MediaStreamType.VIDEO }?.bitRate,
        audioBitrate = streams.firstOrNull { it.type == MediaStreamType.AUDIO }?.bitRate,
    )
}

