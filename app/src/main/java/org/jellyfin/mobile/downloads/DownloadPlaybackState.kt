package org.jellyfin.mobile.downloads

/**
 * Rules that decide where the playback of a downloaded item continues and when it counts as watched.
 *
 * They mirror what the server does for streamed playback, so an episode played from a download
 * behaves exactly like the same episode played from the server.
 */
object DownloadPlaybackState {
    /**
     * Fraction of the runtime after which an item counts as watched. The server uses the same value,
     * so stopping a few seconds before the end does not leave a useless resume point behind.
     */
    private const val WATCHED_FRACTION = 0.9

    /**
     * Position the playback should resume from, or null when it should start at the beginning.
     *
     * A watched item starts over, everything else continues where it stopped. The stored position is
     * kept either way, so watching an item again replaces it with the new position.
     *
     * @param positionTicks position where the previous playback stopped.
     * @param played whether the item was watched to the end.
     */
    fun resumePositionTicks(positionTicks: Long, played: Boolean): Long? =
        positionTicks.takeIf { !played && it > 0L }

    /**
     * Whether the position where playback stopped marks the item as watched.
     *
     * @param positionTicks position where the playback stopped.
     * @param runtimeTicks length of the item, 0 when it is unknown.
     * @param hasFinished whether the player reached the end of the item.
     */
    fun isWatched(positionTicks: Long, runtimeTicks: Long, hasFinished: Boolean): Boolean = when {
        hasFinished -> true
        runtimeTicks <= 0L -> false
        else -> positionTicks >= (runtimeTicks * WATCHED_FRACTION).toLong()
    }
}
