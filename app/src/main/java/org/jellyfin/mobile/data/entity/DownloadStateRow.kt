package org.jellyfin.mobile.data.entity

import androidx.room.ColumnInfo
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.UUID

/**
 * Projection of the download table exposing only the state of an item.
 */
data class DownloadStateRow(
    @ColumnInfo(name = "item_id") val itemId: UUID,
    @ColumnInfo(name = "status") val status: DownloadStatus,
)
