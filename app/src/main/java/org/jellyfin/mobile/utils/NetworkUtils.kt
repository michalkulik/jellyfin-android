package org.jellyfin.mobile.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.getSystemService

/**
 * Whether the device currently has a network that is able to reach the internet.
 *
 * Returns true when the connectivity state cannot be determined, so a missing permission or an
 * unexpected platform state never blocks the app from trying to connect.
 */
fun Context.isNetworkAvailable(): Boolean {
    val connectivityManager = getSystemService<ConnectivityManager>() ?: return true
    val network = connectivityManager.activeNetwork ?: return false
    val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}
