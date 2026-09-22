package org.jellyfin.mobile.ui.screens.downloads

import android.content.Context
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.Checkbox
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.StorageManager
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.downloads.DownloadArtwork
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject

@Composable
fun DownloadsList(
    downloads: List<DownloadFiles>,
    folder: DownloadsFolder?,
    onOpenFolder: (DownloadsFolder) -> Unit,
    onOpen: (DownloadEntity) -> Unit,
    onDownload: (DownloadEntity) -> Unit,
    onCancel: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
    onToggleSelection: (List<Long>) -> Unit = {},
) {
    val tree = remember(downloads) { DownloadsTree.build(downloads) }
    val selectionMode = selection.isNotEmpty()
    val context = LocalContext.current

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        when (folder) {
            null -> {
                items(tree.series, key = { "series-${it.key}" }) { series ->
                    SeriesFolderItem(
                        series = series,
                        selection = selection,
                        selectionMode = selectionMode,
                        onClick = { onOpenFolder(DownloadsFolder.Series(series.key, series.name)) },
                        onToggleSelection = onToggleSelection,
                    )
                }

                items(tree.items, key = { it.download.id }) { downloadFiles ->
                    DownloadItem(
                        downloadFiles = downloadFiles,
                        onOpen = { onOpen(downloadFiles.download) },
                        onDownload = { onDownload(downloadFiles.download) },
                        onCancel = { onCancel(downloadFiles.download) },
                        onToggleSelection = { onToggleSelection(listOf(downloadFiles.download.id)) },
                        isSelected = selection.contains(downloadFiles.download.id),
                        selectionMode = selectionMode,
                    )
                }
            }

            is DownloadsFolder.Series -> {
                val series = tree.series.firstOrNull { it.key == folder.seriesKey }

                items(series?.seasons.orEmpty(), key = { "season-${folder.seriesKey}-${it.key}" }) { season ->
                    SeasonFolderItem(
                        season = season,
                        selection = selection,
                        selectionMode = selectionMode,
                        onClick = {
                            onOpenFolder(
                                DownloadsFolder.Season(
                                    seriesKey = folder.seriesKey,
                                    seasonKey = season.key,
                                    name = season.displayName(context),
                                ),
                            )
                        },
                        onToggleSelection = onToggleSelection,
                    )
                }
            }

            is DownloadsFolder.Season -> {
                val season = tree.series.firstOrNull { it.key == folder.seriesKey }
                    ?.seasons
                    ?.firstOrNull { it.key == folder.seasonKey }

                items(season?.downloads.orEmpty(), key = { it.download.id }) { downloadFiles ->
                    DownloadItem(
                        downloadFiles = downloadFiles,
                        onOpen = { onOpen(downloadFiles.download) },
                        onDownload = { onDownload(downloadFiles.download) },
                        onCancel = { onCancel(downloadFiles.download) },
                        onToggleSelection = { onToggleSelection(listOf(downloadFiles.download.id)) },
                        isSelected = selection.contains(downloadFiles.download.id),
                        selectionMode = selectionMode,
                    )
                }
            }
        }
    }
}

@Composable
private fun SeriesFolderItem(
    series: SeriesNode,
    selection: Set<Long>,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val ids = remember(series) { series.downloads.map { it.download.id } }

    FolderItem(
        name = series.name,
        subtitle = context.resources.getQuantityString(
            R.plurals.download_season_count,
            series.seasons.size,
            series.seasons.size,
        ),
        imageModel = series.seriesId?.let { DownloadArtwork.seriesFile(context, it) }
            ?: remember(series) { primaryImageRequest(context, series.downloads.firstOrNull()) },
        isSelected = ids.isNotEmpty() && ids.all { it in selection },
        selectionMode = selectionMode,
        onClick = onClick,
        onToggleSelection = { onToggleSelection(ids) },
    )
}

@Composable
private fun SeasonFolderItem(
    season: SeasonNode,
    selection: Set<Long>,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: (List<Long>) -> Unit,
) {
    val context = LocalContext.current
    val ids = remember(season) { season.downloads.map { it.download.id } }

    FolderItem(
        name = season.displayName(context),
        subtitle = context.resources.getQuantityString(
            R.plurals.download_episode_count,
            season.downloads.size,
            season.downloads.size,
        ),
        imageModel = season.seasonId?.let { DownloadArtwork.seasonFile(context, it) }
            ?: remember(season) { primaryImageRequest(context, season.downloads.firstOrNull()) },
        isSelected = ids.isNotEmpty() && ids.all { it in selection },
        selectionMode = selectionMode,
        onClick = onClick,
        onToggleSelection = { onToggleSelection(ids) },
    )
}

private fun SeasonNode.displayName(context: Context): String =
    indexNumber?.takeIf { it > 0 }
        ?.let { context.getString(R.string.download_season_name, it) }
        ?: context.getString(R.string.special_episode)

@Composable
private fun FolderItem(
    name: String,
    subtitle: String,
    imageModel: Any?,
    isSelected: Boolean,
    selectionMode: Boolean,
    onClick: () -> Unit,
    onToggleSelection: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selectionMode) onToggleSelection() else onClick() },
                onLongClick = onToggleSelection,
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        AsyncImage(
            model = imageModel,
            placeholder = painterResource(R.drawable.ic_local_movies_white_64),
            error = painterResource(R.drawable.ic_local_movies_white_64),
            fallback = painterResource(R.drawable.ic_local_movies_white_64),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 64.dp, height = 64.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            Text(
                text = name,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )
            Text(
                text = subtitle,
                overflow = TextOverflow.Ellipsis,
                maxLines = 1,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
fun DownloadItem(
    downloadFiles: DownloadFiles,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onToggleSelection: () -> Unit,
    modifier: Modifier = Modifier,
    isSelected: Boolean = false,
    selectionMode: Boolean = false,
) {
    val (download, files) = downloadFiles
    val context = LocalContext.current
    val storageManager: StorageManager = koinInject()

    val isVerified by produceState(initialValue = false, downloadFiles) {
        value = withContext(Dispatchers.IO) {
            storageManager.verify(downloadFiles)
        }
    }

    val isActive = download.status.isActive

    // The stored image is created before it is downloaded, so it can be empty or incomplete while
    // the download runs. Coil caches by url, which means it would keep serving the failed (or
    // placeholder) result, so the size and status of the file are part of the cache key to force a
    // reload once the file actually changed.
    val imageRequest = remember(downloadFiles) { primaryImageRequest(context, downloadFiles) }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {
                    when {
                        selectionMode -> onToggleSelection()
                        isActive -> Unit
                        !isVerified -> onDownload()
                        else -> onOpen()
                    }
                },
                onLongClick = { onToggleSelection() },
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AnimatedVisibility(
            visible = selectionMode,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = null,
                modifier = Modifier.padding(end = 8.dp),
            )
        }

        AsyncImage(
            model = imageRequest,
            placeholder = painterResource(R.drawable.ic_local_movies_white_64),
            error = painterResource(R.drawable.ic_local_movies_white_64),
            fallback = painterResource(R.drawable.ic_local_movies_white_64),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(width = 64.dp, height = 64.dp),
        )

        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 16.dp),
        ) {
            val name = remember(download, context) { download.getDisplayName(context).orEmpty() }
            Text(
                text = name,
                overflow = TextOverflow.Ellipsis,
                maxLines = 2,
            )

            when {
                isActive -> DownloadProgress(status = download.status, progress = download.progress)
                isVerified -> Text(
                    text = Formatter.formatShortFileSize(context, files.sumOf { it.size }),
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.caption,
                )

                download.status == DownloadStatus.CANCELLED -> Text(
                    text = stringResource(R.string.download_cancelled),
                    color = Color.Gray,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.caption,
                )

                else -> Text(
                    text = stringResource(R.string.download_incomplete),
                    color = Color.Yellow,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                    style = MaterialTheme.typography.caption,
                )
            }

            DownloadPlaybackStatus(download)
        }

        if (isActive) {
            IconButton(
                onClick = onCancel,
                enabled = !selectionMode,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.download_cancel),
                )
            }
        }
    }
}

/**
 * Loads the primary image that was stored next to the download.
 *
 * The stored image is created before it is downloaded, so it can be empty or incomplete while the
 * download runs. Coil caches by url, which means it would keep serving the failed (or placeholder)
 * result, so the size and status of the file are part of the cache key to force a reload once the
 * file actually changed.
 */
private fun primaryImageRequest(context: Context, downloadFiles: DownloadFiles?): ImageRequest? {
    val imageFile = downloadFiles?.files?.find { it.type == DownloadFileType.IMAGE_PRIMARY } ?: return null
    val uri = imageFile.uri

    return ImageRequest.Builder(context)
        .data(uri)
        .memoryCacheKey("$uri:${imageFile.size}:${imageFile.status}")
        .diskCacheKey("$uri:${imageFile.size}:${imageFile.status}")
        .build()
}

/**
 * Shows whether the item was already watched or where it will continue.
 *
 * The position is remembered for downloaded items just like it is for items played from the server,
 * so a paused episode can be resumed and a watched one is marked as such.
 */
@Composable
private fun DownloadPlaybackStatus(download: DownloadEntity) {
    val resumePositionTicks = download.resumePositionTicks

    when {
        download.played -> DownloadPlaybackStatusLine(
            icon = Icons.Outlined.CheckCircle,
            text = stringResource(R.string.download_watched),
        )

        resumePositionTicks != null -> DownloadPlaybackStatusLine(
            icon = Icons.Outlined.PlayArrow,
            text = stringResource(
                R.string.download_resume_from,
                DateUtils.formatElapsedTime(resumePositionTicks.ticks.inWholeSeconds),
            ),
        )
    }
}

@Composable
private fun DownloadPlaybackStatusLine(
    icon: ImageVector,
    text: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(top = 2.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colors.primary,
            modifier = Modifier.size(14.dp),
        )
        Text(
            text = text,
            color = MaterialTheme.colors.primary,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            style = MaterialTheme.typography.caption,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

/**
 * Shows which phase is running (converting or downloading) and how far along it is.
 */
@Composable
private fun DownloadProgress(
    status: DownloadStatus,
    progress: Int,
) {
    val label = when (status) {
        DownloadStatus.CONVERTING -> stringResource(R.string.download_converting)
        DownloadStatus.QUEUED -> stringResource(R.string.download_queued)
        else -> stringResource(R.string.download_downloading)
    }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = label,
                style = MaterialTheme.typography.caption,
                modifier = Modifier.weight(1f, fill = false),
            )
            if (progress in 0..100) {
                Text(
                    text = " ${progress}%",
                    style = MaterialTheme.typography.caption,
                )
            }
        }

        if (progress in 0..100) {
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        } else {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            )
        }
    }
}
