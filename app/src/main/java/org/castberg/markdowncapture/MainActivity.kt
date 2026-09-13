package org.castberg.markdowncapture

import android.os.Bundle
import android.os.PowerManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import org.castberg.markdowncapture.ui.*
import org.castberg.markdowncapture.ui.theme.MarkdownCaptureTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onStart() {
        super.onStart()
        viewModel.startInactivityTimer()
    }

    override fun onStop() {
        super.onStop()
        viewModel.cancelInactivityTimer()
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        // Auto-close on screen-off, but not while a capture is still uploading/saving:
        // finishing clears the ViewModel and cancels the job, silently losing the photo.
        if (!pm.isInteractive && viewModel.backgroundJobCount == 0) finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MarkdownCaptureTheme {
                val settings by viewModel.settings.collectAsState()
                val history  by viewModel.history.collectAsState()
                val context  = LocalContext.current

                LaunchedEffect(Unit) {
                    viewModel.finishEvent.collect { finish() }
                }

                LaunchedEffect(viewModel.saveMessage) {
                    viewModel.saveMessage?.let { msg ->
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        viewModel.clearSaveMessage()
                    }
                }

                when (val screen = viewModel.screen) {
                    is MainViewModel.Screen.Camera -> CameraScreen(
                        outputFolderUri      = settings.outputFolderUri,
                        imageQuality         = settings.imageQuality,
                        history              = history,
                        tabNames             = settings.tabs.map { it.name },
                        activeTabIndex       = viewModel.activeTabIndex,
                        isMultiMode          = viewModel.isMultiMode,
                        pendingImageCount    = viewModel.pendingImageCount,
                        backgroundJobCount   = viewModel.backgroundJobCount,
                        onImageCaptured      = viewModel::onImageCaptured,
                        onNavigateToSettings = viewModel::navigateToSettings,
                        onViewCapture        = viewModel::viewCapture,
                        onSetActiveTab       = viewModel::onTabSelected,
                        onToggleMultiMode    = viewModel::toggleMultiMode,
                        onSendMultiImages    = viewModel::onSendMultiImages
                    )
                    is MainViewModel.Screen.Settings -> SettingsScreen(
                        settings       = settings,
                        onSave         = viewModel::saveSettings,
                        onNavigateBack = viewModel::navigateToCamera
                    )
                    is MainViewModel.Screen.Processing -> ProcessingScreen(step = screen.step)
                    is MainViewModel.Screen.ViewCapture -> CaptureDetailScreen(
                        record     = screen.record,
                        tabNames   = settings.tabs.map { it.name },
                        onResubmit = { tabIndex -> viewModel.resubmit(screen.record, tabIndex) },
                        onClose    = viewModel::navigateToCamera
                    )
                    is MainViewModel.Screen.Error -> ErrorScreen(
                        message  = screen.message,
                        canRetry = screen.imageBytes != null,
                        onRetry  = viewModel::retry,
                        onRetake = viewModel::retake
                    )
                }
            }
        }
    }
}
