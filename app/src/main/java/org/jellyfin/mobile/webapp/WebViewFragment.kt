package org.jellyfin.mobile.webapp

import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.ValueCallback
import android.webkit.WebChromeClient.FileChooserParams
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.view.doOnNextLayout
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.withResumed
import androidx.webkit.WebViewAssetLoader.AssetsPathHandler
import androidx.webkit.WebViewCompat
import kotlinx.coroutines.launch
import org.jellyfin.mobile.MainViewModel
import org.jellyfin.mobile.R
import org.jellyfin.mobile.app.AppPreferences
import org.jellyfin.mobile.bridge.ExternalPlayer
import org.jellyfin.mobile.bridge.MediaSegments
import org.jellyfin.mobile.bridge.NativeInterface
import org.jellyfin.mobile.bridge.NativePlayer
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.databinding.FragmentWebviewBinding
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.setup.ConnectFragment
import org.jellyfin.mobile.update.UpdateDialogFragment
import org.jellyfin.mobile.update.UpdateManager
import org.jellyfin.mobile.utils.AndroidVersion
import org.jellyfin.mobile.utils.BackPressInterceptor
import org.jellyfin.mobile.utils.Constants
import org.jellyfin.mobile.utils.Constants.FRAGMENT_WEB_VIEW_EXTRA_SERVER
import org.jellyfin.mobile.utils.applyDefault
import org.jellyfin.mobile.utils.applyWindowInsetsAsMargins
import org.jellyfin.mobile.utils.dip
import org.jellyfin.mobile.utils.extensions.getParcelableCompat
import org.jellyfin.mobile.utils.extensions.replaceFragment
import org.jellyfin.mobile.utils.fadeIn
import org.jellyfin.mobile.utils.isNetworkAvailable
import org.jellyfin.mobile.utils.isOutdated
import org.jellyfin.mobile.utils.requestNoBatteryOptimizations
import org.jellyfin.mobile.utils.runOnUiThread
import org.koin.android.ext.android.inject
import org.koin.androidx.viewmodel.ext.android.activityViewModel
import timber.log.Timber

class WebViewFragment : Fragment(), BackPressInterceptor, JellyfinWebChromeClient.FileChooserListener {
    val appPreferences: AppPreferences by inject()
    private val mainViewModel: MainViewModel by activityViewModel()
    private val webappFunctionChannel: WebappFunctionChannel by inject()
    private lateinit var assetsPathHandler: AssetsPathHandler
    private lateinit var jellyfinWebViewClient: JellyfinWebViewClient
    private val nativePlayer: NativePlayer by inject()
    private lateinit var externalPlayer: ExternalPlayer
    private val mediaSegments: MediaSegments by inject()
    private val activityEventHandler: ActivityEventHandler by inject()
    private val updateManager: UpdateManager by inject()

    lateinit var server: ServerEntity
        private set
    private var connected = false
    private val timeoutRunnable = Runnable {
        // The webapp did not finish loading in time. This usually means an unreachable server or a
        // very slow connection, so offer a retry instead of waiting indefinitely.
        Timber.w("Webapp did not load within %d ms, showing the slow connection screen", Constants.INITIAL_CONNECTION_TIMEOUT)
        handleError(weakConnection = true)
    }
    private val showLoadingContainerRunnable = Runnable {
        webViewBinding?.loadingContainer?.isVisible = true
    }

    // UI
    private var webViewBinding: FragmentWebviewBinding? = null

    // External file access
    private var fileChooserActivityLauncher: ActivityResultLauncher<Intent> = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        fileChooserCallback?.onReceiveValue(FileChooserParams.parseResult(result.resultCode, result.data))
    }
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        server = requireNotNull(requireArguments().getParcelableCompat(FRAGMENT_WEB_VIEW_EXTRA_SERVER)) {
            "Server entity has not been supplied!"
        }

        assetsPathHandler = AssetsPathHandler(requireContext())
        jellyfinWebViewClient = object : JellyfinWebViewClient(
            lifecycleScope,
            server,
            assetsPathHandler,
            mainViewModel,
        ) {
            override fun onConnectedToWebapp() {
                val webViewBinding = webViewBinding ?: return
                val webView = webViewBinding.webView
                webView.removeCallbacks(timeoutRunnable)
                webView.removeCallbacks(showLoadingContainerRunnable)
                connected = true
                runOnUiThread {
                    webViewBinding.loadingContainer.isVisible = false
                    webView.fadeIn()
                }
                requestNoBatteryOptimizations(webViewBinding.root)
                promptForUpdateIfAvailable()
            }

            override fun onErrorReceived() {
                handleError()
            }
        }
        externalPlayer = ExternalPlayer(requireContext(), this, requireActivity().activityResultRegistry)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return FragmentWebviewBinding.inflate(inflater, container, false).also { binding ->
            webViewBinding = binding
        }.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val webView = webViewBinding!!.webView

        // Apply window insets
        webView.applyWindowInsetsAsMargins()

        // Setup exclusion rects for gestures
        if (AndroidVersion.isAtLeastQ) {
            @Suppress("MagicNumber")
            webView.doOnNextLayout {
                // Maximum allowed exclusion rect height is 200dp,
                // offsetting 100dp from the center in both directions
                // uses the maximum available space
                val verticalCenter = webView.measuredHeight / 2
                val offset = webView.resources.dip(100)

                // Arbitrary, currently 2x minimum touch target size
                val exclusionWidth = webView.resources.dip(96)

                webView.systemGestureExclusionRects = listOf(
                    Rect(
                        0,
                        verticalCenter - offset,
                        exclusionWidth,
                        verticalCenter + offset,
                    ),
                )
            }
        }

        // Setup WebView
        webView.initialize()

        // The loading screen offers the same actions as the connection problem screens that follow
        // it, so a slow connection does not look like a different state to the user.
        webViewBinding!!.retryConnectionButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.removeCallbacks(showLoadingContainerRunnable)
            // Keep the loader up while the retry is in flight, otherwise the screen would briefly
            // show the previously failed page.
            webViewBinding!!.loadingContainer.isVisible = true
            connected = false
            webView.loadUrl("${server.hostname.trimEnd('/')}/")
            webView.postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
        }

        webViewBinding!!.useDifferentServerButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.removeCallbacks(showLoadingContainerRunnable)
            webView.stopLoading()
            webViewBinding!!.loadingContainer.isVisible = false
            onSelectServer(error = false)
        }

        // Always offer a way out of the loading screen, so a slow or missing connection never
        // traps the user away from the downloaded media.
        webViewBinding!!.viewDownloadsButton.setOnClickListener {
            webView.removeCallbacks(timeoutRunnable)
            webView.removeCallbacks(showLoadingContainerRunnable)
            webView.stopLoading()
            webViewBinding!!.loadingContainer.isVisible = false
            activityEventHandler.emit(ActivityEvent.OpenDownloads)
        }

        // Process JS functions called from other components (e.g. the PlayerActivity)
        lifecycleScope.launch {
            for (function in webappFunctionChannel) {
                // Never let a single failed evaluation kill the collector, otherwise all further
                // calls (for example download state updates) would be lost.
                try {
                    webView.evaluateJavascript(function, null)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to evaluate webapp function")
                }
            }
        }
    }

    override fun onInterceptBackPressed(): Boolean {
        return connected && webappFunctionChannel.goBack()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        webViewBinding = null
    }

    private fun WebView.initialize() {
        if (!appPreferences.ignoreWebViewChecks && isOutdated()) { // Check WebView version
            showOutdatedWebViewDialog(this)
            return
        }

        // Without a network there is no point in waiting for the webapp to load, so fail fast and
        // let the user retry or open the downloaded media instead.
        if (!requireContext().isNetworkAvailable()) {
            Timber.w("No network available, skipping the webapp load")
            handleError(noNetwork = true)
            return
        }

        webViewClient = jellyfinWebViewClient
        webChromeClient = JellyfinWebChromeClient(this@WebViewFragment)
        settings.applyDefault()
        addJavascriptInterface(NativeInterface(requireContext()), "NativeInterface")
        addJavascriptInterface(nativePlayer, "NativePlayer")
        addJavascriptInterface(externalPlayer, "ExternalPlayer")
        addJavascriptInterface(mediaSegments, "MediaSegments")

        loadUrl("${server.hostname.trimEnd('/')}/")
        postDelayed(timeoutRunnable, Constants.INITIAL_CONNECTION_TIMEOUT)
        postDelayed(showLoadingContainerRunnable, Constants.SHOW_PROGRESS_BAR_DELAY)
    }

    private fun showOutdatedWebViewDialog(webView: WebView) {
        AlertDialog.Builder(requireContext()).apply {
            setTitle(R.string.dialog_web_view_outdated)
            setMessage(R.string.dialog_web_view_outdated_message)
            setCancelable(false)

            val webViewPackage = WebViewCompat.getCurrentWebViewPackage(context)
            if (webViewPackage != null) {
                val marketUri = Uri.Builder().apply {
                    scheme("market")
                    authority("details")
                    appendQueryParameter("id", webViewPackage.packageName)
                }.build()
                val referrerUri = Uri.Builder().apply {
                    scheme("android-app")
                    authority(context.packageName)
                }.build()

                val marketIntent = Intent(Intent.ACTION_VIEW).apply {
                    data = marketUri
                    putExtra(Intent.EXTRA_REFERRER, referrerUri)
                }

                // Only show button if the intent can be resolved
                if (marketIntent.resolveActivity(context.packageManager) != null) {
                    setNegativeButton(R.string.dialog_button_check_for_updates) { _, _ ->
                        startActivity(marketIntent)
                        requireActivity().finishAfterTransition()
                    }
                }
            }
            if (AndroidVersion.isAtLeastN) {
                setPositiveButton(R.string.dialog_button_open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_WEBVIEW_SETTINGS))
                    Toast.makeText(context, R.string.toast_reopen_after_change, Toast.LENGTH_LONG).show()
                    requireActivity().finishAfterTransition()
                }
            }
            setNeutralButton(R.string.dialog_button_ignore) { _, _ ->
                appPreferences.ignoreWebViewChecks = true
                // Re-initialize
                webView.initialize()
            }
        }.show()
    }

    private fun onSelectServer(
        error: Boolean = false,
        noNetwork: Boolean = false,
        weakConnection: Boolean = false,
    ) {
        // The fragment may be created before the activity is resumed (for example when the missing
        // network is detected immediately), so wait for the resumed state before replacing it.
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.withResumed {
                val extras = when {
                    error -> Bundle().apply {
                        putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_ERROR, true)
                        putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_NO_NETWORK, noNetwork)
                        putBoolean(Constants.FRAGMENT_CONNECT_EXTRA_WEAK_CONNECTION, weakConnection)
                    }
                    else -> null
                }
                parentFragmentManager.replaceFragment<ConnectFragment>(extras)
            }
        }
    }

    private fun handleError(noNetwork: Boolean = false, weakConnection: Boolean = false) {
        connected = false
        onSelectServer(error = true, noNetwork = noNetwork, weakConnection = weakConnection)
    }

    /**
     * Offers a newer version once the library screen is visible, which is what the user asked for:
     * the prompt follows the app start instead of interrupting the loading screen.
     */
    private fun promptForUpdateIfAvailable() {
        lifecycleScope.launch {
            updateManager.check()
            if (!updateManager.shouldPrompt()) return@launch
            runOnUiThread {
                activity?.supportFragmentManager?.let { UpdateDialogFragment.show(it) }
            }
        }
    }

    override fun onShowFileChooser(intent: Intent, filePathCallback: ValueCallback<Array<Uri>>) {
        fileChooserCallback = filePathCallback
        fileChooserActivityLauncher.launch(intent)
    }
}
