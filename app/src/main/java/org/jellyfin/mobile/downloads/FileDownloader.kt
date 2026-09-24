package org.jellyfin.mobile.downloads

import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.IOException
import kotlin.coroutines.resumeWithException

class FileDownloader(
    private val okHttpClient: OkHttpClient,
) {
    private companion object {
        /**
         * Size of the read buffer. A bigger buffer keeps the number of read/write syscalls and the
         * number of progress reports down, which matters on a fast connection.
         */
        private const val BUFFER_SIZE = 64 * 1024

        /**
         * How often the progress is reported while a file is written.
         *
         * Reporting is not free: the callback stores the progress in the database, which invalidates
         * the download table and makes every observer reload it. Doing that for every buffer (10 KB)
         * kept a fast download at a few hundred kB/s, so it is done on a timer instead. Four updates
         * per second is smooth enough for a progress bar and still matches the notification throttle.
         */
        private const val PROGRESS_REPORT_INTERVAL_MS = 250L
    }

    fun interface ProgressCallback {
        suspend fun onProgress(downloaded: Long, total: Long)

        companion object Empty : ProgressCallback {
            override suspend fun onProgress(downloaded: Long, total: Long) = Unit
        }
    }

    private suspend fun download(
        api: ApiClient,
        from: Uri,
        rangeStart: Long? = null,
    ): Response {
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )

        val request = Request.Builder().apply {
            url(from.toString())

            header("Authorization", authorizationHeader)
            rangeStart?.let { header("Range", "bytes=$rangeStart-") }
        }.build()

        val response = okHttpClient.newCall(request).await()

        // 416 (Requested Range Not Satisfiable) can happen when we've already fully downloaded the file
        if (response.code == 416 && rangeStart != null && rangeStart >= response.getContentRange().total) return response

        // Throw for other unsuccessful responses
        if (!response.isSuccessful) throw IOException("Unexpected response $response")

        return response
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
        enqueue(
            object : Callback {
                override fun onResponse(call: Call, response: Response) {
                    continuation.resume(response) { cause, response, _ ->
                        response.close()
                    }
                }

                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }
            },
        )

        continuation.invokeOnCancellation {
            cancel()
        }
    }

    private fun Response.getContentRange() = when (code) {
        200 -> requireNotNull(header("Content-Length")).let(ContentRange::fromContentLengthHeader)
        206, 416 -> requireNotNull(header("Content-Range")).let(ContentRange::fromContentRangeHeader)
        else -> error("Invalid response code $code")
    }

    private suspend fun save(
        response: Response,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback,
    ) = withContext(Dispatchers.IO) {
        val contentRange = response.getContentRange()

        val output = ParcelFileDescriptor.AutoCloseOutputStream(to)
        output.channel.position(contentRange.start)

        val inputStream = response.body?.byteStream() ?: error("Response does not contain a body")
        inputStream.use { inputStream ->
            output.use { outputFile ->
                val buffer = ByteArray(BUFFER_SIZE)
                var totalRead = contentRange.start
                var lastReportedTotal = totalRead
                var lastReportAt = SystemClock.elapsedRealtime()

                while (true) {
                    val bytesRead = inputStream.read(buffer)
                    if (bytesRead == -1) break

                    coroutineContext.ensureActive()

                    outputFile.write(buffer, 0, bytesRead)
                    totalRead += bytesRead

                    val now = SystemClock.elapsedRealtime()
                    if (now - lastReportAt >= PROGRESS_REPORT_INTERVAL_MS) {
                        lastReportAt = now
                        lastReportedTotal = totalRead
                        progressCallback.onProgress(totalRead, contentRange.total)
                    }
                }

                // The small files (images, subtitles) usually finish between two reports, and a file
                // that happened to end exactly on a report still has to be finalised the same way.
                if (lastReportedTotal != totalRead) {
                    progressCallback.onProgress(totalRead, contentRange.total)
                }
            }
        }
    }

    suspend fun downloadAndSave(
        api: ApiClient,
        from: Uri,
        to: ParcelFileDescriptor,
        progressCallback: ProgressCallback = ProgressCallback.Empty,
    ) {
        val rangeStart = to.statSize
        val response = download(api, from, rangeStart)
        save(response, to, progressCallback)
    }
}
