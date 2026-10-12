package com.naury.framekit.ui.motion.editor

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.CompositionLocalProvider
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.catalog.CatalogAssets
import com.naury.framekit.image.effect.DefaultColorEffectRenderer
import com.naury.framekit.image.export.ImageMemoryBudget
import com.naury.framekit.motion.export.MotionExporter
import com.naury.framekit.motion.source.MotionSourceLoader
import com.naury.framekit.video.audio.WaveformExtractor
import com.naury.framekit.ui.catalog.CatalogUi
import com.naury.framekit.ui.catalog.LocalCatalogUi
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.motion.contract.MotionEditorContract
import com.naury.framekit.ui.motion.contract.MotionEditorRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** 움직이는 이미지 편집기를 담는다. [MotionEditorContract]로 열며 exported되지 않는다. */
internal class MotionEditorActivity : ComponentActivity() {

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, MotionEditorContract.EXTRA_REQUEST, MotionEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        CatalogAssets.install(this, request.catalog)
        val catalogUi = CatalogUi(request.catalog, CatalogAssets::sticker, CatalogAssets::typeface)
        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val viewModel: MotionEditorViewModel by viewModels { factory(request) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { deliver(viewModel.result.filterNotNull().first()) }
        }
        setContent {
            CompositionLocalProvider(LocalCatalogUi provides catalogUi) {
                ProvideEditorLocale(request.ui.localeTag) {
                    FrameKitTheme(request.ui) {
                        MotionEditorScreen(viewModel)
                    }
                }
            }
        }
    }

    private fun factory(request: MotionEditorRequest) = viewModelFactory {
        initializer {
            val application = checkNotNull(this[APPLICATION_KEY])
            val registry = SessionSourceRegistry(application)
            lateinit var model: MotionEditorViewModel
            model = MotionEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = registry,
                loader = MotionSourceLoader(registry, application.contentResolver),
                exporter = MotionExporter(AppFileOutputStore(application), application) { DefaultColorEffectRenderer() },
                colorRenderer = DefaultColorEffectRenderer(),
                ioDispatcher = Dispatchers.IO,
                renderDispatcher = Dispatchers.Default.limitedParallelism(1),
                memoryBudgetBytes = ImageMemoryBudget.bytes(application),
                audioPlayer = MotionAudioPlayer(application) { id -> model.uriOf(id) },
                describeAudio = { input -> MotionAudioSources.describe(application, input) },
                waveformLoader = { uri -> WaveformExtractor.extract(application, uri) },
            )
            model
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
