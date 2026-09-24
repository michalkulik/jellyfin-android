package org.jellyfin.mobile.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import org.jellyfin.mobile.data.entity.DownloadEntity
import org.jellyfin.mobile.data.entity.DownloadFileEntity
import org.jellyfin.mobile.data.entity.DownloadFiles
import org.jellyfin.mobile.data.entity.DownloadStateRow
import org.jellyfin.mobile.downloads.DownloadStatus
import org.jellyfin.sdk.model.UUID

@Dao
interface DownloadDao {
    /**
     * One shot lookup of every download, used to build the playback queue of downloaded items.
     */
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    suspend fun getAllDownloadsOnce(): List<DownloadEntity>

    @Transaction
    @Query("SELECT * FROM download ORDER BY created_at DESC")
    fun getAllDownloadsWithFiles(): Flow<List<DownloadFiles>>

    @Transaction
    @Query("UPDATE download SET status = 'QUEUED', job_id = NULL, progress = -1, modified_at = :modifiedAt WHERE status IN ('CONVERTING', 'DOWNLOADING')")
    suspend fun requeueActiveDownloads(modifiedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM download WHERE status = 'QUEUED' OR status = 'CONVERTING' OR status = 'DOWNLOADING' ORDER BY created_at ASC")
    fun getQueuedDownloads(): List<DownloadFiles>

    @Query("SELECT * FROM download WHERE item_id IN (:itemIds)")
    fun getDownloadsByItemIds(itemIds: Collection<UUID>): List<DownloadEntity>

    /**
     * Lightweight lookup of the download state of every known item, used to reflect the state in
     * the web based user interface.
     */
    @Query("SELECT item_id, status FROM download")
    suspend fun getDownloadStates(): List<DownloadStateRow>

    /**
     * Flow variant of [getDownloadStates]. Observers that only need the state must not read the full
     * rows, because every progress write invalidates the table and they would decode the stored item
     * of every download each time.
     */
    @Query("SELECT item_id, status FROM download")
    fun getDownloadStatesFlow(): Flow<List<DownloadStateRow>>

    @Query("SELECT * FROM download WHERE item_id = :itemId")
    fun getDownloadByItemId(itemId: UUID): DownloadEntity?

    @Query("SELECT * FROM download WHERE path = :path")
    suspend fun getDownloadsByPath(path: String): List<DownloadEntity>

    /**
     * Updates only the progress of a download so the progress updates don't overwrite other columns
     * written by concurrent workers.
     */
    @Query("UPDATE download SET progress = :progress, modified_at = :modifiedAt WHERE id = :id")
    suspend fun updateProgress(id: Long, progress: Int, modifiedAt: Long = System.currentTimeMillis())

    /**
     * Stores where the playback of a downloaded item stopped, so it can be continued later.
     */
    @Query("UPDATE download SET position_ticks = :positionTicks, played = :played, modified_at = :modifiedAt WHERE item_id = :itemId")
    suspend fun updatePlaybackState(
        itemId: UUID,
        positionTicks: Long,
        played: Boolean,
        modifiedAt: Long = System.currentTimeMillis(),
    )

    @Query("UPDATE download SET status = :status, job_id = :jobId, progress = :progress, modified_at = :modifiedAt WHERE id = :id")
    suspend fun updatePhase(
        id: Long,
        status: DownloadStatus,
        jobId: String?,
        progress: Int,
        modifiedAt: Long = System.currentTimeMillis(),
    )

    @Query("SELECT * FROM download WHERE id = :id")
    suspend fun getDownload(id: Long): DownloadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DownloadEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun update(entity: DownloadEntity): Int

    @Query("DELETE FROM download WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM download_file WHERE download_id = :downloadId")
    suspend fun getFiles(downloadId: Long): List<DownloadFileEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFile(entity: DownloadFileEntity): Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    suspend fun updateFile(entity: DownloadFileEntity): Int
}
