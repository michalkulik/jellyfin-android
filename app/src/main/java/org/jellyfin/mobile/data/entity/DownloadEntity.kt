package org.jellyfin.mobile.data.entity

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import java.util.UUID

@Entity(
    tableName = "download",
    indices = [Index(value = ["server_id"]), Index(value = ["user_id"]), Index(value = ["item_id"])],
    foreignKeys = [
        ForeignKey(
            entity = ServerEntity::class,
            parentColumns = ["id"],
            childColumns = ["server_id"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = UserEntity::class,
            parentColumns = ["id"],
            childColumns = ["user_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class DownloadEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id") val id: Long = 0L,

    @ColumnInfo(name = "server_id") val serverId: Long,
    @ColumnInfo(name = "user_id") val userId: Long,
    @ColumnInfo(name = "item_id") val itemId: UUID,

    @ColumnInfo(name = "path") val path: String,
    @ColumnInfo(name = "item") val item: BaseItemDto,

    /// Maximum bitrate in bits per second requested for the server side conversion. Null means the original file.
    @ColumnInfo(name = "max_bitrate") val maxBitrate: Int? = null,

    /// Maximum video height requested for the server side conversion.
    @ColumnInfo(name = "max_height") val maxHeight: Int? = null,

    /// Media source id the download was created for.
    @ColumnInfo(name = "media_source_id") val mediaSourceId: String? = null,

    /// Id of the server side conversion job, used to cancel it. Null when no conversion is running.
    @ColumnInfo(name = "job_id") val jobId: String? = null,

    /// Progress of the active phase in percent (0-100), or -1 when unknown.
    @ColumnInfo(name = "progress", defaultValue = "-1") val progress: Int = -1,

    @ColumnInfo(name = "status") val status: DownloadStatus = DownloadStatus.QUEUED,

    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "modified_at") var modifiedAt: Long = System.currentTimeMillis(),
) {
    fun getDisplayName(context: Context) = buildString {
        val name = if (
            item.type in arrayOf(BaseItemKind.PROGRAM, BaseItemKind.RECORDING) &&
            (item.isSeries == true || !item.episodeTitle.isNullOrEmpty())
        ) {
            item.episodeTitle
        } else {
            item.name
        }

        val extraInfo = when (item.type) {
            BaseItemKind.TV_CHANNEL if !item.channelNumber.isNullOrEmpty() -> item.channelNumber
            BaseItemKind.EPISODE if item.parentIndexNumber == 0 -> context.getString(R.string.special_episode)
            in arrayOf(BaseItemKind.EPISODE, BaseItemKind.RECORDING) if item.indexNumber != null && item.parentIndexNumber != null ->
                "S${item.parentIndexNumber}:E${item.indexNumber}${item.indexNumberEnd?.let { n -> "-$n" }.orEmpty()}"

            else -> ""
        }

        listOf(item.seriesName, extraInfo, name)
            .filter { str -> !str.isNullOrEmpty() }
            .joinTo(this, separator = " - ")

        if (item.type == BaseItemKind.MOVIE && item.productionYear != null) {
            append(" (${item.productionYear})")
        } else if (item.premiereDate != null) {
            append(" (${item.premiereDate!!.year})")
        }
    }.ifEmpty { item.name }
}
