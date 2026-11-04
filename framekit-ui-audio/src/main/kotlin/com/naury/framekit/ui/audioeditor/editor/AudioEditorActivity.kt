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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.IntentCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
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
import com.naury.framekit.ui.audio.VoiceRecorder
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
internal open class AudioEditorActivity : ComponentActivity() {

    private var delivered = false

    /** 시트로 닫는 중이면 `true`. 닫힘 애니메이션이 끝나면 [finish]한다. */
    private var closing by mutableStateOf(false)

    /** `true`면 녹음 화면을 호스트 화면 위 시트로 띄운다([AudioRecorderSheetActivity]). */
    protected open val presentsSheet: Boolean get() = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val request = IntentCompat.getParcelableExtra(intent, AudioEditorContract.EXTRA_REQUEST, AudioEditorRequest::class.java)
        if (request == null || request.validate() is ValidationResult.Invalid) {
            deliver(FrameKitResult.Failure(EditorError(EditorErrorCode.INVALID_CONFIGURATION)))
            return
        }
        // 오래되고 편집 세션이 쓰지 않는 녹음 파일을 정리한다.
        lifecycleScope.launch(Dispatchers.IO) { runCatching { VoiceRecorder.deleteStale(applicationContext) } }
        val darkBars = request.ui.themeMode != ThemeMode.LIGHT
        enableEdgeToEdge(
            statusBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (darkBars) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        val viewModel: AudioEditorViewModel by viewModels { factory(request) }
        // 녹음할 수 없어 바로 실패하면 시트를 띄우지 않는다.
        viewModel.result.value?.let { return deliver(it) }
        // 결과가 나오면 화면이 백그라운드여도 바로 돌려준다. setResult·finish로 넘긴 결과는 시스템이 보관하므로 이 프로세스가 끝나도 남는다.
        // 시트는 결과를 먼저 넘겨 두고, 보이는 동안이면 닫힘 애니메이션 뒤에 끝낸다.
        lifecycleScope.launch {
            val result = viewModel.result.filterNotNull().first()
            if (presentsSheet && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                setResultOnce(result)
                closing = true
            } else {
                deliver(result)
            }
        }
        setContent {
            ProvideEditorLocale(request.ui.localeTag) {
                FrameKitTheme(request.ui) {
                    if (presentsSheet) {
                        RecorderSheet(viewModel, closing = closing, onClosed = ::finishSheet)
                    } else {
                        AudioEditorScreen(viewModel)
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // 닫는 중에 화면이 가려지면 애니메이션을 기다리지 않는다.
        if (closing) finishSheet()
    }

    // 시트는 호스트 화면 위에 그대로 사라지도록 창 전환 애니메이션 없이 끝낸다.
    private fun finishSheet() {
        if (isFinishing) return
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
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
                recordBlocked = when {
                    !VoiceRecorder.isDeclared(application) -> EditorErrorCode.PERMISSION_DENIED
                    !VoiceRecorder.hasMicrophone(application) -> EditorErrorCode.UNSUPPORTED_OPERATION
                    else -> null
                },
            )
            model
        }
    }

    private fun deliver(result: FrameKitResult) {
        setResultOnce(result)
        if (presentsSheet) finishSheet() else finish()
    }

    private fun setResultOnce(result: FrameKitResult) {
        if (delivered) return
        delivered = true
        val (code, data) = FrameKitResultCodec.encode(result)
        setResult(code, data)
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
