package com.naury.framekit.android.output

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** export 파일의 저장 위치. */
public sealed interface OutputTarget : Parcelable {

    /**
     * 호스트 앱의 `files/framekit/exports/`에 저장되는 최종 파일로, SDK의 FileProvider가 제공하는
     * `content://` Uri로 반환된다.
     *
     * 파일의 소유권은 호스트에 있다. SDK는 성공한 결과를 스스로 삭제하지 않으므로, 더 이상 필요 없으면
     * [FrameKitOutputs.deleteOutput]을 호출하거나 파일을 옮긴다.
     */
    @Parcelize
    public data object AppFile : OutputTarget
}
