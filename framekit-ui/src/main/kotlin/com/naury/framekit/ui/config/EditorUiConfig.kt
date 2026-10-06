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
 * @property accentArgb ARGB 강조 색상. `null`이면 기본값 `#635BFF`.
 * @property cornerRadiusDp 패널과 칩의 모서리 반경, `0..32`.
 * @property showExportProgress 내보내기 오버레이에 현재 단계를 표시할지 여부.
 * @property enableHaptics 스냅과 선택 시 짧은 햅틱을 재생할지 여부. `true`여도 기기 설정은
 *   그대로 존중한다.
 * @property localeTag `ko`, `en` 같은 BCP 47 언어 태그. `null`이면 기기 로케일.
 */
@Parcelize
public data class EditorUiConfig(
    val themeMode: ThemeMode = ThemeMode.DARK,
    val accentArgb: Int? = null,
    val cornerRadiusDp: Int = 14,
    val showExportProgress: Boolean = true,
    val enableHaptics: Boolean = true,
    val localeTag: String? = null,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (cornerRadiusDp !in 0..MAX_CORNER_RADIUS_DP) {
            issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "ui.cornerRadiusDp", "Expected 0..$MAX_CORNER_RADIUS_DP")
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
