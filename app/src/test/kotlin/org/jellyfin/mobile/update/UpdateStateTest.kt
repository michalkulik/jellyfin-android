package org.jellyfin.mobile.update

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

class UpdateStateTest {
    private companion object {
        private val release = UpdateRelease(
            version = "0.3.15",
            versionCode = 31599,
            notes = null,
            variant = "libre",
            url = "https://example.invalid/jellyfin-android-v0.3.15-libre-release.apk",
            size = 1024L,
            sha256 = null,
        )
    }

    @Test
    fun `an offered release can be downloaded`() {
        UpdateState.Available(release).downloadableRelease shouldBe release
    }

    @Test
    fun `a failed download can be retried with the same release`() {
        // This is what the "Try again" button in the update dialog relies on.
        UpdateState.Failed(release, "timeout").downloadableRelease shouldBe release
    }

    @Test
    fun `a failure without a known release has nothing to retry`() {
        UpdateState.Failed(null, "timeout").downloadableRelease.shouldBeNull()
    }

    @Test
    fun `a running download is not restarted`() {
        UpdateState.Downloading(release, 42).downloadableRelease.shouldBeNull()
    }

    @Test
    fun `a finished download is not repeated`() {
        UpdateState.Downloaded(release, File("update.apk")).downloadableRelease.shouldBeNull()
    }

    @Test
    fun `states without a release have nothing to download`() {
        UpdateState.Unknown.downloadableRelease.shouldBeNull()
        UpdateState.Checking.downloadableRelease.shouldBeNull()
        UpdateState.UpToDate.downloadableRelease.shouldBeNull()
    }
}
