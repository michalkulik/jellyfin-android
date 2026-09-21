package org.jellyfin.mobile.downloads

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.junit.jupiter.api.Test

class DownloadPlaybackStateTest {
    private companion object {
        private const val MINUTE = 10_000_000L

        private fun download(positionTicks: Long = 0L, played: Boolean = false) = DownloadEntity(
            id = 1L,
            serverId = 1L,
            userId = 1L,
            itemId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
            path = "series/episode",
            item = BaseItemDto(
                id = UUID.fromString("00000000-0000-0000-0000-000000000001"),
                type = BaseItemKind.EPISODE,
            ),
            positionTicks = positionTicks,
            played = played,
        )
    }

    @Test
    fun `a paused item continues where it stopped`() {
        download(positionTicks = 12 * MINUTE).resumePositionTicks shouldBe 12 * MINUTE
    }

    @Test
    fun `an item that was never played starts at the beginning`() {
        download(positionTicks = 0L).resumePositionTicks.shouldBeNull()
    }

    @Test
    fun `a watched item starts over`() {
        download(positionTicks = 45 * MINUTE, played = true).resumePositionTicks.shouldBeNull()
    }

    @Test
    fun `an item that was paused right at the start still continues`() {
        download(positionTicks = 1L).resumePositionTicks shouldBe 1L
    }

    @Test
    fun `watching a watched item again stores the new position`() {
        val watched = download(positionTicks = 45 * MINUTE, played = true)
        watched.played shouldBe true

        // Watching a watched item again stores the new position without the watched flag.
        val rewatched = watched.copy(positionTicks = 3 * MINUTE, played = false)
        rewatched.played shouldBe false
        rewatched.resumePositionTicks shouldBe 3 * MINUTE
    }

    @Test
    fun `an item that was watched to the end counts as watched`() {
        DownloadPlaybackState.isWatched(
            positionTicks = 45 * MINUTE,
            runtimeTicks = 45 * MINUTE,
            hasFinished = true,
        ) shouldBe true
    }

    @Test
    fun `stopping a few seconds before the end still counts as watched`() {
        // Mirrors the server, so the same episode does not behave differently when streamed.
        DownloadPlaybackState.isWatched(
            positionTicks = 44 * MINUTE,
            runtimeTicks = 45 * MINUTE,
            hasFinished = false,
        ) shouldBe true
    }

    @Test
    fun `stopping halfway does not count as watched`() {
        DownloadPlaybackState.isWatched(
            positionTicks = 20 * MINUTE,
            runtimeTicks = 45 * MINUTE,
            hasFinished = false,
        ) shouldBe false
    }

    @Test
    fun `an unknown runtime does not count as watched`() {
        DownloadPlaybackState.isWatched(
            positionTicks = 20 * MINUTE,
            runtimeTicks = 0L,
            hasFinished = false,
        ) shouldBe false
    }
}
