package org.jellyfin.mobile.ui.utils

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadQuality
import kotlin.coroutines.resume

private fun DownloadQuality.label(): String = when (maxBitrate) {
    null -> "Original"
    15_000_000 -> "15 Mbps (1080p)"
    12_000_000 -> "12 Mbps (1080p)"
    8_000_000 -> "8 Mbps (720p)"
    4_000_000 -> "4 Mbps (720p)"
    1_500_000 -> "1.5 Mbps (480p)"
    else -> "${maxBitrate / 1_000_000} Mbps"
}

@Composable
fun DownloadQualityDialogContent(
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
                text = stringResource(R.string.download_quality_dialog_message),
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
                    Text(text = quality.label())
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

suspend fun ComponentActivity.showDownloadQualityDialog(): DownloadQuality? =
    suspendCancellableCoroutine { continuation ->
        ComponentDialog(this).apply {
            setContentView(
                ComposeView(this@showDownloadQualityDialog).apply {
                    setContent {
                        AppTheme {
                            DownloadQualityDialogContent(
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
