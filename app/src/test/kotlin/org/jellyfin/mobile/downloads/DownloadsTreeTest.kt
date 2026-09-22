package org.jellyfin.mobile.downloads

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.ui.screens.downloads.DownloadsTree
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import org.junit.jupiter.api.Test

class DownloadsTreeTest {
    private companion object {
        private val BING_ID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        private val BLUEY_ID = UUID.fromString("22222222-2222-2222-2222-222222222222")
        private val BING_SEASON_1 = UUID.fromString("33333333-3333-3333-3333-333333333333")
        private val BING_SEASON_2 = UUID.fromString("44444444-4444-4444-4444-444444444444")

        private fun episode(
            id: String,
            name: String,
            seriesId: UUID?,
            seriesName: String?,
            seasonId: UUID?,
            season: Int?,
            number: Int?,
            kind: BaseItemKind = BaseItemKind.EPISODE,
        ) = DownloadFiles(
            download = DownloadEntity(
                id = id.hashCode().toLong(),
                serverId = 1,
                userId = 1,
                itemId = UUID.fromString(id),
                path = name,
                item = BaseItemDto(
                    id = UUID.fromString(id),
                    name = name,
                    type = kind,
                    mediaType = MediaType.VIDEO,
                    seriesId = seriesId,
                    seriesName = seriesName,
                    seasonId = seasonId,
                    parentIndexNumber = season,
                    indexNumber = number,
                ),
            ),
            files = emptyList(),
        )

        private fun movie(id: String, name: String) = DownloadFiles(
            download = DownloadEntity(
                id = id.hashCode().toLong(),
                serverId = 1,
                userId = 1,
                itemId = UUID.fromString(id),
                path = name,
                item = BaseItemDto(
                    id = UUID.fromString(id),
                    name = name,
                    type = BaseItemKind.MOVIE,
                    mediaType = MediaType.VIDEO,
                ),
            ),
            files = emptyList(),
        )
    }

    @Test
    fun `episodes are grouped into a series and its seasons`() {
        val downloads = listOf(
            episode("00000000-0000-0000-0000-000000000001", "Pa, pa", BING_ID, "Bing", BING_SEASON_1, 1, 2),
            episode("00000000-0000-0000-0000-000000000002", "Klocki", BING_ID, "Bing", BING_SEASON_1, 1, 4),
            episode("00000000-0000-0000-0000-000000000003", "Nowy sezon", BING_ID, "Bing", BING_SEASON_2, 2, 1),
            episode("00000000-0000-0000-0000-000000000004", "Bluey 1", BLUEY_ID, "Bluey", BING_SEASON_1, 1, 1),
        )

        val tree = DownloadsTree.build(downloads)

        tree.series.map { it.name } shouldContainExactly listOf("Bing", "Bluey")
        tree.items shouldBe emptyList()

        val bing = tree.series.first { it.name == "Bing" }
        bing.seasons.map { it.indexNumber } shouldContainExactly listOf(1, 2)
        bing.seasons.first().downloads.map { it.download.item.name } shouldContainExactly listOf("Pa, pa", "Klocki")
        bing.downloads shouldBe bing.seasons.flatMap { it.downloads }
    }

    @Test
    fun `movies stay single items next to the series`() {
        val downloads = listOf(
            episode("00000000-0000-0000-0000-000000000001", "Pa, pa", BING_ID, "Bing", BING_SEASON_1, 1, 2),
            movie("00000000-0000-0000-0000-000000000005", "8 Mila"),
        )

        val tree = DownloadsTree.build(downloads)

        tree.series.map { it.name } shouldContainExactly listOf("Bing")
        tree.items.map { it.download.item.name } shouldContainExactly listOf("8 Mila")
    }

    @Test
    fun `recordings of a series are grouped as well`() {
        val downloads = listOf(
            episode(
                "00000000-0000-0000-0000-000000000001",
                "Nagranie",
                BING_ID,
                "Bing",
                BING_SEASON_1,
                1,
                1,
                kind = BaseItemKind.RECORDING,
            ),
        )

        val tree = DownloadsTree.build(downloads)

        tree.series.map { it.name } shouldContainExactly listOf("Bing")
    }

    @Test
    fun `a recording without a series stays a single item`() {
        val downloads = listOf(
            episode(
                "00000000-0000-0000-0000-000000000001",
                "Wiadomości",
                null,
                null,
                null,
                1,
                1,
                kind = BaseItemKind.RECORDING,
            ),
        )

        val tree = DownloadsTree.build(downloads)

        tree.series shouldBe emptyList()
        tree.items.map { it.download.item.name } shouldContainExactly listOf("Wiadomości")
    }

    @Test
    fun `episodes without a season id are grouped by their season number`() {
        val downloads = listOf(
            episode("00000000-0000-0000-0000-000000000001", "Odcinek 1", BING_ID, "Bing", null, 1, 1),
            episode("00000000-0000-0000-0000-000000000002", "Odcinek 2", BING_ID, "Bing", null, 1, 2),
        )

        val tree = DownloadsTree.build(downloads)

        val seasons = tree.series.single().seasons
        seasons.size shouldBe 1
        seasons.single().downloads.size shouldBe 2
    }
}
