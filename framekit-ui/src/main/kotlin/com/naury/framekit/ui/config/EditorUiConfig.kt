package com.naury.framekit.ui.config

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/** Color scheme selection for the editor. */
public enum class ThemeMode {
    DARK,
    LIGHT,

    /** Follows the device setting. */
    SYSTEM,
}

/**
 * Appearance of the built-in editor.
 *
 * Only primitives are stored so the config can travel inside the launch Intent and survive
 * recreation. Compose colors are created inside the editor.
 *
 * @property accentArgb accent color as ARGB, or `null` for the default `#635BFF`.
 * @property cornerRadiusDp corner radius of panels and chips, `0..32`.
 * @property showExportProgress whether the export overlay shows the current stage.
 * @property enableHaptics whether snapping and selection play a short haptic. The device setting is
 *   still respected when this is `true`.
 * @property localeTag BCP 47 language tag such as `ko` or `en`, or `null` for the device locale.
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
