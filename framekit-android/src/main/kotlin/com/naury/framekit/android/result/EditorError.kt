package com.naury.framekit.android.result

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/** 안정적인 오류 코드. 현지화는 호스트가 담당하며, SDK는 번역된 메시지를 반환하지 않는다. */
public enum class EditorErrorCode(
    public val defaultRecoverable: Boolean,
    public val defaultAction: SuggestedAction,
) {
    /** MIME 타입, 미디어 종류 또는 메타데이터가 편집기가 열 수 있는 것과 맞지 않는다. */
    INVALID_SOURCE(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 읽기 권한이 없어 원본을 열 수 없다. */
    PERMISSION_DENIED(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 원본이 삭제·이동되었거나 provider가 오프라인이다. */
    SOURCE_UNAVAILABLE(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 원본이 편집기가 허용하는 크기보다 커서 열면 메모리가 고갈될 수 있다. */
    SOURCE_TOO_LARGE(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 컨테이너, 코덱 또는 이미지 포맷을 지원하지 않는다. */
    UNSUPPORTED_FORMAT(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 기기가 요청한 편집이나 출력을 수행할 수 없다. */
    UNSUPPORTED_OPERATION(true, SuggestedAction.CHANGE_OUTPUT_SETTINGS),

    /** 호스트가 문서화된 범위를 벗어난 설정을 전달했다. */
    INVALID_CONFIGURATION(false, SuggestedAction.FIX_CONFIGURATION),

    /** 프로젝트에 문서화된 범위를 벗어난 값이 있다. */
    INVALID_PROJECT(false, SuggestedAction.FIX_CONFIGURATION),

    /** 원본이 손상되었거나 디코딩할 수 없다. */
    DECODE_FAILED(false, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** 인코더가 실패했다. 해상도를 낮추면 성공할 수 있다. */
    ENCODE_FAILED(true, SuggestedAction.LOWER_RESOLUTION),

    /** 이미지가 이 기기의 메모리 예산을 넘는다. */
    INSUFFICIENT_MEMORY(true, SuggestedAction.LOWER_RESOLUTION),

    /** 결과물을 쓸 여유 공간이 부족하다. */
    INSUFFICIENT_STORAGE(true, SuggestedAction.FREE_STORAGE),

    /** 결과 파일을 쓰거나 확정하는 데 실패했다. */
    OUTPUT_WRITE_FAILED(true, SuggestedAction.RETRY),

    /** 프로세스 종료 이후처럼 편집기가 읽을 수 있는 결과 없이 돌아왔다. */
    RESULT_UNAVAILABLE(true, SuggestedAction.RELAUNCH),

    /** 다른 어떤 코드에도 해당하지 않는 엔진 실패. */
    UNKNOWN(true, SuggestedAction.RETRY),
}

/** 사용자가 다음에 할 수 있는 조치. */
public enum class SuggestedAction {
    CHOOSE_ANOTHER_SOURCE,
    CHANGE_OUTPUT_SETTINGS,
    FIX_CONFIGURATION,
    LOWER_RESOLUTION,
    FREE_STORAGE,
    RETRY,
    RELAUNCH,
}

/**
 * 호스트에 전달되는 오류.
 *
 * 원인 예외는 포함하지 않는다. 예외 타입은 [diagnosticId]와 함께 기기 로그에 기록되므로,
 * 결과에 파일 이름이나 Uri를 노출하지 않고도 사용자 신고를 로그와 대조할 수 있다.
 */
@Parcelize
public data class EditorError(
    val code: EditorErrorCode,
    val recoverable: Boolean = code.defaultRecoverable,
    val suggestedAction: SuggestedAction = code.defaultAction,
    val diagnosticId: String = newDiagnosticId(),
) : Parcelable {
    public companion object {
        internal fun newDiagnosticId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
