package com.naury.framekit.ui.picker

import android.net.Uri
import android.os.Parcelable
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.EditorUiConfig
import kotlinx.parcelize.Parcelize

/** 사진·영상을 고르는 화면의 종류. */
public enum class PickerStyle {
    /**
     * `framekit-picker` 모듈이 있으면 앱 안 갤러리(앨범별 보기·선택 순서·미리보기·제한)를 연다. 호스트가 사진 읽기 권한을
     * 선언하지 않았거나 사용자가 거부하면 시스템 사진 선택기로 대신한다. 모듈이 없으면 시스템 사진 선택기.
     */
    AUTO,

    /** 항상 시스템 사진 선택기를 연다. */
    SYSTEM,
}

/**
 * 사진·영상 고르기 요청. [MediaPickerContract]로 연다.
 *
 * 제한([maxFileSizeBytes], [maxVideoDurationMs], [mimeTypes])은 앱 안 갤러리에서는 고르기 전에 막고, 시스템 선택기로
 * 대신한 경우에는 고른 뒤 걸러 낸다(`framekit-picker`가 있을 때). 모듈 없이 시스템 선택기만 쓰면 개수 외의 제한은
 * 적용되지 않는다.
 *
 * @property kind 고를 종류. [MediaKind.AUDIO]는 지원하지 않는다.
 * @property maxItems 최대 개수, `1..100`. 1이면 하나를 누르는 즉시 돌려준다.
 * @property minItems 완료할 수 있는 최소 개수, `1..maxItems`.
 * @property maxFileSizeBytes 파일 하나의 최대 크기(byte). `null`이면 제한 없음.
 * @property maxVideoDurationMs 영상 하나의 최대 길이(ms). `null`이면 제한 없음.
 * @property mimeTypes 보여 줄 형식. `image/jpeg`처럼 정확히 쓰거나 `image/ *` 형태(공백 없이)로 쓴다. 비어 있으면 [kind]의 모든 형식.
 * @property style 앱 안 갤러리를 쓸지.
 * @property ui 앱 안 갤러리의 색·언어. 편집기와 같은 설정을 쓴다.
 */
@Parcelize
public data class MediaPickerRequest(
    val kind: MediaKind = MediaKind.ANY,
    val maxItems: Int = 1,
    val minItems: Int = 1,
    val maxFileSizeBytes: Long? = null,
    val maxVideoDurationMs: Long? = null,
    val mimeTypes: Set<String> = emptySet(),
    val style: PickerStyle = PickerStyle.AUTO,
    val ui: EditorUiConfig = EditorUiConfig(),
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (kind == MediaKind.AUDIO) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.kind", "Audio is not supported")
        if (maxItems !in 1..EditorInput.MAX_PICK_ITEMS) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.maxItems", "Expected 1..${EditorInput.MAX_PICK_ITEMS}")
        if (minItems !in 1..maxItems.coerceAtLeast(1)) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.minItems", "Expected 1..maxItems")
        if (maxFileSizeBytes != null && maxFileSizeBytes <= 0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.maxFileSizeBytes", "Must be positive")
        if (maxVideoDurationMs != null && maxVideoDurationMs <= 0) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.maxVideoDurationMs", "Must be positive")
        if (mimeTypes.any { !MIME_PATTERN.matches(it) }) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "picker.mimeTypes", "Use type/subtype or type/*")
        (ui.validate() as? ValidationResult.Invalid)?.let { issues += it.issues }
        return ValidationResult.of(issues)
    }

    /** [mimeType]이 [mimeTypes]에 맞으면 `true`. [mimeTypes]가 비어 있으면 늘 `true`. */
    public fun accepts(mimeType: String?): Boolean {
        if (mimeTypes.isEmpty()) return true
        val type = mimeType?.lowercase() ?: return false
        return mimeTypes.any { pattern ->
            val p = pattern.lowercase()
            if (p.endsWith("/*")) type.startsWith(p.dropLast(1)) else type == p
        }
    }

    private companion object {
        val MIME_PATTERN = Regex("^[a-zA-Z0-9.+-]+/([a-zA-Z0-9.+-]+|\\*)$")
    }
}

/**
 * 고른 사진·영상 하나.
 *
 * @property uri 읽을 수 있는 `content://` Uri. 앱 안 갤러리면 MediaStore Uri, 시스템 선택기면 선택기가 준 Uri다.
 * @property mimeType 형식. 알 수 없으면 `null`.
 * @property sizeBytes 파일 크기(byte). 알 수 없으면 `null`.
 * @property width 가로(px). 알 수 없으면 `null`.
 * @property height 세로(px). 알 수 없으면 `null`.
 * @property durationMs 영상 길이(ms). 사진이거나 알 수 없으면 `null`.
 */
@Parcelize
public data class PickedMedia(
    val uri: Uri,
    val mimeType: String? = null,
    val sizeBytes: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
) : Parcelable {
    /** 영상이면 `true`. 형식을 모르면 `false`. */
    public val isVideo: Boolean get() = mimeType?.startsWith("video/") == true
}

/** [MediaPickerContract]의 결과. */
public sealed interface MediaPickerResult {
    /** 고른 순서대로 담긴다. 비어 있지 않다. [skipped]는 제한에 맞지 않아 뺀 개수(시스템 선택기로 대신한 경우). */
    public data class Picked(val items: List<PickedMedia>, val skipped: Int = 0) : MediaPickerResult

    /** 사용자가 닫았거나, 고른 것이 모두 제한에 맞지 않았다. */
    public data object Cancelled : MediaPickerResult
}
