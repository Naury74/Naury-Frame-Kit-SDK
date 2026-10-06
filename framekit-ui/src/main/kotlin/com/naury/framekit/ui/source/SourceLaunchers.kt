package com.naury.framekit.ui.source

import android.Manifest
import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext
import com.naury.framekit.android.capture.CaptureFiles
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditorErrorCode

/**
 * 편집기가 원본을 받아 오는 시스템 화면(사진 picker, 카메라 앱, 카메라 권한)을 한곳에서 연다.
 *
 * picker가 없는 오래된 기기에서는 AndroidX가 문서 선택 화면으로 대신한다. 카메라 앱이 없거나 권한이
 * 거부되면 [onFailed]로 알리고 앱은 멈추지 않는다.
 */
public class SourceLaunchers internal constructor(
    private val pickOne: (MediaKind) -> Unit,
    private val pickMany: (MediaKind) -> Unit,
    private val capture: (MediaKind, () -> Uri?) -> Unit,
) {
    /** [maxItems]가 1이면 한 개, 2 이상이면 여러 개를 고르는 picker를 연다. */
    public fun pick(kind: MediaKind, maxItems: Int) {
        if (maxItems > 1) pickMany(kind) else pickOne(kind)
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
 * @param maxItems 여러 개 picker의 최대 개수. 2보다 작으면 2로 맞춘다(시스템 요구).
 * @param onPicked 고른 원본. 닫으면 빈 목록이다.
 * @param onCaptured 카메라 앱이 결과를 저장했으면 `true`.
 */
@Composable
public fun rememberSourceLaunchers(
    maxItems: Int,
    onPicked: (List<Uri>) -> Unit,
    onCaptured: (Boolean) -> Unit,
    onFailed: (EditorErrorCode) -> Unit,
): SourceLaunchers {
    val context = LocalContext.current
    val picked by rememberUpdatedState(onPicked)
    val captured by rememberUpdatedState(onCaptured)
    val failed by rememberUpdatedState(onFailed)
    val single = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> picked(listOfNotNull(uri)) }
    val multiple = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(maxOf(2, maxItems))) { uris -> picked(uris) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok -> captured(ok) }
    val video = rememberLauncherForActivityResult(ActivityResultContracts.CaptureVideo()) { ok -> captured(ok) }
    val pending = remember { arrayOfNulls<Pair<MediaKind, () -> Uri?>>(1) }

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
        val request = pending[0]
        pending[0] = null
        when {
            request == null -> Unit
            granted -> launchCamera(request.first, request.second)
            else -> failed(EditorErrorCode.PERMISSION_DENIED)
        }
    }

    return remember(single, multiple, photo, video, permission) {
        SourceLaunchers(
            pickOne = { kind -> single.launch(PickVisualMediaRequest(kind.toPickerType())) },
            pickMany = { kind -> multiple.launch(PickVisualMediaRequest(kind.toPickerType())) },
            capture = { kind, target ->
                if (CaptureFiles.needsCameraPermission(context)) {
                    pending[0] = kind to target
                    permission.launch(Manifest.permission.CAMERA)
                } else {
                    launchCamera(kind, target)
                }
            },
        )
    }
}

private fun MediaKind.toPickerType(): ActivityResultContracts.PickVisualMedia.VisualMediaType = when (this) {
    MediaKind.IMAGE -> ActivityResultContracts.PickVisualMedia.ImageOnly
    MediaKind.VIDEO -> ActivityResultContracts.PickVisualMedia.VideoOnly
    MediaKind.ANY -> ActivityResultContracts.PickVisualMedia.ImageAndVideo
}
