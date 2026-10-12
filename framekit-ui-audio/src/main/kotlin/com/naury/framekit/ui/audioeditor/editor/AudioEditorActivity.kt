package com.naury.framekit.ui.audioeditor.editor

import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorError
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.audioeditor.contract.AudioEditorContract
import com.naury.framekit.ui.audioeditor.contract.AudioEditorRequest
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.design.ProvideEditorLocale
import com.naury.framekit.video.audio.WaveformExtractor
import com.naury.framekit.video.media3.AudioTrackMixer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

/** 음성 녹음·편집기를 담는다. [AudioEditorContract]로 열며 exported되지 않는다. */
internal class AudioEditorActivity : ComponentActivity() {

    private var delivered = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, AudioEditorContract.EXTRA_REQUEST, AudioEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val viewModel: AudioEditorViewModel by viewModels { factory(request) }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { deliver(viewModel.result.filterNotNull().first()) }
        }
        setContent {
            ProvideEditorLocale(request.ui.localeTag) {
                FrameKitTheme(request.ui) { AudioEditorScreen(viewModel) }
            }
        }
    }

    private fun factory(request: AudioEditorRequest) = viewModelFactory {
        initializer {
            val application = checkNotNull(this[APPLICATION_KEY])
            lateinit var model: AudioEditorViewModel
            model = AudioEditorViewModel(
                request = request,
                savedState = createSavedStateHandle(),
                registry = SessionSourceRegistry(application),
                outputStore = AppFileOutputStore(application),
                ioDispatcher = Dispatchers.IO,
                describe = { input -> describe(application, input) },
                mixer = { duration, tracks, output, bitrate, progress -> AudioTrackMixer.mixAudioOnly(application, duration, tracks, output, bitrate, progress) },
                waveformLoader = { uri -> WaveformExtractor.extract(application, uri) },
                player = AudioClipPlayer(application) { id -> model.uriOf(id) },
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

    private companion object {
        // 소리 파일의 이름과 길이를 읽는다. 소리가 없으면 null.
        fun describe(context: android.content.Context, input: EditorInput): AudioSource? {
            val uri = when (input) {
                is EditorInput.UriSource -> input.uri
                is EditorInput.FileSource -> Uri.fromFile(File(input.absolutePath))
                else -> return null
            }
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context, uri)
                if (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) != "yes") return null
                val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: return null
                val name = (input as? EditorInput.UriSource)?.let {
                    runCatching {
                        context.contentResolver.query(it.uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.substringBeforeLast('.') else null }
                    }.getOrNull()
                }
                AudioSource(name, duration * 1000)
            } catch (_: Exception) {
                null
            } finally {
                runCatching { retriever.release() }
            }
        }
    }
}
