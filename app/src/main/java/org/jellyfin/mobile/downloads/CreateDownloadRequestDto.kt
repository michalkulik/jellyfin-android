package org.jellyfin.mobile.downloads

import kotlinx.serialization.Serializable

/**
 * Request body for creating a server side download job.
 */
@Serializable
data class CreateDownloadRequestDto(
    val original: Boolean = false,
    val maxBitrate: Int? = null,
    val maxHeight: Int? = null,
    val container: String? = null,
    val mediaSourceId: String? = null,
    val deviceId: String? = null,
)
