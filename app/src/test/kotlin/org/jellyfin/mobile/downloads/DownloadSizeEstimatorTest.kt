package org.jellyfin.mobile.downloads

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class DownloadSizeEstimatorTest {
    private companion object {
        private const val TICKS_PER_SECOND = 10_000_000L
        private const val TICKS_PER_MINUTE = TICKS_PER_SECOND * 60

        private val ORIGINAL = DownloadQuality.Original
        private val TEN_MBPS = DownloadQuality(10_000_000, 1080)
        private val FOUR_MBPS = DownloadQuality(4_000_000, 720)
    }

    @Test
    fun `original quality uses the exact size reported by the server`() {
        val input = input(runtimeTicks = TICKS_PER_MINUTE * 90, originalSizeBytes = 24_583_430)

        DownloadSizeEstimator.estimate(input, ORIGINAL) shouldBe 24_583_430
    }

    @Test
    fun `original quality without a known size is not estimated`() {
        val input = input(runtimeTicks = TICKS_PER_MINUTE * 90, originalSizeBytes = null)

        DownloadSizeEstimator.estimate(input, ORIGINAL).shouldBeNull()
    }

    @Test
    fun `converted quality is estimated from the bitrate and runtime`() {
        val input = input(
            runtimeTicks = TICKS_PER_MINUTE * 60,
            originalSizeBytes = null,
            videoBitrate = 8_000_000,
            audioBitrate = 320_000,
        )

        // 8 Mbps video (below the 10 Mbps target) + 320 kbps audio for 60 minutes
        DownloadSizeEstimator.estimate(input, TEN_MBPS) shouldBe 3_744_000_000
    }

    @Test
    fun `requested bitrate caps the estimate of a high bitrate source`() {
        val input = input(
            runtimeTicks = TICKS_PER_MINUTE * 60,
            originalSizeBytes = null,
            videoBitrate = 20_000_000,
            audioBitrate = 192_000,
        )

        // The 20 Mbps source is capped at the requested 4 Mbps
        DownloadSizeEstimator.estimate(input, FOUR_MBPS) shouldBe 1_886_400_000
    }

    @Test
    fun `unknown stream bitrates fall back to defaults`() {
        val input = input(
            runtimeTicks = TICKS_PER_MINUTE * 60,
            originalSizeBytes = null,
            videoBitrate = null,
            audioBitrate = null,
        )

        // 2 Mbps default video + 192 kbps default audio for 60 minutes
        DownloadSizeEstimator.estimate(input, TEN_MBPS) shouldBe 986_400_000
    }

    @Test
    fun `audio bitrate is capped to avoid overestimating high channel audio`() {
        val input = input(
            runtimeTicks = TICKS_PER_MINUTE * 60,
            originalSizeBytes = null,
            videoBitrate = 8_000_000,
            audioBitrate = 4_000_000,
        )

        // The audio is capped at 640 kbps, so 8 Mbps + 640 kbps for 60 minutes
        DownloadSizeEstimator.estimate(input, TEN_MBPS) shouldBe 3_888_000_000
    }

    @Test
    fun `converted quality without runtime is not estimated`() {
        val input = input(runtimeTicks = null, originalSizeBytes = 5_000_000, videoBitrate = 8_000_000)

        DownloadSizeEstimator.estimate(input, TEN_MBPS).shouldBeNull()
    }

    @Test
    fun `total sums the estimate of every item`() {
        val inputs = listOf(
            input(
                runtimeTicks = TICKS_PER_MINUTE * 30,
                originalSizeBytes = 1_000_000,
                videoBitrate = 3_000_000,
                audioBitrate = 128_000,
            ),
            input(
                runtimeTicks = TICKS_PER_MINUTE * 30,
                originalSizeBytes = 2_000_000,
                videoBitrate = 3_000_000,
                audioBitrate = 128_000,
            ),
        )

        DownloadSizeEstimator.estimateTotal(inputs, ORIGINAL) shouldBe 3_000_000
        DownloadSizeEstimator.estimateTotal(inputs, FOUR_MBPS) shouldBe 1_407_600_000
    }

    @Test
    fun `total is unknown when a single item cannot be estimated`() {
        val inputs = listOf(
            input(runtimeTicks = TICKS_PER_MINUTE * 30, originalSizeBytes = 1_000_000, videoBitrate = 3_000_000),
            input(runtimeTicks = null, originalSizeBytes = null, videoBitrate = null),
        )

        DownloadSizeEstimator.estimateTotal(inputs, ORIGINAL).shouldBeNull()
    }

    @Test
    fun `empty list has no estimate`() {
        DownloadSizeEstimator.estimateTotal(emptyList(), ORIGINAL).shouldBeNull()
    }

    private fun input(
        runtimeTicks: Long?,
        originalSizeBytes: Long?,
        videoBitrate: Int? = null,
        audioBitrate: Int? = null,
    ) = DownloadSizeInput(
        runtimeTicks = runtimeTicks,
        originalSizeBytes = originalSizeBytes,
        videoBitrate = videoBitrate,
        audioBitrate = audioBitrate,
    )
}

