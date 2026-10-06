package com.naury.framekit.showcase

import androidx.annotation.StringRes
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageEditorRequest
import com.naury.framekit.ui.image.contract.ImageTool

/** Examples on the home screen. Each one launches the editor with a real request. */
enum class ShowcaseExample(
    @StringRes val title: Int,
    @StringRes val description: Int,
    val request: ImageEditorRequest,
) {
    DEFAULT(
        R.string.example_default_title,
        R.string.example_default_description,
        ImageEditorRequest(EditorInput.Pick()),
    ),
    ROTATE_ONLY(
        R.string.example_rotate_only_title,
        R.string.example_rotate_only_description,
        ImageEditorRequest(EditorInput.Pick(), config = ImageEditorConfig(enabledTools = setOf(ImageTool.ROTATE))),
    ),
    PNG(
        R.string.example_png_title,
        R.string.example_png_description,
        ImageEditorRequest(EditorInput.Pick(), export = ImageExportConfig(format = ImageFormat.PNG)),
    ),
    RESTRICTED(
        R.string.example_restricted_title,
        R.string.example_restricted_description,
        ImageEditorRequest(
            EditorInput.Pick(),
            config = ImageEditorConfig(enabledTools = setOf(ImageTool.CROP), allowUndo = false, allowRedo = false),
            export = ImageExportConfig(maxWidth = 1080, maxHeight = 1080, quality = 85),
        ),
    ),
    CUSTOM_BRAND(
        R.string.example_brand_title,
        R.string.example_brand_description,
        ImageEditorRequest(
            EditorInput.Pick(),
            ui = EditorUiConfig(themeMode = ThemeMode.LIGHT, accentArgb = 0xFF1E6BFF.toInt(), cornerRadiusDp = 22),
        ),
    ),
    ENGLISH(
        R.string.example_english_title,
        R.string.example_english_description,
        ImageEditorRequest(EditorInput.Pick(), ui = EditorUiConfig(localeTag = "en")),
    ),
}
