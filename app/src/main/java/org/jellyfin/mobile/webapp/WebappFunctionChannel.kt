package org.jellyfin.mobile.webapp

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ChannelIterator

/**
 * Allows to call functions within the webapp
 */
class WebappFunctionChannel {
    // Buffered so calls made while the webapp is (re)loading are not silently dropped and a slow or
    // missing consumer cannot cause callers to fail.
    private val internalChannel = Channel<String>(
        capacity = Channel.BUFFERED,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    operator fun iterator(): ChannelIterator<String> = internalChannel.iterator()

    fun call(action: String) = internalChannel.trySend(action).isSuccess

    // Web component helpers
    fun callPlaybackManagerAction(action: String) = call("$PLAYBACK_MANAGER.$action();")
    fun setVolume(volume: Int) = call(
        "$PLAYBACK_MANAGER.sendCommand({" +
            "Name: 'SetVolume', Arguments: { Volume: $volume }" +
            "});",
    )

    fun seekTo(pos: Long) = call("$PLAYBACK_MANAGER.seekMs($pos);")
    fun goBack() = call("$NAVIGATION_HELPER.goBack();")

    companion object {
        private const val NAVIGATION_HELPER = "window.NavigationHelper"
        private const val PLAYBACK_MANAGER = "$NAVIGATION_HELPER.playbackManager"
    }
}
