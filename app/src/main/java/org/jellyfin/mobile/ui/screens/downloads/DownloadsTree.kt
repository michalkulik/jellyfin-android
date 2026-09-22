package org.jellyfin.mobile.ui.screens.downloads

import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

/**
 * The downloads screen mirrors the structure of a library: episodes are grouped into seasons and
 * seasons into series, so a downloaded series is entered as a series, then a season and only then
 * the episodes. Everything that is not episodic (movies, songs, ...) stays a single item.
 */
data class DownloadsTree(
    val series: List<SeriesNode>,
    val items: List<DownloadFiles>,
) {
    val isEmpty: Boolean get() = series.isEmpty() && items.isEmpty()

    companion object {
        fun build(downloads: List<DownloadFiles>): DownloadsTree {
            val (episodic, standalone) = downloads.partition { it.download.item.isEpisodic }

            val series = episodic
                .groupBy { it.download.item.seriesKey }
                .map { (key, group) ->
                    SeriesNode(
                        key = key,
                        seriesId = group.firstNotNullOfOrNull { it.download.item.seriesId },
                        name = group.first().download.item.seriesName.orEmpty(),
                        seasons = group
                            .groupBy { it.download.item.seasonKey }
                            .map { (seasonKey, seasonGroup) ->
                                SeasonNode(
                                    key = seasonKey,
                                    seasonId = seasonGroup.firstNotNullOfOrNull { it.download.item.seasonId },
                                    indexNumber = seasonGroup.firstNotNullOfOrNull {
                                        it.download.item.parentIndexNumber
                                    },
                                    downloads = seasonGroup.sortedWith(EPISODE_ORDER),
                                )
                            }
                            .sortedWith(compareBy({ it.indexNumber ?: Int.MAX_VALUE }, { it.key })),
                    )
                }
                .sortedBy { it.name.lowercase() }

            return DownloadsTree(
                series = series,
                items = standalone.sortedWith(compareBy({ it.download.item.name?.lowercase() ?: "" })),
            )
        }

        private val EPISODE_ORDER = compareBy<DownloadFiles>(
            { it.download.item.parentIndexNumber ?: 0 },
            { it.download.item.indexNumber ?: 0 },
            { it.download.item.name ?: "" },
        )

        /** An item belongs to a series when it is an episode, or a recording of a series. */
        private val BaseItemDto.isEpisodic: Boolean
            get() = type in arrayOf(BaseItemKind.EPISODE, BaseItemKind.RECORDING) && !seriesName.isNullOrEmpty()

        private val BaseItemDto.seriesKey: String
            get() = seriesId?.toString() ?: "name:${seriesName.orEmpty()}"

        private val BaseItemDto.seasonKey: String
            get() = seasonId?.toString() ?: "index:${parentIndexNumber ?: 0}"
    }
}

data class SeriesNode(
    val key: String,
    val seriesId: UUID?,
    val name: String,
    val seasons: List<SeasonNode>,
) {
    val downloads: List<DownloadFiles> get() = seasons.flatMap { it.downloads }
}

data class SeasonNode(
    val key: String,
    val seasonId: UUID?,
    val indexNumber: Int?,
    val downloads: List<DownloadFiles>,
)

/**
 * The folder that is currently opened in the downloads screen. The root level shows the series and
 * the standalone items, a series shows its seasons and a season shows its episodes.
 */
sealed interface DownloadsFolder {
    /** Name shown as the screen title while this folder is open. */
    val name: String

    data class Series(val seriesKey: String, override val name: String) : DownloadsFolder

    data class Season(val seriesKey: String, val seasonKey: String, override val name: String) : DownloadsFolder
}

/** Ids of every download that is visible on the given level, used by "select all". */
fun DownloadsFolder?.downloadIds(downloads: List<DownloadFiles>): List<Long> {
    val tree = DownloadsTree.build(downloads)

    val visible = when (this) {
        null -> tree.series.flatMap { it.downloads } + tree.items
        is DownloadsFolder.Series -> tree.series.firstOrNull { it.key == seriesKey }?.downloads.orEmpty()
        is DownloadsFolder.Season -> tree.series.firstOrNull { it.key == seriesKey }
            ?.seasons
            ?.firstOrNull { it.key == seasonKey }
            ?.downloads
            .orEmpty()
    }

    return visible.map { it.download.id }
}
