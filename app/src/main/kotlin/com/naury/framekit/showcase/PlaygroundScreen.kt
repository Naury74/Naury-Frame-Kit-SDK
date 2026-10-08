package com.naury.framekit.showcase

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.naury.framekit.FrameKitRequest
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.ui.config.EditorPalette
import com.naury.framekit.ui.config.EditorUiConfig
import com.naury.framekit.ui.config.ThemeMode
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.image.contract.ImageTool
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import com.naury.framekit.ui.video.contract.VideoTool
import com.naury.framekit.video.export.VideoExportConfig
import kotlinx.parcelize.Parcelize
import kotlin.math.roundToInt

/**
 * Playground에서 고르는 값. 화면이 다시 만들어져도 유지되도록 Parcelable로 둔다.
 * [toRequest]가 실제 SDK 요청을 만들고, 잘못된 조합은 SDK 검증 결과로 그대로 보여 준다.
 */
@Parcelize
data class PlaygroundSettings(
    val kind: MediaKind = MediaKind.ANY,
    val imageTools: Set<ImageTool> = ImageTool.defaults,
    val videoTools: Set<VideoTool> = VideoTool.defaults,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val primaryArgb: Int = 0xFF635BFF.toInt(),
    val cornerRadiusDp: Int = 14,
    val localeTag: String? = null,
    val showProgress: Boolean = true,
    val haptics: Boolean = true,
    val imageFormat: ImageFormat = ImageFormat.JPEG,
    val quality: Int = 92,
    val imageMaxSide: Int? = null,
    val videoShortSide: Int = 1080,
    val videoFps: Int = 30,
    val maxDurationSec: Int = 300,
    val maxClips: Int = 10,
) : Parcelable {

    fun toRequest(): FrameKitRequest = FrameKitRequest(
        input = EditorInput.Pick(kind),
        image = ImageEditorConfig(enabledTools = imageTools, allowUndo = allowUndo, allowRedo = allowRedo),
        imageExport = ImageExportConfig(format = imageFormat, quality = quality, maxWidth = imageMaxSide, maxHeight = imageMaxSide),
        video = VideoEditorConfig(
            enabledTools = videoTools,
            allowUndo = allowUndo,
            allowRedo = allowRedo,
            maxTimelineDurationUs = maxDurationSec * 1_000_000L,
            maxClipCount = maxClips,
        ),
        videoExport = VideoExportConfig(maxShortSide = videoShortSide, maxFrameRate = videoFps),
        ui = EditorUiConfig(
            themeMode = themeMode,
            primaryArgb = primaryArgb,
            cornerRadiusDp = cornerRadiusDp,
            showExportProgress = showProgress,
            enableHaptics = haptics,
            localeTag = localeTag,
        ),
    )
}

private val PRIMARY_COLORS = listOf(0xFF635BFF, 0xFF1E6BFF, 0xFF12A150, 0xFFFF5A36, 0xFFFFD43B).map { it.toInt() }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaygroundScreen(onBack: () -> Unit, onLaunch: (FrameKitRequest) -> Unit) {
    var settings by rememberSaveable { mutableStateOf(PlaygroundSettings()) }
    val request = settings.toRequest()
    val issues = (request.validate() as? ValidationResult.Invalid)?.issues.orEmpty()
    // 프라이머리 색이 패널과 너무 비슷해 선택 표시가 안 보이는지 미리 알려 준다. 위 글자색은 편집기가 자동으로 고른다.
    val darkBase = EditorPalette(0xFF0D0D0E.toInt(), 0xFF171719.toInt(), null, 0xFFFFFFFF.toInt(), 0xFFA1A1AA.toInt(), null, null)
    val contrast = EditorPalette().contrastWarnings(darkBase, settings.primaryArgb)

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                TextButton(onClick = onBack) { Text(stringResource(R.string.result_back)) }
                Text(stringResource(R.string.playground_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.playground_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        section(R.string.playground_media) {
            Chips(listOf(MediaKind.ANY to R.string.playground_any, MediaKind.IMAGE to R.string.home_edit_photo, MediaKind.VIDEO to R.string.home_edit_video), settings.kind) {
                settings = settings.copy(kind = it)
            }
        }
        section(R.string.playground_image_tools) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ImageTool.entries.forEach { tool ->
                    Toggle(tool.name.lowercase(), tool in settings.imageTools) { on ->
                        settings = settings.copy(imageTools = if (on) settings.imageTools + tool else settings.imageTools - tool)
                    }
                }
            }
        }
        section(R.string.playground_video_tools) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                VideoTool.entries.forEach { tool ->
                    Toggle(tool.name.lowercase(), tool in settings.videoTools) { on ->
                        settings = settings.copy(videoTools = if (on) settings.videoTools + tool else settings.videoTools - tool)
                    }
                }
            }
        }
        section(R.string.playground_history) {
            SwitchRow(R.string.playground_undo, settings.allowUndo) { settings = settings.copy(allowUndo = it) }
            SwitchRow(R.string.playground_redo, settings.allowRedo) { settings = settings.copy(allowRedo = it) }
        }
        section(R.string.playground_theme) {
            Chips(listOf(ThemeMode.DARK to R.string.playground_dark, ThemeMode.LIGHT to R.string.playground_light, ThemeMode.SYSTEM to R.string.playground_system), settings.themeMode) {
                settings = settings.copy(themeMode = it)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                PRIMARY_COLORS.forEach { argb ->
                    val selected = argb == settings.primaryArgb
                    Column(
                        Modifier
                            .size(36.dp)
                            .background(Color(argb), CircleShape)
                            .border(if (selected) 3.dp else 0.dp, if (selected) Color.White else Color.Transparent, CircleShape)
                            .clickable { settings = settings.copy(primaryArgb = argb) },
                    ) {}
                }
            }
            if (contrast.isNotEmpty()) {
                Text(stringResource(R.string.playground_contrast_warning), color = Color(0xFFFFB020), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
            }
            LabeledSlider(stringResource(R.string.playground_corner, settings.cornerRadiusDp), settings.cornerRadiusDp.toFloat(), 0f..40f) {
                settings = settings.copy(cornerRadiusDp = it.roundToInt())
            }
            Chips(listOf<Pair<String?, Int>>(null to R.string.playground_system, "ko" to R.string.playground_korean, "en" to R.string.playground_english), settings.localeTag) {
                settings = settings.copy(localeTag = it)
            }
            SwitchRow(R.string.playground_progress, settings.showProgress) { settings = settings.copy(showProgress = it) }
            SwitchRow(R.string.playground_haptics, settings.haptics) { settings = settings.copy(haptics = it) }
        }
        section(R.string.playground_image_export) {
            Chips(ImageFormat.entries.map { it to 0 }, settings.imageFormat, label = { it.name }) { settings = settings.copy(imageFormat = it) }
            LabeledSlider(stringResource(R.string.playground_quality, settings.quality), settings.quality.toFloat(), 0f..100f) {
                settings = settings.copy(quality = it.roundToInt())
            }
            Chips(listOf<Pair<Int?, Int>>(null to R.string.playground_original_size, 1080 to 0, 2048 to 0), settings.imageMaxSide, label = { it?.let { side -> "$side px" } }) {
                settings = settings.copy(imageMaxSide = it)
            }
        }
        section(R.string.playground_video_export) {
            Chips(listOf(480, 720, 1080, 2160).map { it to 0 }, settings.videoShortSide, label = { "${it}p" }) { settings = settings.copy(videoShortSide = it) }
            Chips(listOf(24, 30, 60).map { it to 0 }, settings.videoFps, label = { "$it fps" }) { settings = settings.copy(videoFps = it) }
            Chips(listOf(15, 60, 300).map { it to 0 }, settings.maxDurationSec, label = { if (it < 60) "${it}s" else "${it / 60}min" }) {
                settings = settings.copy(maxDurationSec = it)
            }
            Chips(listOf(1, 5, 10, 20).map { it to 0 }, settings.maxClips, label = { stringResource(R.string.playground_clips, it) }) {
                settings = settings.copy(maxClips = it)
            }
        }
        item {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (issues.isNotEmpty()) {
                    Text(stringResource(R.string.playground_invalid), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleSmall)
                    issues.forEach { issue ->
                        Text("• ${issue.path}: ${issue.message}", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
                Button(onClick = { onLaunch(request) }, enabled = issues.isEmpty(), modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(stringResource(R.string.playground_launch))
                }
                TextButton(onClick = { settings = PlaygroundSettings() }) { Text(stringResource(R.string.playground_reset)) }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(@StringRes title: Int, content: @Composable () -> Unit) {
    item {
        Column(
            Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(14.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(stringResource(title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> Chips(options: List<Pair<T, Int>>, selected: T, label: @Composable (T) -> String? = { null }, onSelect: (T) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, res) ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value) ?: stringResource(res)) },
            )
        }
    }
}

@Composable
private fun Toggle(text: String, on: Boolean, onChange: (Boolean) -> Unit) {
    FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text(text) })
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}
