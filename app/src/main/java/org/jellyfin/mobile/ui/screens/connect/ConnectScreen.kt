package org.jellyfin.mobile.ui.screens.connect

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material.ButtonDefaults
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.ui.utils.CenterRow
import org.koin.compose.koinInject

@Composable
fun ConnectScreen(
    mainViewModel: MainViewModel,
    showExternalConnectionError: Boolean,
    showNoNetworkConnection: Boolean = false,
    showWeakConnection: Boolean = false,
    activityEventHandler: ActivityEventHandler = koinInject(),
) {
    Surface(color = MaterialTheme.colors.background) {
        val coroutineScope = rememberCoroutineScope()
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            LogoHeader()
            val onRetry: () -> Unit = { coroutineScope.launch { mainViewModel.retryServerConnection() } }
            val onOpenDownloads: () -> Unit = { activityEventHandler.emit(ActivityEvent.OpenDownloads) }
            when {
                showNoNetworkConnection -> ConnectionProblemScreen(
                    title = stringResource(R.string.no_network_connection_title),
                    message = stringResource(R.string.no_network_connection_message),
                    onRetry = onRetry,
                    onOpenDownloads = onOpenDownloads,
                )
                showWeakConnection -> ConnectionProblemScreen(
                    title = stringResource(R.string.weak_connection_title),
                    message = stringResource(R.string.weak_connection_message),
                    onRetry = onRetry,
                    onOpenDownloads = onOpenDownloads,
                )
                else -> {
                    ServerSelection(
                        showExternalConnectionError = showExternalConnectionError,
                        onConnected = { hostname ->
                            mainViewModel.switchServer(hostname)
                        },
                    )
                    StyledTextButton(
                        onClick = onOpenDownloads,
                        text = stringResource(R.string.view_downloads),
                    )
                }
            }
        }
    }
}

/**
 * Shown when the connection to the server cannot be used, either because there is no network at all
 * or because the webapp could not be loaded in time. Downloaded media stays reachable from here.
 */
@Stable
@Composable
fun ConnectionProblemScreen(
    title: String,
    message: String,
    onRetry: () -> Unit,
    onOpenDownloads: () -> Unit,
) {
    Column {
        Text(
            text = title,
            modifier = Modifier.padding(bottom = 8.dp),
            style = MaterialTheme.typography.h5,
        )
        Text(
            text = message,
            modifier = Modifier.padding(bottom = 16.dp),
            style = MaterialTheme.typography.body1,
        )
        StyledTextButton(
            text = stringResource(R.string.retry_connection),
            onClick = onRetry,
        )
        StyledTextButton(
            text = stringResource(R.string.view_downloads),
            onClick = onOpenDownloads,
        )
    }
}

@Stable
@Composable
fun LogoHeader() {
    CenterRow(
        modifier = Modifier.padding(vertical = 25.dp),
    ) {
        Image(
            painter = painterResource(R.drawable.ic_launcher_foreground),
            modifier = Modifier
                .height(96.dp),
            contentDescription = null,
        )
    }
}

@Stable
@Composable
fun StyledTextButton(
    text: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(),
    ) {
        Text(text = text)
    }
}
