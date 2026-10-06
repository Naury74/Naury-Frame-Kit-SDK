package com.naury.framekit.ui.video.editor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.layout.foldPostureFlow
import com.naury.framekit.ui.video.contract.VideoEditorContract
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import com.naury.framekit.video.export.VideoExportCoordinator
import com.naury.framekit.video.preview.Media3VideoPreviewEngine
import com.naury.framekit.video.source.VideoMetadataReader
import com.naury.framekit.video.thumbnail.VideoThumbnailLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hosts the video editor. Launch it through [VideoEditorContract]; it is not exported. */
internal class VideoEditorActivity : ComponentActivity() {

    private var delivered = false
    private var editor: VideoEditorViewModel? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, VideoEditorContract.EXTRA_REQUEST, VideoEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }

        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val viewModel = viewModels<VideoEditorViewModel> { factory(request) }.value
        editor = viewModel
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                deliver(viewModel.result.filterNotNull().first())
            }
        }
        val posture = foldPostureFlow(this)
        setContent {
            val currentPosture by posture.collectAsStateWithLifecycle(FoldPosture.Flat)
            ProvideEditorLocale(request.ui.localeTag) {
                FrameKitTheme(request.ui) {
                    VideoEditorScreen(viewModel, currentPosture)
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        editor?.onStop()
    }

    private fun factory(request: VideoEditorRequest) = viewModelFactory {
        initializer {
            val application = checkNotNull(this[APPLICATION_KEY])
            val registry = SessionSourceRegistry(application)
            val coordinator = VideoExportCoordinator(application, AppFileOutputStore(application))
            val loader = VideoThumbnailLoader(application)
            VideoEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = registry,
                readSource = VideoMetadataReader(application, registry)::read,
                exporter = { project, sources, locations, onProgress ->
                    coordinator.export(
                        project = project,
                        sources = sources,
                        locations = locations,
                        config = request.export,
                        target = request.output,
                        minClipOutputDurationUs = request.config.minClipDurationUs,
                        onProgress = onProgress,
                    )
                },
                previewEngineFactory = { scope -> Media3VideoPreviewEngine(application, scope) },
                frames = VideoFrameSource(loader::load),
                ioDispatcher = Dispatchers.IO,
                closeables = listOf(loader),
            )
        }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        when (result) {
            is FrameKitResult.Success -> setResult(RESULT_OK, Intent().putExtra(VideoEditorContract.EXTRA_OUTPUT, result.output))
            is FrameKitResult.Failure -> setResult(
                VideoEditorContract.RESULT_FAILURE,
                Intent().putExtra(VideoEditorContract.EXTRA_ERROR, result.error),
            )
            FrameKitResult.Cancelled -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
