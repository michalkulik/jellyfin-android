package org.jellyfin.mobile.update

import java.io.File

/**
 * State of the in-app updater. The web based user interface and the dialog both observe this, so
 * every change is visible in both places at once.
 */
sealed interface UpdateState {
    /** No check has run yet. */
    data object Unknown : UpdateState

    /** A check is in flight. */
    data object Checking : UpdateState

    /** The installed version is current, or the last check could not reach the manifest. */
    data object UpToDate : UpdateState

    /** A newer version exists and can be installed. */
    data class Available(val release: UpdateRelease) : UpdateState

    /** The new version is being downloaded, [progress] is 0-100. */
    data class Downloading(val release: UpdateRelease, val progress: Int) : UpdateState

    /** The download finished and was verified. */
    data class Downloaded(val release: UpdateRelease, val file: File) : UpdateState

    /** The download or the verification failed. */
    data class Failed(val release: UpdateRelease?, val reason: String?) : UpdateState

    val releaseOrNull: UpdateRelease?
        get() = when (this) {
            is Available -> release
            is Downloading -> release
            is Downloaded -> release
            is Failed -> release
            else -> null
        }

    /**
     * The release a download request should use: the one that is on offer, or the one a failed
     * download was working on, so retrying does not need another manifest request.
     *
     * A download that is already running or finished is deliberately excluded, asking for it again
     * would restart or repeat work that is already done.
     */
    val downloadableRelease: UpdateRelease?
        get() = when (this) {
            is Available -> release
            is Failed -> release
            else -> null
        }
}
