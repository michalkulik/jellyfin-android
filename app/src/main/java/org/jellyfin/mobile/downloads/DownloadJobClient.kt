package org.jellyfin.mobile.downloads

import android.net.Uri
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.IOException
import java.util.UUID

/**
 * Client for the server side "convert then download" job API.
 *
 * The server may serialize responses in camelCase or PascalCase depending on the negotiated
 * media type, so responses are parsed case-insensitively.
 */
class DownloadJobClient(
    private val okHttpClient: OkHttpClient,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun createJob(api: ApiClient, itemId: UUID, request: CreateDownloadRequestDto): DownloadJobDto =
        withContext(Dispatchers.IO) {
            val payload = json.encodeToString(CreateDownloadRequestDto.serializer(), request)
            val httpRequest = buildRequest(api, "Items/$itemId/Download")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            parseJob(execute(api, httpRequest))
        }

    suspend fun getJob(api: ApiClient, itemId: UUID, jobId: String): DownloadJobDto =
        withContext(Dispatchers.IO) {
            val httpRequest = buildRequest(api, "Items/$itemId/Download/$jobId").get().build()
            parseJob(execute(api, httpRequest))
        }

    suspend fun cancelJob(api: ApiClient, itemId: UUID, jobId: String) = withContext(Dispatchers.IO) {
        val httpRequest = buildRequest(api, "Items/$itemId/Download/$jobId").delete().build()
        execute(api, httpRequest)
        Unit
    }

    fun getFileUrl(api: ApiClient, itemId: UUID, jobId: String): Uri =
        "${api.baseUrl}Items/$itemId/Download/$jobId/File".toUri()

    fun getDirectDownloadUrl(api: ApiClient, itemId: UUID): Uri =
        "${api.baseUrl}Items/$itemId/Download".toUri()

    fun getSubtitleUrl(api: ApiClient, itemId: UUID, mediaSourceId: String, index: Int, format: String): Uri =
        "${api.baseUrl}Videos/$itemId/$mediaSourceId/Subtitles/$index/Stream.$format".toUri()

    private fun parseJob(body: String): DownloadJobDto {
        val obj = json.decodeFromString(JsonObject.serializer(), body)

        return DownloadJobDto(
            id = obj.string("id") ?: error("Download job response is missing an id"),
            status = obj.string("status") ?: "Queued",
            progress = obj.double("progress"),
            fileName = obj.string("fileName"),
            size = obj.long("size"),
            error = obj.string("error"),
        )
    }

    private fun JsonObject.value(key: String) = entries
        .firstOrNull { (name, _) -> name.equals(key, ignoreCase = true) }
        ?.value

    private fun JsonObject.string(key: String): String? = value(key)?.jsonPrimitive?.contentOrNull

    private fun JsonObject.double(key: String): Double? = value(key)?.jsonPrimitive?.doubleOrNull

    private fun JsonObject.long(key: String): Long? = value(key)?.jsonPrimitive?.longOrNull

    private fun buildRequest(api: ApiClient, path: String): Request.Builder {
        val authorizationHeader = AuthorizationHeaderBuilder.buildHeader(
            clientName = api.clientInfo.name,
            clientVersion = api.clientInfo.version,
            deviceId = api.deviceInfo.id,
            deviceName = api.deviceInfo.name,
            accessToken = api.accessToken,
        )

        return Request.Builder()
            .url("${api.baseUrl}$path")
            .header("Authorization", authorizationHeader)
            .header("Accept", CAMEL_CASE_JSON)
    }

    private fun execute(api: ApiClient, request: Request): String {
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Unexpected response ${response.code} for ${request.url}")
            }

            return response.body?.string() ?: error("Response does not contain a body")
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json".toMediaType()
        private const val CAMEL_CASE_JSON = "application/json; profile=\"CamelCase\""
    }
}

/**
 * Server side download job state.
 */
data class DownloadJobDto(
    val id: String,
    val status: String = "Queued",
    val progress: Double? = null,
    val fileName: String? = null,
    val size: Long? = null,
    val error: String? = null,
)
