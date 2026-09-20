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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
        val initialProblem = remember {
            when {
                showNoNetworkConnection -> ConnectionProblem.NO_NETWORK
                showWeakConnection -> ConnectionProblem.WEAK_CONNECTION
                else -> null
            }
        }
        // Dismissed when the user decides to pick another server, so the screen falls back to the
        // regular server selection without leaving the fragment.
        var problem by remember { mutableStateOf(initialProblem) }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(horizontal = 16.dp),
        ) {
            LogoHeader()
            val onRetry: () -> Unit = { coroutineScope.launch { mainViewModel.retryServerConnection() } }
            val onOpenDownloads: () -> Unit = { activityEventHandler.emit(ActivityEvent.OpenDownloads) }
            val onUseDifferentServer: () -> Unit = { problem = null }
            when (problem) {
                ConnectionProblem.NO_NETWORK -> ConnectionProblemScreen(
                    title = stringResource(R.string.no_network_connection_title),
                    message = stringResource(R.string.no_network_connection_message),
                    onRetry = onRetry,
                    onOpenDownloads = onOpenDownloads,
                    onUseDifferentServer = onUseDifferentServer,
                )
                ConnectionProblem.WEAK_CONNECTION -> ConnectionProblemScreen(
                    title = stringResource(R.string.weak_connection_title),
                    message = stringResource(R.string.weak_connection_message),
                    onRetry = onRetry,
                    onOpenDownloads = onOpenDownloads,
                    onUseDifferentServer = onUseDifferentServer,
                )
                null -> {
                    ServerSelection(
                        // The previous attempt failed, but that is already explained above. Only
                        // report it once the user is back on the regular selection screen.
                        showExternalConnectionError = showExternalConnectionError && initialProblem == null,
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
 * The screens that stand in for the webapp when it cannot be loaded. They all offer the same
 * actions as the loading screen: retry, the downloaded media, and another server.
 */
private enum class ConnectionProblem {
    NO_NETWORK,
    WEAK_CONNECTION,
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
    onUseDifferentServer: () -> Unit,
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
        StyledTextButton(
            text = stringResource(R.string.button_use_different_server),
            onClick = onUseDifferentServer,
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
