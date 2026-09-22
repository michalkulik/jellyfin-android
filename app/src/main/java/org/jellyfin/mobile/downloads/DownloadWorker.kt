package org.jellyfin.mobile.downloads

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.guava.await
import org.jellyfin.mobile.app.AppPreferences
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.IOException

class DownloadWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters), KoinComponent {
    companion object {
        private val tag = DownloadWorker::class.qualifiedName!!

        suspend fun start(context: Context, appPreferences: AppPreferences) {
            // Downloads are explicitly started by the user, so no network constraint is applied:
            // an unmet constraint would silently defer the work and show nothing to the user.
            val request = OneTimeWorkRequestBuilder<DownloadWorker>().apply {
                addTag(tag)
                setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }.build()

            WorkManager.getInstance(context).enqueueUniqueWork(tag, ExistingWorkPolicy.REPLACE, request).await()
        }

        suspend fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(tag).await()
        }

        suspend fun restart(context: Context, appPreferences: AppPreferences) {
            stop(context)
            start(context, appPreferences)
        }

        suspend fun isActive(context: Context): Boolean = WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(tag)
            .await()
            .any { workInfo -> workInfo.state == WorkInfo.State.RUNNING }
    }

    private val downloadNotificationManager by inject<DownloadNotificationManager>()
    private val downloadQueue by inject<DownloadQueue>()

    override suspend fun getForegroundInfo(): ForegroundInfo = downloadNotificationManager.createForegroundInfo()

    override suspend fun doWork(): Result {
        Timber.i("DownloadWorker started")
        val canProcess = downloadQueue.prepare()
        // Existing downloads may predate the group artwork, so it is filled in on every run.
        val canProcessArtwork = downloadQueue.prepareArtwork()
        if (!canProcess && !canProcessArtwork) {
            Timber.i("DownloadWorker: nothing queued")
            return Result.success()
        }

        setForeground(getForegroundInfo())
        return try {
            downloadQueue.process()
            downloadQueue.processArtwork()
            Timber.i("DownloadWorker finished")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Timber.w(e, "DownloadWorker network error, retrying")
            Result.retry()
        } catch (e: Exception) {
            Timber.e(e, "DownloadWorker failed")
            Result.failure()
        }
    }
}
