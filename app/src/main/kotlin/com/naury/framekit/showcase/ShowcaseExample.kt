package com.naury.framekit.showcase

import androidx.annotation.StringRes
import com.naury.framekit.FrameKitRequest
import com.naury.framekit.android.catalog.CatalogFile
import com.naury.framekit.android.catalog.CustomFilter
import com.naury.framekit.android.catalog.CustomSticker
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.export.VideoExportConfig

/** 홈 화면의 예제. 각 예제는 실제 요청으로 FrameKit을 실행한다. */
enum class ShowcaseExample(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val request: FrameKitRequest,
) {
    DEFAULT(
        R.string.example_default_title,
        R.string.example_default_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.IMAGE)),
    ),
    VIDEO(
        R.string.example_video_title,
        R.string.example_video_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.VIDEO), video = VideoEditorConfig(maxClipCount = 10)),
    ),
    ANY(
        R.string.example_any_title,
        R.string.example_any_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.ANY)),
    ),
    SHORT_CLIP(
        R.string.example_short_clip_title,
        R.string.example_short_clip_description,
        FrameKitRequest(
            EditorInput.Pick(MediaKind.VIDEO),
            video = VideoEditorConfig(
                enabledTools = setOf(VideoTool.TRIM, VideoTool.SPEED, VideoTool.AUDIO, VideoTool.FILTER),
                maxTimelineDurationUs = 15_000_000,
            ),
            videoExport = VideoExportConfig(maxShortSide = 720),
        ),
    ),
    VIDEO_MOSAIC(
        R.string.example_video_mosaic_title,
        R.string.example_video_mosaic_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.VIDEO), video = VideoEditorConfig(enabledTools = setOf(VideoTool.PRIVACY, VideoTool.TRIM))),
    ),
    ROTATE_ONLY(
        R.string.example_rotate_only_title,
        R.string.example_rotate_only_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.IMAGE), image = ImageEditorConfig(enabledTools = setOf(ImageTool.ROTATE))),
    ),
    PNG(
        R.string.example_png_title,
        R.string.example_png_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.IMAGE), imageExport = ImageExportConfig(format = ImageFormat.PNG)),
    ),
    CUTOUT(
        R.string.example_cutout_title,
        R.string.example_cutout_description,
        FrameKitRequest(
            EditorInput.Pick(MediaKind.IMAGE),
            image = ImageEditorConfig(enabledTools = setOf(ImageTool.CUTOUT, ImageTool.CROP, ImageTool.STICKER)),
            imageExport = ImageExportConfig(format = ImageFormat.PNG),
        ),
    ),
    RESTRICTED(
        R.string.example_restricted_title,
        R.string.example_restricted_description,
        FrameKitRequest(
            EditorInput.Pick(MediaKind.IMAGE),
            image = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP), allowUndo = false, allowRedo = false),
            imageExport = ImageExportConfig(maxWidth = 1080, maxHeight = 1080, quality = 85),
        ),
    ),
    HOST_CATALOG(
        R.string.example_catalog_title,
        R.string.example_catalog_description,
        FrameKitRequest(
            EditorInput.Pick(MediaKind.ANY),
            catalog = EditorCatalog(
                filters = listOf(
                    CustomFilter("brand-sunset", "Sunset", temperature = 0.45, saturation = 0.15, highlightTint = listOf(0.04, 0.01, -0.02), fade = 0.08),
                    CustomFilter("brand-ocean", "Ocean", temperature = -0.4, contrast = 0.1, shadowTint = listOf(0.0, 0.02, 0.05)),
                ),
                stickers = listOf(
                    CustomSticker("flower", "Flower", CatalogFile.Asset("stickers/flower.png")),
                    CustomSticker("heart", "Heart", CatalogFile.Asset("stickers/heart.png")),
                ),
            ),
        ),
    ),
    CUSTOM_BRAND(
        R.string.example_brand_title,
        R.string.example_brand_description,
        FrameKitRequest(
            EditorInput.Pick(MediaKind.IMAGE),
            ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, accentArgb = 0xFF1E6BFF.toInt(), cornerRadiusDp = 22),
        ),
    ),
    ENGLISH(
        R.string.example_english_title,
        R.string.example_english_description,
        FrameKitRequest(EditorInput.Pick(MediaKind.IMAGE), ui = EditorUiConfig(localeTag = "en")),
    ),
}
