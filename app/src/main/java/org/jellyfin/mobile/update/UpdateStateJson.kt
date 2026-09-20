package org.jellyfin.mobile.update

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Serializes the update state for the web based user interface, which uses it to show the update
 * entry in the profile menu and the button in the dashboard.
 */
fun UpdateState.toJsonString(): String {
    val release = releaseOrNull
    val progress = (this as? UpdateState.Downloading)?.progress ?: 0
    val error = (this as? UpdateState.Failed)?.reason.orEmpty()

    return buildJsonObject {
        put("state", name)
        put("version", release?.version.orEmpty())
        put("versionCode", release?.versionCode ?: 0)
        put("notes", release?.notes.orEmpty())
        put("progress", progress)
        put("error", error)
    }.toString()
}

/**
 * Lowercase state name, matching the `state` field above.
 */
private val UpdateState.name: String
    get() = when (this) {
        is UpdateState.Unknown -> "unknown"
        is UpdateState.Checking -> "checking"
        is UpdateState.UpToDate -> "uptodate"
        is UpdateState.Available -> "available"
        is UpdateState.Downloading -> "downloading"
        is UpdateState.Downloaded -> "downloaded"
        is UpdateState.Failed -> "failed"
    }
