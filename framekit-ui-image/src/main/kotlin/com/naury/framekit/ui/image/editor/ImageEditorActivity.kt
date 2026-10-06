package com.naury.framekit.ui.image.editor

import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.MediaKind
import androidx.compose.runtime.CompositionLocalProvider
import com.naury.framekit.ui.catalog.LocalCatalogUi
import com.naury.framekit.ui.catalog.CatalogUi
import com.naury.framekit.image.catalog.CatalogAssets
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
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.decode.PreviewResolution
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.image.cutout.BackgroundRemovers
import com.naury.framekit.image.effect.DefaultColorEffectRenderer
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.image.export.ImageMemoryBudget
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.layout.foldPostureFlow
import com.naury.framekit.ui.image.contract.ImageEditorContract
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import java.io.File
import java.util.UUID
import kotlinx.coroutines.launch

/** 이미지 에디터를 호스팅한다. [ImageEditorContract]로 실행하며 exported되지 않는다. */
internal class ImageEditorActivity : ComponentActivity() {

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, ImageEditorContract.EXTRA_REQUEST, ImageEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }

        // 호스트 필터·스티커·폰트를 등록한다. Activity가 다시 만들어질 때도 같은 요청으로 다시 등록한다.
        CatalogAssets.install(this, request.catalog)
        val catalogUi = CatalogUi(request.catalog, CatalogAssets::sticker, CatalogAssets::typeface)
        // 이전에 끝나지 못한 촬영·내보내기가 남긴 임시 파일을 정리한다.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { CaptureFiles.deleteStale(applicationContext) } }
        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )

        val viewModel: ImageEditorViewModel by viewModels { factory(request) }
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
                        ImageEditorScreen(viewModel, currentPosture)
                    }
                }
            }
        }
    }

    private fun factory(request: ImageEditorRequest) = viewModelFactory {
        initializer {
            val application = checkNotNull(this[APPLICATION_KEY])
            val registry = SessionSourceRegistry(application)
            val colorRenderer = DefaultColorEffectRenderer()
            val coordinator = ImageExportCoordinator(
                resolver = registry,
                outputStore = AppFileOutputStore(application),
                memoryBudgetBytes = ImageMemoryBudget.bytes(application),
                contentResolver = application.contentResolver,
                colorRenderer = colorRenderer,
            )
            ImageEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = registry,
                exportCoordinator = coordinator,
                previewLongEdge = PreviewResolution.longEdge(application),
                ioDispatcher = Dispatchers.IO,
                sessionStore = EditorSessionStore(application),
                contentResolver = application.contentResolver,
                colorRenderer = colorRenderer,
                backgroundRemover = BackgroundRemovers.find(application),
                assetFallback = ProjectAssetStore(File(application.cacheDir, "framekit/assets/${UUID.randomUUID()}")),
                captureFile = { CaptureFiles.create(application, MediaKind.IMAGE) },
            )
        }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
        finish()
    }
}
