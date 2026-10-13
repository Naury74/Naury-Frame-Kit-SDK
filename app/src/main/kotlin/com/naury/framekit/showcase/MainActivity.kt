package com.naury.framekit.showcase

import androidx.compose.runtime.remember
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.FrameKitRequest
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import com.naury.framekit.FrameKitContract
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.MediaPickerResult
import com.naury.framekit.ui.motion.contract.MotionEditorContract
import com.naury.framekit.android.output.FrameKitOutputs
import com.naury.framekit.android.result.EditedMedia
import com.naury.framekit.android.result.FrameKitResult

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ShowcaseTheme {
                val context = LocalContext.current
                val resources = LocalResources.current
                var result by rememberSaveable { mutableStateOf<ArrayList<EditedMedia>?>(null) }
                var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
                var lastMessage by rememberSaveable { mutableStateOf<String?>(null) }
                var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
                var playground by rememberSaveable { mutableStateOf(false) }
                var editorTheme by rememberSaveable { mutableStateOf(ThemeMode.DARK) }

                val editor = rememberLauncherForActivityResult(FrameKitContract()) { outcome ->
                    when (outcome) {
                        is FrameKitResult.Success -> {
                            elapsedMs = SystemClock.elapsedRealtime() - launchedAt
                            result = ArrayList(outcome.outputs)
                            lastMessage = null
                        }
                        FrameKitResult.Cancelled -> lastMessage = resources.getString(R.string.result_cancelled)
                        is FrameKitResult.Failure -> lastMessage = resources.getString(
                            R.string.result_failed,
                            outcome.error.code.name,
                            outcome.error.diagnosticId,
                        )
                    }
                }

                // 움직이는 이미지 예제는 같은 결과 처리로 움직이는 이미지 편집기를 연다.
                val motionEditor = rememberLauncherForActivityResult(MotionEditorContract()) { outcome ->
                    when (outcome) {
                        is FrameKitResult.Success -> {
                            elapsedMs = SystemClock.elapsedRealtime() - launchedAt
                            result = ArrayList(outcome.outputs)
                            lastMessage = null
                        }
                        FrameKitResult.Cancelled -> lastMessage = resources.getString(R.string.result_cancelled)
                        is FrameKitResult.Failure -> lastMessage = resources.getString(R.string.result_failed, outcome.error.code.name, outcome.error.diagnosticId)
                    }
                }

                // 호스트 앱의 앨범 화면: FrameKit 갤러리(MediaPickerContract)로 먼저 고르고, 고른 파일을 FrameKit에 넘겨 무엇을 할지 묻게 한다.
                var pendingHost by remember { mutableStateOf<FrameKitRequest?>(null) }
                val hostPicker = rememberLauncherForActivityResult(MediaPickerContract()) { picked ->
                    val base = pendingHost
                    pendingHost = null
                    val uris = (picked as? MediaPickerResult.Picked)?.items?.map { it.uri }.orEmpty()
                    if (base != null && uris.isNotEmpty()) {
                        val input = if (uris.size == 1) EditorInput.UriSource(uris.single()) else EditorInput.Multiple(uris.map(EditorInput::UriSource))
                        launchedAt = SystemClock.elapsedRealtime()
                        editor.launch(base.copy(input = input))
                    }
                }

                val current = result
                if (current == null && playground) {
                    BackHandler { playground = false }
                    PlaygroundScreen(
                        onBack = { playground = false },
                        onLaunch = { request ->
                            launchedAt = SystemClock.elapsedRealtime()
                            editor.launch(request)
                        },
                    )
                } else if (current == null) {
                    HomeScreen(
                        message = lastMessage,
                        editorTheme = editorTheme,
                        onEditorTheme = { editorTheme = it },
                        onLaunch = { example ->
                            launchedAt = SystemClock.elapsedRealtime()
                            val motion = example.motion
                            val request = example.request.copy(ui = example.request.ui.copy(themeMode = editorTheme))
                            when {
                                motion != null -> motionEditor.launch(motion.copy(ui = motion.ui.copy(themeMode = editorTheme)))
                                example.hostPick -> {
                                    pendingHost = request
                                    hostPicker.launch(
                                        MediaPickerRequest(
                                            MediaKind.ANY,
                                            maxItems = 10,
                                            maxFileSizeBytes = 50L * 1024 * 1024,
                                            maxVideoDurationMs = 60_000,
                                            ui = EditorUiConfig(themeMode = editorTheme),
                                        ),
                                    )
                                }
                                else -> editor.launch(request)
                            }
                        },
                        onPlayground = { playground = true },
                    )
                } else {
                    BackHandler { result = null }
                    ResultScreen(
                        outputs = current,
                        elapsedMs = elapsedMs,
                        onBack = { result = null },
                        onDeleteAll = {
                            current.forEach { FrameKitOutputs.deleteOutput(context, it.uri) }
                            result = null
                            lastMessage = resources.getString(R.string.result_deleted)
                        },
                    )
                }
            }
        }
    }
}
