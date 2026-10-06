package com.naury.framekit.ui.video.editor

import androidx.compose.runtime.CompositionLocalProvider
import com.naury.framekit.ui.catalog.LocalCatalogUi
import com.naury.framekit.ui.catalog.CatalogUi
import com.naury.framekit.image.catalog.CatalogAssets
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
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
import com.naury.framekit.android.session.EditorSessionStore
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

/** 영상 에디터를 호스팅한다. [VideoEditorContract]로 실행하며 exported되지 않는다. */
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

        // 호스트 필터·스티커·폰트를 등록한다. Activity가 다시 만들어질 때도 같은 요청으로 다시 등록한다.
        CatalogAssets.install(this, request.catalog)
        val catalogUi = CatalogUi(request.catalog, CatalogAssets::sticker)
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
            CompositionLocalProvider(LocalCatalogUi provides catalogUi) {
                ProvideEditorLocale(request.ui.localeTag) {
                    FrameKitTheme(request.ui) {
                        VideoEditorScreen(viewModel, currentPosture)
                    }
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
            val reader = VideoMetadataReader(application, registry)
            VideoEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = registry,
                readSource = reader::read,
                readAudio = reader::readAudio,
                describe = { uri -> displayName(application.contentResolver, uri) },
                exporter = { project, sources, locations, audioSources, onProgress ->
                    coordinator.export(
                        project = project,
                        sources = sources,
                        locations = locations,
                        config = request.export,
                        target = request.output,
                        minClipOutputDurationUs = request.config.minClipDurationUs,
                        audioSources = audioSources,
                        onProgress = onProgress,
                    )
                },
                previewEngineFactory = { scope -> Media3VideoPreviewEngine(application, scope) },
                frames = VideoFrameSource(loader::load),
                ioDispatcher = Dispatchers.IO,
                closeables = listOf(loader),
                sessionStore = EditorSessionStore(application),
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

// 배경 음악 이름 표시용. 파일 이름을 읽지 못하면 이름 없이 표시한다.
private fun displayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
    resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0) else null
    }
}.getOrNull()
