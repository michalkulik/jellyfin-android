package org.jellyfin.mobile.update

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import org.jellyfin.mobile.BuildConfig
import org.jellyfin.mobile.R
import org.jellyfin.mobile.databinding.DialogUpdateBinding
import org.koin.android.ext.android.inject

/**
 * The update prompt: shows the new version with its release notes and offers to install it, then
 * switches to the download progress and finally starts the installer.
 *
 * The dialog is hosted by the Activity so it can also be opened from the web based user interface
 * through [org.jellyfin.mobile.events.ActivityEvent.RequestUpdateDialog].
 */
class UpdateDialogFragment : DialogFragment() {
    companion object {
        const val TAG = "UpdateDialogFragment"

        /**
         * Shows the dialog when it is not already on screen.
         */
        fun show(fragmentManager: FragmentManager) {
            if (fragmentManager.isStateSaved) return
            if (fragmentManager.findFragmentByTag(TAG) != null) return
            UpdateDialogFragment().show(fragmentManager, TAG)
        }
    }

    private val updateManager: UpdateManager by inject()
    private lateinit var binding: DialogUpdateBinding
    private var dialog: AlertDialog? = null

    /**
     * Prevents the automatic install from being started more than once per dialog.
     */
    private var installStarted = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        binding = DialogUpdateBinding.inflate(LayoutInflater.from(requireContext()))

        return AlertDialog.Builder(requireContext())
            .setTitle(R.string.update_available_title)
            .setView(binding.root)
            .setPositiveButton(R.string.update_button_install, null)
            .setNegativeButton(R.string.update_button_later, null)
            .create()
            .also { dialog ->
                this.dialog = dialog
                dialog.setOnShowListener {
                    dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { onPositiveButton() }
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onNegativeButton() }
                    render(updateManager.state.value)
                }
            }
    }

    override fun onStart() {
        super.onStart()
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                updateManager.state.collect { state -> render(state) }
            }
        }
    }

    override fun onDestroyView() {
        dialog = null
        super.onDestroyView()
    }

    private fun onPositiveButton() {
        when (updateManager.state.value) {
            is UpdateState.Downloaded -> attemptInstall()
            else -> updateManager.download()
        }
    }

    private fun onNegativeButton() {
        when (updateManager.state.value) {
            is UpdateState.Downloading -> {
                lifecycleScope.launch { updateManager.cancelDownload() }
            }
            else -> {
                updateManager.snooze()
                dismiss()
            }
        }
    }

    private fun attemptInstall() {
        val started = updateManager.install(requireActivity())
        when {
            started -> dismissAllowingStateLoss()
            !UpdateInstaller.canRequestPackageInstalls(requireContext()) ->
                Toast.makeText(context, R.string.update_install_permission_message, Toast.LENGTH_LONG).show()
            BuildConfig.DEBUG ->
                Toast.makeText(context, R.string.update_debug_no_install, Toast.LENGTH_LONG).show()
        }
    }

    private fun render(state: UpdateState) {
        val dialog = dialog ?: return
        val positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
        val negative = dialog.getButton(AlertDialog.BUTTON_NEGATIVE)

        binding.updateNotesText.text = state.releaseOrNull?.notes.orEmpty()
        binding.updateNotesText.isVisible = !state.releaseOrNull?.notes.isNullOrBlank()

        binding.updateVersionText.text = when (state) {
            is UpdateState.Downloading, is UpdateState.Downloaded -> stringResourceVersion(state)
            is UpdateState.Failed -> getString(R.string.update_failed_message, state.reason.orEmpty())
            else -> stringResourceVersion(state)
        }

        val progressContainer = binding.updateProgressContainer
        progressContainer.isVisible = state is UpdateState.Downloading

        when (state) {
            is UpdateState.Downloading -> {
                binding.updateProgressBar.progress = state.progress
                binding.updateProgressText.text = getString(R.string.download_progress, state.progress)
                positive.isEnabled = false
                positive.setText(R.string.update_button_install)
                negative.isEnabled = true
                negative.setText(R.string.download_cancel)
                dialog.setCanceledOnTouchOutside(false)
            }
            is UpdateState.Downloaded -> {
                positive.isEnabled = true
                positive.setText(R.string.update_button_install)
                negative.isEnabled = true
                negative.setText(R.string.update_button_later)
                dialog.setCanceledOnTouchOutside(true)

                // The download finished, continue with the installation right away.
                if (!installStarted) {
                    installStarted = true
                    attemptInstall()
                }
            }
            is UpdateState.Failed -> {
                positive.isEnabled = true
                positive.setText(R.string.retry_connection)
                negative.isEnabled = true
                negative.setText(R.string.update_button_later)
                dialog.setCanceledOnTouchOutside(true)
            }
            else -> {
                positive.isEnabled = true
                positive.setText(R.string.update_button_install)
                negative.isEnabled = true
                negative.setText(R.string.update_button_later)
                dialog.setCanceledOnTouchOutside(true)
            }
        }
    }

    private fun stringResourceVersion(state: UpdateState): String {
        val release = state.releaseOrNull
        return if (release == null) {
            getString(R.string.update_available_message, "", "")
        } else {
            getString(R.string.update_available_message, release.version, BuildConfig.VERSION_NAME)
        }
    }
}
