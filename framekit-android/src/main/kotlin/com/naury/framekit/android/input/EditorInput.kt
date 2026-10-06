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

    /** 먼저 시스템 Photo Picker를 연다. picker를 닫으면 `Cancelled`를 반환한다. */
    @Parcelize
    public data class Pick(val kind: MediaKind = MediaKind.IMAGE) : EditorInput
}
