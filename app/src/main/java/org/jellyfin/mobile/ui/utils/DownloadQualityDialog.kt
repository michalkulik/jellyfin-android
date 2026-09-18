package org.jellyfin.mobile.ui.utils

import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.MaterialTheme
import androidx.compose.material.RadioButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadQuality
import kotlin.coroutines.resume

/**
 * Label of a quality. The bitrate/resolution part is language neutral, but "Original" needs to be
 * translated, so every label is built from string resources.
 */
@Composable
private fun DownloadQuality.label(): String = when (maxBitrate) {
    null -> stringResource(R.string.download_quality_original)
    15_000_000 -> stringResource(R.string.download_quality_15_mbps)
    12_000_000 -> stringResource(R.string.download_quality_12_mbps)
    8_000_000 -> stringResource(R.string.download_quality_8_mbps)
    4_000_000 -> stringResource(R.string.download_quality_4_mbps)
    1_500_000 -> stringResource(R.string.download_quality_1_5_mbps)
    500_000 -> stringResource(R.string.download_quality_0_5_mbps)
    250_000 -> stringResource(R.string.download_quality_0_25_mbps)
    else -> stringResource(R.string.download_quality_custom_mbps, maxBitrate / 1_000_000)
}

/**
 * Formats the estimated size shown next to a quality.
 */
@Composable
private fun estimatedSizeLabel(size: Long?): String? = size
    ?.takeIf { it > 0 }
    ?.let { stringResource(R.string.download_quality_estimated_size, Formatter.formatShortFileSize(LocalContext.current, it)) }

@Composable
fun DownloadQualityDialogContent(
    sizeEstimates: Map<DownloadQuality, Long?> = emptyMap(),
    itemCount: Int = 1,
    onQualitySelected: (DownloadQuality) -> Unit,
) {
    val options = DownloadQuality.PRESETS
    var selectedIndex by remember { mutableIntStateOf(0) }

    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.download_quality_dialog_title),
                style = MaterialTheme.typography.h6,
            )

            Text(
                text = if (itemCount > 1) {
                    stringResource(R.string.download_quality_dialog_message_multi, itemCount)
                } else {
                    stringResource(R.string.download_quality_dialog_message)
                },
                style = MaterialTheme.typography.body2,
                modifier = Modifier.padding(bottom = 8.dp),
            )

            options.forEachIndexed { index, quality ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .selectable(
                            selected = selectedIndex == index,
                            onClick = { selectedIndex = index },
                        )
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                    )
                    Column {
                        Text(text = quality.label())
                        estimatedSizeLabel(sizeEstimates[quality])?.let { sizeLabel ->
                            Text(
                                text = sizeLabel,
                                style = MaterialTheme.typography.caption,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                            )
                        }
                    }
                }
            }

            Button(
                onClick = { onQualitySelected(options[selectedIndex]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            ) {
                Text(text = stringResource(R.string.download_quality_confirm))
            }
        }
    }
}

suspend fun ComponentActivity.showDownloadQualityDialog(
    sizeEstimates: Map<DownloadQuality, Long?> = emptyMap(),
    itemCount: Int = 1,
): DownloadQuality? =
    suspendCancellableCoroutine { continuation ->
        ComponentDialog(this).apply {
            setContentView(
                ComposeView(this@showDownloadQualityDialog).apply {
                    setContent {
                        AppTheme {
                            DownloadQualityDialogContent(
                                sizeEstimates = sizeEstimates,
                                itemCount = itemCount,
                                onQualitySelected = { quality ->
                                    if (continuation.isActive) continuation.resume(quality)
                                    dismiss()
                                },
                            )
                        }
                    }
                },
            )

            setOnCancelListener {
                if (continuation.isActive) continuation.resume(null)
            }
            setOnDismissListener {
                if (continuation.isActive) continuation.resume(null)
            }
            show()
            continuation.invokeOnCancellation { dismiss() }
        }
    }
