package com.naury.framekit.ui.tool

import androidx.annotation.StringRes
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.ui.R

@StringRes
public fun AdjustmentKind.labelRes(): Int = when (this) {
    AdjustmentKind.BRIGHTNESS -> R.string.framekit_adjust_brightness
    AdjustmentKind.EXPOSURE -> R.string.framekit_adjust_exposure
    AdjustmentKind.CONTRAST -> R.string.framekit_adjust_contrast
    AdjustmentKind.HIGHLIGHTS -> R.string.framekit_adjust_highlights
    AdjustmentKind.SHADOWS -> R.string.framekit_adjust_shadows
    AdjustmentKind.SATURATION -> R.string.framekit_adjust_saturation
    AdjustmentKind.TEMPERATURE -> R.string.framekit_adjust_temperature
    AdjustmentKind.TINT -> R.string.framekit_adjust_tint
    AdjustmentKind.SHARPNESS -> R.string.framekit_adjust_sharpness
    AdjustmentKind.FADE -> R.string.framekit_adjust_fade
    AdjustmentKind.VIGNETTE -> R.string.framekit_adjust_vignette
    AdjustmentKind.GRAIN -> R.string.framekit_adjust_grain
}

// 카탈로그에 없는 id(호스트가 추가할 사용자 정의 프리셋 등)는 id를 그대로 보여 준다.
@StringRes
public fun filterLabelRes(presetId: String): Int? = when (presetId) {
    "original" -> R.string.framekit_filter_original
    "bright" -> R.string.framekit_filter_bright
    "soft" -> R.string.framekit_filter_soft
    "lovely" -> R.string.framekit_filter_lovely
    "dramatic" -> R.string.framekit_filter_dramatic
    "clean" -> R.string.framekit_filter_clean
    "vivid" -> R.string.framekit_filter_vivid
    "warm" -> R.string.framekit_filter_warm
    "cool" -> R.string.framekit_filter_cool
    "film01" -> R.string.framekit_filter_film01
    "film02" -> R.string.framekit_filter_film02
    "film03" -> R.string.framekit_filter_film03
    "mono" -> R.string.framekit_filter_mono
    "fade" -> R.string.framekit_filter_fade
    "vintage" -> R.string.framekit_filter_vintage
    "cinema" -> R.string.framekit_filter_cinema
    else -> null
}
