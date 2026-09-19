package org.jellyfin.mobile.downloads

import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.junit.jupiter.api.Test

class DownloadPlaybackQueueTest {
    private companion object {
        private val SERIES_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val OTHER_SERIES_ID = UUID.fromString("22222222-2222-2222-2222-222222222222")

        private fun episode(
            id: String,
            season: Int?,
            number: Int?,
            seriesId: UUID? = SERIES_ID,
            status: DownloadStatus = DownloadStatus.DOWNLOADED,
            mediaType: MediaType = MediaType.VIDEO,
            serverId: Long = 1,
            userId: Long = 1,
        ) = DownloadEntity(
            id = id.hashCode().toLong(),
            serverId = serverId,
            userId = userId,
            itemId = UUID.fromString(id),
            path = "series/$id",
            item = BaseItemDto(
                id = UUID.fromString(id),
                type = BaseItemKind.EPISODE,
                mediaType = mediaType,
                seriesId = seriesId,
                parentIndexNumber = season,
                indexNumber = number,
            ),
            status = status,
        )
    }

    @Test
    fun `episodes of the same series are ordered by season and episode number`() {
        val s1e2 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 2)
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)
        val s2e1 = episode("00000000-0000-0000-0000-000000000003", season = 2, number = 1)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e2, s2e1, s1e1))

        queue shouldBe listOf(s1e1.itemId, s1e2.itemId, s2e1.itemId)
    }

    @Test
    fun `episodes of other series are not part of the queue`() {
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)
        val s1e2 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 2)
        val other = episode("00000000-0000-0000-0000-000000000009", season = 1, number = 1, seriesId = OTHER_SERIES_ID)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e1, s1e2, other))

        queue shouldBe listOf(s1e1.itemId, s1e2.itemId)
    }

    @Test
    fun `episodes that are not downloaded are skipped`() {
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)
        val s1e2 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 2, status = DownloadStatus.DOWNLOADING)
        val s1e3 = episode("00000000-0000-0000-0000-000000000003", season = 1, number = 3)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e1, s1e2, s1e3))

        queue shouldBe listOf(s1e1.itemId, s1e3.itemId)
    }

    @Test
    fun `a single downloaded episode results in a single item queue`() {
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e1))

        queue shouldBe listOf(s1e1.itemId)
    }

    @Test
    fun `an item without a series falls back to itself`() {
        val movie = DownloadEntity(
            id = 1,
            serverId = 1,
            userId = 1,
            itemId = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
            path = "movies/movie",
            item = BaseItemDto(
                id = UUID.fromString("00000000-0000-0000-0000-00000000000a"),
                type = BaseItemKind.MOVIE,
                mediaType = MediaType.VIDEO,
            ),
        )

        val queue = DownloadPlaybackQueue.build(movie, listOf(movie))

        queue shouldBe listOf(movie.itemId)
    }

    @Test
    fun `missing season and episode numbers are treated as zero`() {
        val unknown = episode("00000000-0000-0000-0000-000000000001", season = null, number = null)
        val s1e1 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 1)

        val queue = DownloadPlaybackQueue.build(unknown, listOf(s1e1, unknown))

        queue shouldBe listOf(unknown.itemId, s1e1.itemId)
    }

    @Test
    fun `downloads of another user are not part of the queue`() {
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)
        val s1e2 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 2)
        val otherUser = episode("00000000-0000-0000-0000-000000000003", season = 1, number = 3, userId = 2)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e1, s1e2, otherUser))

        queue shouldBe listOf(s1e1.itemId, s1e2.itemId)
    }

    @Test
    fun `downloads of another server are not part of the queue`() {
        val s1e1 = episode("00000000-0000-0000-0000-000000000001", season = 1, number = 1)
        val s1e2 = episode("00000000-0000-0000-0000-000000000002", season = 1, number = 2)
        val otherServer = episode("00000000-0000-0000-0000-000000000003", season = 1, number = 3, serverId = 2)

        val queue = DownloadPlaybackQueue.build(s1e1, listOf(s1e1, s1e2, otherServer))

        queue shouldBe listOf(s1e1.itemId, s1e2.itemId)
    }
}
