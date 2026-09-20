package org.jellyfin.mobile

import android.app.Application
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jellyfin.mobile.app.apiModule
import org.jellyfin.mobile.app.applicationModule
import org.jellyfin.mobile.data.databaseModule
import org.jellyfin.mobile.downloads.DownloadManager
import org.jellyfin.mobile.update.UpdateManager
import org.jellyfin.mobile.utils.JellyTree
import org.jellyfin.mobile.utils.isWebViewSupported
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.fragment.koin.fragmentFactory
import org.koin.core.context.startKoin
import org.koin.core.context.GlobalContext
import timber.log.Timber

@Suppress("unused")
class JellyfinApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // Setup logging
        Timber.plant(JellyTree())

        if (BuildConfig.DEBUG) {
            // Enable WebView debugging
            if (isWebViewSupported()) {
                WebView.setWebContentsDebuggingEnabled(true)
            }
        }

        startKoin {
            androidContext(this@JellyfinApplication)
            fragmentFactory()

            modules(
                applicationModule,
                apiModule,
                databaseModule,
            )
        }

        // Resume downloads that were interrupted when the app was closed.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { GlobalContext.get().get<DownloadManager>().resumeActiveDownloads() }
                .onFailure { Timber.w(it, "Unable to resume downloads") }
        }

        // Check for a newer version in the background. The result decides whether the update prompt
        // is shown once the library screen is ready and whether the web user interface shows the
        // update entry in the profile menu and the dashboard button.
        val updateManager = GlobalContext.get().get<UpdateManager>()
        updateManager.cleanupDownloadedPackages()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { updateManager.check(force = true) }
                .onFailure { Timber.w(it, "Unable to check for app updates") }
        }
    }
}
