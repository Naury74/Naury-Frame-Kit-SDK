package com.naury.framekit.ui.source

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.MediaPickerResult

/**
 * 편집기가 원본을 받아 오는 화면(사진 고르기, 카메라 앱, 카메라 권한)을 한곳에서 연다.
 *
 * 고르기는 [MediaPickerContract]로 연다(`framekit-picker`가 있으면 앱 안 갤러리, 없으면 시스템 사진 선택기). 카메라 앱이 없거나 권한이
 * 거부되면 [onFailed]로 알리고 앱은 멈추지 않는다.
 */
public class SourceLaunchers internal constructor(
    private val pickMedia: (MediaKind, Int) -> Unit,
    private val capture: (MediaKind, () -> Uri?) -> Unit,
) {
    /** [maxItems]가 1이면 한 개, 2 이상이면 여러 개를 고르는 화면을 연다. */
    public fun pick(kind: MediaKind, maxItems: Int) {
        pickMedia(kind, maxItems)
    }

    /**
     * 카메라 앱을 연다. 필요하면 먼저 CAMERA 권한을 요청한다.
     *
     * @param target 촬영 결과를 받을 Uri를 만든다. `null`이면 아무것도 하지 않는다(호출한 쪽이 실패를 처리).
     */
    public fun capture(kind: MediaKind, target: () -> Uri?) {
        capture.invoke(kind, target)
    }
}

/**
 * @param maxItems 여러 개를 고를 때의 최대 개수. [SourceLaunchers.pick]에 넘긴 값과 작은 쪽을 쓴다.
 * @param onPicked 고른 원본. 닫으면 빈 목록이다.
 * @param onCaptured 카메라 앱이 결과를 저장했으면 `true`.
 * @param ui 고르는 화면의 종류([EditorUiConfig.picker])와 앱 안 갤러리의 색·언어.
 * @param captureTarget 권한 창이 떠 있는 동안 화면이 다시 만들어져 [SourceLaunchers.capture]에 넘긴 함수를 잃었을 때 대신 쓸
 *   촬영 Uri를 만든다. `null`이면 이때 [onCaptured]에 `false`를 넘긴다.
 */
@Composable
public fun rememberSourceLaunchers(
    maxItems: Int,
    onPicked: (List<Uri>) -> Unit,
    onCaptured: (Boolean) -> Unit,
    onFailed: (EditorErrorCode) -> Unit,
    ui: EditorUiConfig = FrameKitTheme.config,
    captureTarget: ((MediaKind) -> Uri?)? = null,
): SourceLaunchers {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onFailed)
    val currentUi by rememberUpdatedState(ui)
    val fallbackTarget by rememberUpdatedState(captureTarget)
    val media = rememberLauncherForActivityResult(MediaPickerContract()) { result ->
        picked((result as? MediaPickerResult.Picked)?.items?.map { it.uri }.orEmpty())
    }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> captured(ok) }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> captured(ok) }
    // 권한을 묻는 중인 촬영 종류. 권한 창이 떠 있는 동안 화면이 다시 만들어져도 결과를 이어 받도록 저장한다.
    val pendingKind = rememberSaveable { mutableStateOf<MediaKind?>(null) }
    // Uri를 만드는 함수는 저장할 수 없어 메모리에만 둔다. 다시 만들어진 뒤에는 captureTarget을 쓴다.
    val pendingTarget = remember { arrayOfNulls<() -> Uri?>(1) }

    fun launchCamera(kind: MediaKind, target: () -> Uri?) {
        val uri = target() ?: return
        try {
            if (kind == MediaKind.VIDEO) video.launch(uri) else photo.launch(uri)
        } catch (_: ActivityNotFoundException) {
            failed(EditorErrorCode.CAMERA_UNAVAILABLE)
        } catch (_: SecurityException) {
            failed(EditorErrorCode.PERMISSION_DENIED)
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val kind = pendingKind.value
        val target = pendingTarget[0] ?: fallbackTarget?.let { make -> { make(kind ?: MediaKind.IMAGE) } }
        pendingKind.value = null
        pendingTarget[0] = null
        when {
            kind == null -> Unit
            !granted -> failed(EditorErrorCode.PERMISSION_DENIED)
            target == null -> captured(false)
            else -> launchCamera(kind, target)
        }
    }

    return remember(media, photo, video, permission) {
        SourceLaunchers(
            pickMedia = { kind, count ->
                val limit = count.coerceIn(1, maxOf(1, maxItems))
                val pickKind = if (kind == MediaKind.AUDIO) MediaKind.ANY else kind
                try {
                    media.launch(MediaPickerRequest(pickKind, maxItems = limit, style = currentUi.picker, ui = currentUi))
                } catch (_: ActivityNotFoundException) {
                    failed(EditorErrorCode.SOURCE_UNAVAILABLE)
                }
            },
            capture = { kind, target ->
                if (CaptureFiles.needsCameraPermission(context)) {
                    pendingKind.value = kind
                    pendingTarget[0] = target
                    permission.launch(Manifest.permission.CAMERA)
                } else {
                    launchCamera(kind, target)
                }
            },
        )
    }
}
