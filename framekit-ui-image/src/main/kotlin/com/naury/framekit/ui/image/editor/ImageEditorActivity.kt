package com.naury.framekit.ui.image.editor

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.session.EditorSessionStore
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.decode.PreviewResolution
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.ui.image.contract.ImageEditorContract
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** Hosts the image editor. Launch it through [ImageEditorContract]; it is not exported. */
internal class ImageEditorActivity : ComponentActivity() {

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, ImageEditorContract.EXTRA_REQUEST, ImageEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }

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
        setContent {
            ProvideEditorLocale(request.ui.localeTag) {
                FrameKitTheme(request.ui) {
                    ImageEditorScreen(viewModel)
                }
            }
        }
    }

    private fun factory(request: ImageEditorRequest) = viewModelFactory {
        initializer {
            val application = checkNotNull(this[APPLICATION_KEY])
            val registry = SessionSourceRegistry(application)
            val coordinator = ImageExportCoordinator(application, registry)
            ImageEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = registry,
                exportCoordinator = coordinator,
                previewLongEdge = PreviewResolution.longEdge(application),
                ioDispatcher = Dispatchers.IO,
                sessionStore = EditorSessionStore(application),
            )
        }
    }

    private fun deliver(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        when (result) {
            is FrameKitResult.Success -> setResult(RESULT_OK, Intent().putExtra(ImageEditorContract.EXTRA_OUTPUT, result.output))
            is FrameKitResult.Failure -> setResult(
                ImageEditorContract.RESULT_FAILURE,
                Intent().putExtra(ImageEditorContract.EXTRA_ERROR, result.error),
            )
            FrameKitResult.Cancelled -> setResult(RESULT_CANCELED)
        }
        finish()
    }
}
