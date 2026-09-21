package org.jellyfin.mobile.ui.screens.downloads

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
import org.jellyfin.mobile.downloads.DownloadFileType
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.extensions.ticks
import org.koin.compose.koinInject

@Composable
fun DownloadsList(
    downloads: List<DownloadFiles>,
    onOpen: (DownloadEntity) -> Unit,
    onDownload: (DownloadEntity) -> Unit,
    onCancel: (DownloadEntity) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues.Zero,
    selection: Set<Long> = emptySet(),
    onToggleSelection: (DownloadEntity) -> Unit = {},
) {
    val selectionMode = selection.isNotEmpty()

    // Group episodic downloads by series so the list mirrors the library structure
    // (series -> season -> episode) instead of a flat history.
    val groups = remember(downloads) {
        downloads.groupBy { files ->
            val item = files.download.item
            when {
                item.type == BaseItemKind.EPISODE || item.type == BaseItemKind.RECORDING -> item.seriesName
                else -> null
            }
        }
    }

    LazyColumn(
        modifier = modifier,
        contentPadding = contentPadding,
    ) {
        // Series groups first, with a header per series and sorted by season/episode.
        groups.filterKeys { it != null }
            .toList()
            .sortedBy { (name, _) -> name }
            .forEach { (seriesName, seriesDownloads) ->
                item(key = "header-$seriesName") {
                    Text(
                        text = seriesName.orEmpty(),
                        style = MaterialTheme.typography.subtitle1,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                items(
                    seriesDownloads.sortedWith(
                        compareBy(
                            { it.download.item.parentIndexNumber ?: 0 },
                            { it.download.item.indexNumber ?: 0 },
                        ),
                    ),
                    key = { it.download.id },
                ) { downloadFiles ->
                    DownloadItem(
                        downloadFiles = downloadFiles,
                        onOpen = { onOpen(downloadFiles.download) },
                        onDownload = { onDownload(downloadFiles.download) },
                        onCancel = { onCancel(downloadFiles.download) },
                        onToggleSelection = { onToggleSelection(downloadFiles.download) },
                        isSelected = selection.contains(downloadFiles.download.id),
                        selectionMode = selectionMode,
                    )
                }
            }

        items(
            groups[null].orEmpty(),
            key = { it.download.id },
        ) { downloadFiles ->
            DownloadItem(
                downloadFiles = downloadFiles,
                onOpen = { onOpen(downloadFiles.download) },
                onDownload = { onDownload(downloadFiles.download) },
                onCancel = { onCancel(downloadFiles.download) },
                onToggleSelection = { onToggleSelection(downloadFiles.download) },
                isSelected = selection.contains(downloadFiles.download.id),
                selectionMode = selectionMode,
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
    val imageFile = files.find { it.type == DownloadFileType.IMAGE_PRIMARY }
    val imageRequest = remember(imageFile) {
        imageFile?.uri?.let { uri ->
            ImageRequest.Builder(context)
                .data(uri)
                .memoryCacheKey("${uri}:${imageFile.size}:${imageFile.status}")
                .diskCacheKey("${uri}:${imageFile.size}:${imageFile.status}")
                .build()
        }
    }

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
