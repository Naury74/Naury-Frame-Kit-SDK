package com.naury.framekit.showcase

import androidx.compose.runtime.remember
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.FrameKitRequest
import android.os.Bundle
import android.os.SystemClock
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import com.naury.kinetica.ui.KTone
import com.naury.kinetica.ui.KToastHost
import com.naury.kinetica.ui.KineticaColors
import com.naury.kinetica.ui.KineticaTheme
import com.naury.kinetica.ui.PushNavigation
import com.naury.kinetica.ui.SurfaceStyles
import com.naury.kinetica.ui.rememberKToastState
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val systemDark = isSystemInDarkTheme()
            var editorTheme by rememberSaveable { mutableStateOf(if (systemDark) ThemeMode.DARK else ThemeMode.LIGHT) }
            val dark = editorTheme == ThemeMode.DARK
            // 앱 안에서 고른 테마에 맞춰 상태 표시줄·내비게이션 바 아이콘 색을 바꾼다.
            LaunchedEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            ShowcaseTheme(dark) {
                KineticaTheme(
                    darkTheme = dark,
                    surface = if (dark) SurfaceStyles.CleanDark else SurfaceStyles.CleanLight,
                    colors = KineticaColors(
                        accent = MaterialTheme.colorScheme.primary,
                        onAccent = MaterialTheme.colorScheme.onPrimary,
                    ),
                ) {
                    ShowcaseApp(editorTheme, onEditorTheme = { editorTheme = it })
                }
            }
        }
    }
}

/** 홈, Playground, 결과 화면을 쌓아 옮긴다. 편집기는 FrameKit 계약으로 따로 띄운다. */
@Composable
private fun ShowcaseApp(editorTheme: ThemeMode, onEditorTheme: (ThemeMode) -> Unit) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val toast = rememberKToastState()
    var result by rememberSaveable { mutableStateOf<ArrayList<EditedMedia>?>(null) }
    var elapsedMs by rememberSaveable { mutableLongStateOf(0L) }
    // 실패는 진단 ID를 옮겨 적을 수 있게 홈에 남겨 두고, 취소·삭제는 잠깐 보이는 토스트로 알린다.
    var failure by rememberSaveable { mutableStateOf<String?>(null) }
    var launchedAt by rememberSaveable { mutableLongStateOf(0L) }
    var playground by rememberSaveable { mutableStateOf(false) }
    // 결과 화면을 닫는 전환 동안에도 내용을 그리도록 마지막 결과를 기억한다.
    var shownResult by remember { mutableStateOf<List<EditedMedia>?>(result) }
    result?.let { shownResult = it }

    fun notify(text: String, tone: KTone = KTone.Neutral) {
        scope.launch { toast.show(text, tone = tone) }
    }

    fun onOutcome(outcome: FrameKitResult) {
        when (outcome) {
            is FrameKitResult.Success -> {
                elapsedMs = SystemClock.elapsedRealtime() - launchedAt
                result = ArrayList(outcome.outputs)
                failure = null
            }
            FrameKitResult.Cancelled -> notify(resources.getString(R.string.result_cancelled))
            is FrameKitResult.Failure -> failure = resources.getString(R.string.result_failed, outcome.error.code.name, outcome.error.diagnosticId)
        }
    }

    val editor = rememberLauncherForActivityResult(FrameKitContract(), ::onOutcome)
    // 움직이는 이미지 예제는 같은 결과 처리로 움직이는 이미지 편집기를 연다.
    val motionEditor = rememberLauncherForActivityResult(MotionEditorContract(), ::onOutcome)

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

    // Playground에서 편집기를 열고 돌아오면 결과 화면이 Playground 위에 쌓인다.
    val stack = buildList {
        add(Screen.HOME)
        if (playground) add(Screen.PLAYGROUND)
        if (result != null) add(Screen.RESULT)
    }
    PushNavigation(
        stack = stack,
        onBack = { if (result != null) result = null else playground = false },
        paneTitle = { screen ->
            when (screen) {
                Screen.HOME -> "FrameKit"
                Screen.PLAYGROUND -> resources.getString(R.string.playground_title)
                Screen.RESULT -> null
            }
        },
    ) { screen ->
        when (screen) {
            Screen.HOME -> HomeScreen(
                failure = failure,
                onDismissFailure = { failure = null },
                editorTheme = editorTheme,
                onEditorTheme = onEditorTheme,
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
            Screen.PLAYGROUND -> PlaygroundScreen(
                onBack = { playground = false },
                onLaunch = { request ->
                    launchedAt = SystemClock.elapsedRealtime()
                    editor.launch(request)
                },
            )
            Screen.RESULT -> shownResult?.let { outputs ->
                ResultScreen(
                    outputs = outputs,
                    elapsedMs = elapsedMs,
                    onBack = { result = null },
                    onDeleteAll = {
                        outputs.forEach { FrameKitOutputs.deleteOutput(context, it.uri) }
                        result = null
                        notify(resources.getString(R.string.result_deleted), KTone.Positive)
                    },
                )
            }
        }
    }
    KToastHost(toast)
}

private enum class Screen { HOME, PLAYGROUND, RESULT }
