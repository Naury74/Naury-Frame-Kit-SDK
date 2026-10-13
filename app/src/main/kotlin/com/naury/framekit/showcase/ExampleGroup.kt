package com.naury.framekit.showcase

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.naury.framekit.ui.R as UiR

/** 홈 화면에서 예제를 묶는 단위. 나열 순서가 화면에 보이는 순서다. */
enum class ExampleGroup(@StringRes val title: Int, val accent: Color) {
    START(R.string.home_group_start, Color(0xFF5E5CE6)),
    PHOTO(R.string.home_group_photo, Color(0xFFFF9500)),
    VIDEO(R.string.home_group_video, Color(0xFF0A84FF)),
    DOCUMENT(R.string.home_group_document, Color(0xFF30B85A)),
    MOTION(R.string.home_group_motion, Color(0xFFFF2D55)),
    AUDIO(R.string.home_group_audio, Color(0xFFFF453A)),
    WATERMARK(R.string.home_group_watermark, Color(0xFF8E8E93)),
    CUSTOM(R.string.home_group_custom, Color(0xFF30A6C7)),
}

/** 예제가 속한 묶음. 빠른 시작 타일로만 보이는 예제는 `null`이다. */
val ShowcaseExample.group: ExampleGroup?
    get() = when (this) {
        ShowcaseExample.DEFAULT, ShowcaseExample.VIDEO -> null
        ShowcaseExample.CHOOSER, ShowcaseExample.HOST_ALBUM, ShowcaseExample.ANY, ShowcaseExample.MIXED_PICK,
        ShowcaseExample.CAMERA_PHOTO, ShowcaseExample.CAMERA_VIDEO -> ExampleGroup.START
        ShowcaseExample.MULTI_PHOTO, ShowcaseExample.CUTOUT, ShowcaseExample.ROTATE_ONLY, ShowcaseExample.PNG,
        ShowcaseExample.RESTRICTED -> ExampleGroup.PHOTO
        ShowcaseExample.SHORT_CLIP, ShowcaseExample.VIDEO_MOSAIC -> ExampleGroup.VIDEO
        ShowcaseExample.DOCUMENT_SCAN, ShowcaseExample.DOCUMENT_PDF -> ExampleGroup.DOCUMENT
        ShowcaseExample.MOTION_MAKE, ShowcaseExample.MOTION_EDIT -> ExampleGroup.MOTION
        ShowcaseExample.AUDIO_RECORD, ShowcaseExample.AUDIO_HOLD, ShowcaseExample.AUDIO_FILE -> ExampleGroup.AUDIO
        ShowcaseExample.WATERMARK, ShowcaseExample.WATERMARK_LOCKED -> ExampleGroup.WATERMARK
        ShowcaseExample.HOST_CATALOG, ShowcaseExample.CUSTOM_BRAND, ShowcaseExample.ENGLISH -> ExampleGroup.CUSTOM
    }

/** 예제 줄에 쓰는 아이콘. 같은 묶음 안에서도 무엇을 하는 예제인지 구별되게 고른다. */
@get:DrawableRes
val ShowcaseExample.icon: Int
    get() = when (this) {
        ShowcaseExample.CHOOSER -> R.drawable.ic_help
        ShowcaseExample.HOST_ALBUM -> R.drawable.ic_photo_library
        ShowcaseExample.ANY, ShowcaseExample.PNG, ShowcaseExample.DEFAULT -> R.drawable.ic_image
        ShowcaseExample.MIXED_PICK, ShowcaseExample.MULTI_PHOTO -> UiR.drawable.framekit_ic_duplicate
        ShowcaseExample.CAMERA_PHOTO, ShowcaseExample.DOCUMENT_SCAN -> R.drawable.ic_camera
        ShowcaseExample.CAMERA_VIDEO, ShowcaseExample.VIDEO -> R.drawable.ic_videocam
        ShowcaseExample.CUTOUT -> UiR.drawable.framekit_ic_cutout
        ShowcaseExample.ROTATE_ONLY -> UiR.drawable.framekit_ic_rotate_right
        ShowcaseExample.RESTRICTED -> UiR.drawable.framekit_ic_crop
        ShowcaseExample.SHORT_CLIP -> UiR.drawable.framekit_ic_trim
        ShowcaseExample.VIDEO_MOSAIC, ShowcaseExample.WATERMARK_LOCKED -> UiR.drawable.framekit_ic_privacy
        ShowcaseExample.DOCUMENT_PDF -> UiR.drawable.framekit_ic_document
        ShowcaseExample.MOTION_MAKE -> UiR.drawable.framekit_ic_play
        ShowcaseExample.MOTION_EDIT -> UiR.drawable.framekit_ic_speed
        ShowcaseExample.AUDIO_RECORD, ShowcaseExample.AUDIO_HOLD -> UiR.drawable.framekit_ic_mic
        ShowcaseExample.AUDIO_FILE -> UiR.drawable.framekit_ic_volume
        ShowcaseExample.WATERMARK -> UiR.drawable.framekit_ic_watermark
        ShowcaseExample.HOST_CATALOG -> UiR.drawable.framekit_ic_sticker
        ShowcaseExample.CUSTOM_BRAND -> UiR.drawable.framekit_ic_filter
        ShowcaseExample.ENGLISH -> UiR.drawable.framekit_ic_text
    }
