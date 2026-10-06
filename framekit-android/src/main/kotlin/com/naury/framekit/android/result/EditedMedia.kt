package com.naury.framekit.android.result

import android.net.Uri
import android.os.Parcelable
import com.naury.framekit.core.model.MediaType
import kotlinx.parcelize.Parcelize

/** 요청과 실제 생성된 파일 사이의 치명적이지 않은 차이. */
public enum class ExportWarning {
    /** 원본이 광색역 등 sRGB가 아닌 색 공간을 사용해 sRGB로 변환했다. */
    COLOR_SPACE_CONVERTED_TO_SRGB,

    /** 원본에 Ultra HDR gain map이 있었지만 결과물에는 보존되지 않는다. */
    HDR_GAIN_MAP_DROPPED,

    /** 인코더가 요청한 포맷을 만들 수 없어 지원되는 대체 포맷을 사용했다. */
    ENCODER_FALLBACK_APPLIED,

    /** 원본 프레임 레이트가 설정된 최대값보다 높아 프레임을 버렸다. */
    FRAME_RATE_REDUCED,

    /** HDR 영상을 SDR로 변환했다. */
    HDR_CONVERTED_TO_SDR,
}

/**
 * export에 성공한 파일.
 *
 * @property uri 호스트 프로세스가 읽을 수 있는 `content://` Uri. 다른 앱과 공유할 때는
 *   `FLAG_GRANT_READ_URI_PERMISSION`을 붙인 Intent로 전달한다.
 * @property width 인코딩된 너비(픽셀).
 * @property height 인코딩된 높이(픽셀).
 * @property durationMs 영상 길이(밀리초). 이미지는 `null`이다.
 * @property fileSize 파일 크기(바이트).
 * @property pageCount PDF 문서의 쪽 수. 사진·영상은 `null`이다. PDF의 [width]·[height]는 첫 쪽 크기(pt, 1/72인치)다.
 */
@Parcelize
public data class EditedMedia(
    val uri: Uri,
    val mediaType: MediaType,
    val width: Int,
    val height: Int,
    val durationMs: Long?,
    val mimeType: String,
    val fileSize: Long,
    val warnings: List<ExportWarning> = emptyList(),
    val pageCount: Int? = null,
) : Parcelable
