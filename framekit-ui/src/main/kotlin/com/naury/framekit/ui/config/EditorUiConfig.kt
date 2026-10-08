package com.naury.framekit.ui.config

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/** 에디터의 색 구성 선택. */
public enum class ThemeMode {
    DARK,
    LIGHT,

    /** 기기 설정을 따른다. */
    SYSTEM,
}

/**
 * 내장 에디터의 외관.
 *
 * 설정이 실행 Intent에 담겨 전달되고 재생성 후에도 유지되도록 원시 타입만 저장한다.
 * Compose 색상은 에디터 안에서 만든다.
 *
 * @property primaryArgb 편집기의 프라이머리(브랜드) 색, ARGB. 저장 버튼, 선택된 칩·도구, 슬라이더, 자르기 핸들,
 *   체크 표시 등 강조가 필요한 곳에 쓰인다. `null`이면 기본값 `#635BFF`. 이 색 위의 글자색은
 *   [EditorPalette.onPrimaryArgb]를 따로 주지 않으면 흰색·검정 중 더 잘 보이는 쪽을 자동으로 고른다.
 * @property cornerRadiusDp 패널과 칩의 모서리 반경, `0..32`.
 * @property showExportProgress 내보내기 오버레이에 현재 단계를 표시할지 여부.
 * @property enableHaptics 스냅과 선택 시 짧은 햅틱을 재생할지 여부. `true`여도 기기 설정은
 *   그대로 존중한다.
 * @property localeTag `ko`, `en`, `ja`, `zh-CN`, `zh-TW`, `vi`, `th`, `id`, `ru`, `es`, `pt-BR`, `fr`, `de` 같은 BCP 47 언어 태그.
 *   `null`이면 기기 로케일. 제공하지 않는 언어는 영어로 보인다.
 * @property palette 배경·패널·글자 색을 바꾸는 값. `null`이면 테마 기본 색.
 * @property uiFontId 편집기 문구에 쓸 폰트. 요청 카탈로그에 등록한 폰트 id이며, `null`이면 시스템 폰트.
 */
@Parcelize
public data class EditorUiConfig(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val primaryArgb: Int? = null,
    val cornerRadiusDp: Int = 14,
    val showExportProgress: Boolean = true,
    val enableHaptics: Boolean = true,
    val localeTag: String? = null,
    val palette: EditorPalette? = null,
    val uiFontId: String? = null,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (cornerRadiusDp !in 0..MAX_CORNER_RADIUS_DP) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "ui.cornerRadiusDp", "Expected 0..$MAX_CORNER_RADIUS_DP")
        }
        if (uiFontId != null && uiFontId.isBlank()) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "ui.uiFontId", "Use null for the system font")
        }
        if (localeTag != null && localeTag.isBlank()) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "ui.localeTag", "Use null for the device locale")
        }
        return ValidationResult.of(issues)
    }

    private companion object {
        const val MAX_CORNER_RADIUS_DP = 32
    }
}
