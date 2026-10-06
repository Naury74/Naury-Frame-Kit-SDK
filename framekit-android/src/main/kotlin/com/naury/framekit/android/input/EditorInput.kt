package com.naury.framekit.android.input

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** 내장 picker가 제공할 수 있는 미디어 종류. */
public enum class MediaKind {
    IMAGE,
    VIDEO,
    ANY,
}

/**
 * 편집기가 원본을 가져오는 위치.
 *
 * Activity 요청에는 참조만 담는다. Bitmap, Drawable, 콜백은 프로세스 재생성 후 살아남지 못하고
 * Binder 한도를 넘을 수 있으므로 받지 않는다.
 */
public sealed interface EditorInput : Parcelable {

    /**
     * 호스트가 이미 읽을 수 있는 `content://` 또는 `file://` Uri.
     *
     * 다른 앱의 Uri라면 호스트가 실행 시 읽기 권한을 함께 넘겨야 한다. 예를 들어 자체 picker 결과로
     * 받은 권한을 유지하면 된다. 편집기는 scheme만 보고 권한이 있다고 가정하지 않고, 열기 전에
     * 접근 가능 여부를 확인해 불가능하면 `PERMISSION_DENIED`를 보고한다.
     */
    @Parcelize
    public data class UriSource(val uri: Uri) : EditorInput

    /**
     * 호스트 앱 전용 저장소 안의 파일로, 절대 경로로 전달한다.
     *
     * 편집기는 호스트 프로세스에서 실행되므로 provider로 노출하지 않고 파일을 직접 읽는다.
     */
    @Parcelize
    public data class FileSource(val absolutePath: String) : EditorInput

    /**
     * 먼저 시스템 Photo Picker를 연다. picker를 닫으면 `Cancelled`를 반환한다.
     *
     * @property maxItems 한 번에 고를 수 있는 개수. 1이면 한 장(한 개), 2 이상이면 여러 장 사진 편집이나
     *   여러 클립 영상 편집으로 열린다. 편집기 설정의 최대 개수를 넘을 수 없다.
     */
    @Parcelize
    public data class Pick(val kind: MediaKind = MediaKind.IMAGE, val maxItems: Int = 1) : EditorInput

    /**
     * 호스트가 이미 가진 여러 원본을 한 번에 연다. 모두 사진이면 여러 장 사진 편집, 모두 영상이면 여러 클립
     * 영상 편집으로 열린다. 사진과 영상을 섞을 수는 없다.
     *
     * @property items [UriSource]나 [FileSource]만 담는다. 순서가 편집·결과 순서다.
     */
    @Parcelize
    public data class Multiple(val items: List<EditorInput>) : EditorInput

    /**
     * 기기 카메라 앱으로 바로 찍은 사진(또는 영상)을 연다. 촬영을 취소하면 `Cancelled`를 반환한다.
     *
     * 찍은 파일은 앱 캐시에 임시로 두었다가 편집이 끝나면 지운다. 호스트가 manifest에 `CAMERA` 권한을
     * 선언했다면 편집기가 권한을 요청하고, 거부되면 `PERMISSION_DENIED`를 반환한다. 카메라 앱이 없으면
     * `CAMERA_UNAVAILABLE`.
     *
     * @property kind [MediaKind.IMAGE] 또는 [MediaKind.VIDEO].
     */
    @Parcelize
    public data class Capture(val kind: MediaKind = MediaKind.IMAGE) : EditorInput

    public companion object {
        /** 여러 개를 고를 수 있는 picker 개수 상한. 시스템 picker 한도와 같다. */
        public const val MAX_PICK_ITEMS: Int = 100
    }
}

/** [EditorInput.Multiple]에 담을 수 있는 단일 원본이면 `true`. */
public val EditorInput.isSingleSource: Boolean
    get() = this is EditorInput.UriSource || this is EditorInput.FileSource

/** 이 입력이 한 번에 여는 원본 개수의 상한. picker는 고를 수 있는 최대 개수. */
public val EditorInput.maxItemCount: Int
    get() = when (this) {
        is EditorInput.Pick -> maxItems
        is EditorInput.Multiple -> items.size
        else -> 1
    }
