package org.jellyfin.mobile.downloads

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.File
import java.util.UUID

/**
 * Artwork of the groups shown in the downloads screen.
 *
 * Series and seasons are not downloaded themselves, only their episodes are, so their posters are
 * kept separately from the episode folders. One file is shared by every episode of the group, it is
 * downloaded once and it stays available when a single episode is removed. The files live in the app
 * private storage, which keeps them out of the user visible download folder and away from the
 * download verification, which only looks at the files recorded for an item.
 */
object DownloadArtwork {
    private const val DIRECTORY = "download_artwork"
    private const val EXTENSION = "webp"

    private fun directory(context: Context) = File(context.filesDir, DIRECTORY)

    fun seriesFile(context: Context, seriesId: UUID) = File(directory(context), "series_$seriesId.$EXTENSION")

    fun seasonFile(context: Context, seasonId: UUID) = File(directory(context), "season_$seasonId.$EXTENSION")

    /**
     * The series poster of the item, or null when the server has none.
     */
    fun seriesArtwork(context: Context, item: BaseItemDto): Artwork? = item.seriesId
        ?.takeIf { item.seriesPrimaryImageTag != null }
        ?.let { seriesId ->
            Artwork(
                file = seriesFile(context, seriesId),
                itemId = seriesId,
                tag = item.seriesPrimaryImageTag,
            )
        }

    /**
     * The season poster of the item, or null when the server has none.
     *
     * The parent primary image of an episode is the poster of its season.
     */
    fun seasonArtwork(context: Context, item: BaseItemDto): Artwork? = item.parentPrimaryImageItemId
        ?.takeIf { item.parentPrimaryImageTag != null }
        ?.let { seasonId ->
            Artwork(
                file = seasonFile(context, seasonId),
                itemId = seasonId,
                tag = item.parentPrimaryImageTag,
            )
        }

    /**
     * Removes the artwork of groups that no longer have a downloaded item, so the app private
     * storage does not keep growing while the downloads are removed.
     */
    fun prune(context: Context, seriesIds: Set<UUID>, seasonIds: Set<UUID>) {
        val files = directory(context).listFiles() ?: return

        for (file in files) {
            val name = file.nameWithoutExtension
            val id = name.substringAfter('_', "").takeIf { it.isNotEmpty() }
                ?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                ?: continue

            val keep = when {
                name.startsWith("series_") -> id in seriesIds
                name.startsWith("season_") -> id in seasonIds
                else -> true
            }

            if (!keep) file.delete()
        }
    }

    data class Artwork(
        val file: File,
        val itemId: UUID,
        val tag: String?,
    ) {
        val uri: Uri get() = file.toUri()

        val isPresent: Boolean get() = file.exists() && file.length() > 0
    }
}
