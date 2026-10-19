package com.naury.framekit.showcase

import android.os.Parcelable
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.naury.kinetica.ui.KAlert
import com.naury.kinetica.ui.KButton
import com.naury.kinetica.ui.KButtonSize
import com.naury.kinetica.ui.KChipGroup
import com.naury.kinetica.ui.KChipSelection
import com.naury.kinetica.ui.KHapticType
import com.naury.kinetica.ui.KSlider
import com.naury.kinetica.ui.KStatus
import com.naury.kinetica.ui.KSwitch
import com.naury.kinetica.ui.KVariant
import com.naury.kinetica.ui.jellyPress
import com.naury.kinetica.ui.kSelectable
import com.naury.kinetica.ui.rememberKHaptics
import com.naury.kinetica.ui.shake
import java.util.Locale
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

@Composable
fun PlaygroundScreen(onBack: () -> Unit, onLaunch: (FrameKitRequest) -> Unit) {
    var settings by rememberSaveable { mutableStateOf(PlaygroundSettings()) }
    val request = settings.toRequest()
    val issues = (request.validate() as? ValidationResult.Invalid)?.issues.orEmpty()
    // 프라이머리 색이 패널과 너무 비슷해 선택 표시가 안 보이는지 미리 알려 준다. 위 글자색은 편집기가 자동으로 고른다.
    val darkBase = EditorPalette(0xFF0D0D0E.toInt(), 0xFF171719.toInt(), null, 0xFFFFFFFF.toInt(), 0xFFA1A1AA.toInt(), null, null)
    val contrast = EditorPalette().contrastWarnings(darkBase, settings.primaryArgb)
    var rejectedLaunches by remember { mutableIntStateOf(0) }
    val haptics = rememberKHaptics()

    val listState = rememberLazyListState()
    // 목록이 상단 바 아래로 지나가도록 바 높이와 내비게이션 바는 목록 여백으로 받는다.
    val navigationBar = WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom).asPaddingValues().calculateBottomPadding()
    BlurBarScaffold(stringResource(R.string.playground_title), listState, onBack) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)),
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = padding.calculateTopPadding() + 4.dp, bottom = navigationBar + 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth()) {
                    Text(stringResource(R.string.playground_description), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            section(R.string.playground_media) {
                Chips(listOf(MediaKind.ANY to R.string.playground_any, MediaKind.IMAGE to R.string.home_edit_photo, MediaKind.VIDEO to R.string.home_edit_video), settings.kind) {
                    settings = settings.copy(kind = it)
                }
            }
            section(R.string.playground_image_tools) {
                MultiChips(ImageTool.entries, settings.imageTools) { settings = settings.copy(imageTools = it) }
            }
            section(R.string.playground_video_tools) {
                MultiChips(VideoTool.entries, settings.videoTools) { settings = settings.copy(videoTools = it) }
            }
            section(R.string.playground_history) {
                SwitchRow(R.string.playground_undo, settings.allowUndo) { settings = settings.copy(allowUndo = it) }
                SwitchRow(R.string.playground_redo, settings.allowRedo) { settings = settings.copy(allowRedo = it) }
            }
            section(R.string.playground_theme) {
                Chips(listOf(ThemeMode.DARK to R.string.playground_dark, ThemeMode.LIGHT to R.string.playground_light, ThemeMode.SYSTEM to R.string.playground_system), settings.themeMode) {
                    settings = settings.copy(themeMode = it)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp).selectableGroup()) {
                    PRIMARY_COLORS.forEach { argb ->
                        val selected = argb == settings.primaryArgb
                        Box(
                            Modifier
                                .jellyPress()
                                .size(36.dp)
                                .background(Color(argb), CircleShape)
                                .border(if (selected) 3.dp else 0.dp, if (selected) MaterialTheme.colorScheme.onBackground else Color.Transparent, CircleShape)
                                .kSelectable(selected, CircleShape, role = Role.RadioButton) { settings = settings.copy(primaryArgb = argb) }
                                .semantics { contentDescription = String.format(Locale.ROOT, "#%06X", argb and 0xFFFFFF) },
                        )
                    }
                }
                if (contrast.isNotEmpty()) {
                    Text(stringResource(R.string.playground_contrast_warning), color = Color(0xFFFFB020), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
                }
                LabeledSlider(stringResource(R.string.playground_corner, settings.cornerRadiusDp), settings.cornerRadiusDp.toFloat(), 0f..40f) {
                    settings = settings.copy(cornerRadiusDp = it.roundToInt())
                }
                // 언어 이름은 각 언어 자체 표기로 보여 준다(日本語, Tiếng Việt 등). 시스템은 기기 언어를 따른다.
                Chips(
                    listOf<Pair<String?, Int>>(null to R.string.playground_system) + EDITOR_LANGUAGES.map { it to R.string.playground_system },
                    settings.localeTag,
                    label = { tag -> tag?.let { languageName(it) } },
                ) {
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
                        KAlert(
                            issues.joinToString("\n") { "${it.path}: ${it.message}" },
                            Modifier.fillMaxWidth().shake(rejectedLaunches),
                            status = KStatus.Error,
                            title = stringResource(R.string.playground_invalid),
                        )
                    }
                    // 잘못된 조합이어도 버튼은 눌리게 두고, 누르면 무엇이 문제인지 흔들어 알려 준다.
                    KButton(
                        stringResource(R.string.playground_launch),
                        onClick = {
                            if (issues.isEmpty()) {
                                onLaunch(request)
                            } else {
                                rejectedLaunches++
                                haptics.perform(KHapticType.Error)
                            }
                        },
                        size = KButtonSize.Large,
                        fullWidth = true,
                    )
                    KButton(stringResource(R.string.playground_reset), onClick = { settings = PlaygroundSettings() }, variant = KVariant.Text)
                }
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

/** 하나만 고르는 칩 묶음. 고른 칩을 다시 눌러도 선택은 남는다. */
@Composable
private fun <T> Chips(options: List<Pair<T, Int>>, selected: T, label: @Composable (T) -> String? = { null }, onSelect: (T) -> Unit) {
    val labels = options.map { (value, res) -> label(value) ?: stringResource(res) }
    KChipGroup(
        chips = labels,
        selected = setOf(options.indexOfFirst { it.first == selected }),
        onSelectedChange = { picked -> picked.firstOrNull()?.let { onSelect(options[it].first) } },
    )
}

/** 여러 개를 켜고 끄는 도구 칩 묶음. */
@Composable
private fun <T : Enum<T>> MultiChips(all: List<T>, enabled: Set<T>, onChange: (Set<T>) -> Unit) {
    KChipGroup(
        chips = all.map { it.name.lowercase() },
        selected = all.indices.filterTo(mutableSetOf()) { all[it] in enabled },
        onSelectedChange = { picked -> onChange(picked.mapTo(mutableSetOf()) { all[it] }) },
        selection = KChipSelection.Multiple,
    )
}

@Composable
private fun SwitchRow(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
        KSwitch(checked, onChange)
    }
}

@Composable
private fun LabeledSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    Column {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        KSlider(value, onChange, Modifier.fillMaxWidth(), valueRange = range)
    }
}

// 편집기가 문구를 제공하는 언어(BCP 47).
private val EDITOR_LANGUAGES = listOf("ko", "en", "ja", "zh-CN", "zh-TW", "vi", "th", "id", "ru", "es", "pt-BR", "fr", "de")

private fun languageName(tag: String): String {
    val locale = java.util.Locale.forLanguageTag(tag)
    return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
}
