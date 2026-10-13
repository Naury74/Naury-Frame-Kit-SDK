package com.naury.framekit.ui.motion.editor

import kotlin.math.roundToInt
import com.naury.framekit.motion.model.MotionAudio
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.ui.picker.MediaPickerContract
import com.naury.framekit.ui.picker.MediaPickerRequest
import com.naury.framekit.ui.picker.MediaPickerResult
import com.naury.framekit.ui.audio.VoiceRecordDialog
import com.naury.framekit.ui.audio.VoiceRecorder
import com.naury.framekit.ui.audio.AudioTrackUi
import com.naury.framekit.ui.audio.AudioTrackSection
import com.naury.framekit.core.video.AudioRole
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalContext
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.naury.framekit.motion.export.MotionExportConfig
import com.naury.framekit.motion.export.MotionFormat
import com.naury.framekit.motion.export.MotionSize
import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.FitMode
import com.naury.framekit.motion.model.LoopMode
import com.naury.framekit.motion.model.MotionAspect
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionOverlay
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.MotionTransition
import com.naury.framekit.motion.model.OverlayAnimation
import com.naury.framekit.ui.component.ChoiceChips
import com.naury.framekit.ui.component.DialogAction
import com.naury.framekit.ui.component.DialogActionStyle
import com.naury.framekit.ui.component.FrameKitDialog
import com.naury.framekit.ui.component.ValueSlider
import com.naury.framekit.ui.component.horizontalFadingEdges
import com.naury.framekit.ui.design.FrameKitTheme
import com.naury.framekit.ui.motion.R
import com.naury.framekit.ui.tool.ColorPalette
import java.util.Locale
import kotlin.math.roundToLong
import com.naury.framekit.ui.R as UiR

/** 사진마다 주는 카메라 움직임. */
@Composable
internal fun MotionPanel(project: MotionProject, onCommit: ((MotionProject) -> MotionProject) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Help(stringResource(R.string.framekit_motion_motion_help))
        ChoiceChips(
            options = listOf(CameraMotion.AUTO, CameraMotion.NONE, CameraMotion.ZOOM_IN, CameraMotion.ZOOM_OUT, CameraMotion.PAN_LEFT, CameraMotion.PAN_RIGHT),
            selected = project.cameraMotion,
            label = { stringResource(it.labelRes()) },
            onSelect = { motion -> onCommit { it.copy(cameraMotion = motion) } },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 사진 사이 전환과 전환 시간. */
@Composable
internal fun TransitionPanel(project: MotionProject, viewModel: MotionEditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = MotionTransition.entries,
            selected = project.transition,
            label = { stringResource(it.labelRes()) },
            onSelect = { transition -> viewModel.commit { it.copy(transition = transition) } },
        )
        if (project.transition != MotionTransition.NONE) {
            val max = (project.slideDurationMs / 2).coerceAtLeast(MIN_TRANSITION_MS + 100)
            ValueSlider(
                label = stringResource(R.string.framekit_motion_transition_length),
                value = project.transitionMs.coerceIn(MIN_TRANSITION_MS, max).toFloat(),
                valueRange = MIN_TRANSITION_MS.toFloat()..max.toFloat(),
                onValueChange = { value -> viewModel.drag { it.copy(transitionMs = (value / 50).roundToLong() * 50) } },
                onValueChangeFinished = viewModel::finishDrag,
                formatValue = { seconds(it.toLong()) },
                resetValue = MotionProject.DEFAULT_TRANSITION_MS.toFloat().coerceAtMost(max.toFloat()),
            )
        }
    }
}

/** 슬라이드쇼는 사진당 시간, 움직이는 원본은 구간과 속도. */
@Composable
internal fun TimingPanel(project: MotionProject, sourceDurationMs: Long, viewModel: MotionEditorViewModel) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        when (project.kind) {
            MotionKind.SLIDESHOW -> ValueSlider(
                label = stringResource(R.string.framekit_motion_slide_duration),
                value = project.slideDurationMs.toFloat(),
                valueRange = MotionProject.MIN_SLIDE_MS.toFloat()..MotionProject.MAX_SLIDE_MS.toFloat(),
                onValueChange = { value ->
                    viewModel.drag { p ->
                        val slide = (value / 100).roundToLong() * 100
                        p.copy(slideDurationMs = slide, transitionMs = p.transitionMs.coerceAtMost(slide / 2))
                    }
                },
                onValueChangeFinished = viewModel::finishDrag,
                formatValue = { seconds(it.toLong()) },
                resetValue = MotionProject.DEFAULT_SLIDE_MS.toFloat(),
            )
            MotionKind.ANIMATION -> {
                val end = project.trimEndMs ?: sourceDurationMs
                SectionTitle(stringResource(R.string.framekit_motion_speed))
                ChoiceChips(
                    options = SPEEDS,
                    selected = SPEEDS.minBy { kotlin.math.abs(it - project.speed) },
                    label = { String.format(Locale.ROOT, "%sx", it.toString().removeSuffix(".0")) },
                    onSelect = { speed -> viewModel.commit { it.copy(speed = speed) } },
                )
                ValueSlider(
                    label = stringResource(R.string.framekit_motion_trim_start),
                    value = project.trimStartMs.toFloat(),
                    valueRange = 0f..sourceDurationMs.toFloat().coerceAtLeast(1f),
                    onValueChange = { value -> viewModel.drag { it.copy(trimStartMs = value.toLong().coerceAtMost(end - MIN_TRIM_MS).coerceAtLeast(0)) } },
                    onValueChangeFinished = viewModel::finishDrag,
                    formatValue = { seconds(it.toLong()) },
                    resetValue = 0f,
                )
                ValueSlider(
                    label = stringResource(R.string.framekit_motion_trim_end),
                    value = end.toFloat(),
                    valueRange = 0f..sourceDurationMs.toFloat().coerceAtLeast(1f),
                    onValueChange = { value ->
                        viewModel.drag { p ->
                            val next = value.toLong().coerceAtLeast(p.trimStartMs + MIN_TRIM_MS).coerceAtMost(sourceDurationMs)
                            p.copy(trimEndMs = if (next >= sourceDurationMs) null else next)
                        }
                    },
                    onValueChangeFinished = viewModel::finishDrag,
                    formatValue = { seconds(it.toLong()) },
                    resetValue = sourceDurationMs.toFloat(),
                )
            }
        }
    }
}

/** 반복 방식. */
@Composable
internal fun LoopPanel(project: MotionProject, onCommit: ((MotionProject) -> MotionProject) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Help(stringResource(R.string.framekit_motion_loop_help))
        ChoiceChips(
            options = LoopMode.entries,
            selected = project.loop,
            label = { stringResource(if (it == LoopMode.FORWARD) R.string.framekit_motion_loop_forward else R.string.framekit_motion_loop_boomerang) },
            onSelect = { loop -> onCommit { it.copy(loop = loop) } },
            modifier = Modifier.padding(top = 8.dp),
        )
    }
}

/** 화면 비율, 채우기 방식, 여백 색. */
@Composable
internal fun CanvasPanel(project: MotionProject, onCommit: ((MotionProject) -> MotionProject) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        ChoiceChips(
            options = MotionAspect.entries,
            selected = project.aspect,
            label = { aspect ->
                when (aspect) {
                    MotionAspect.ORIGINAL -> stringResource(R.string.framekit_motion_aspect_original)
                    MotionAspect.SQUARE -> "1:1"
                    MotionAspect.PORTRAIT_4_5 -> "4:5"
                    MotionAspect.STORY_9_16 -> "9:16"
                    MotionAspect.WIDE_16_9 -> "16:9"
                }
            },
            onSelect = { aspect -> onCommit { it.copy(aspect = aspect) } },
        )
        ChoiceChips(
            options = FitMode.entries,
            selected = project.fit,
            label = { stringResource(if (it == FitMode.FILL) R.string.framekit_motion_fit_fill else R.string.framekit_motion_fit_fit) },
            onSelect = { fit -> onCommit { it.copy(fit = fit) } },
            modifier = Modifier.padding(top = 8.dp),
        )
        if (project.fit == FitMode.FIT) {
            SectionTitle(stringResource(R.string.framekit_motion_background))
            ColorPalette(selected = project.backgroundArgb, onSelect = { color -> onCommit { it.copy(backgroundArgb = color) } })
        }
    }
}

/** 배경 음악·음성 트랙. 녹음은 호스트가 녹음 권한을 선언했고 마이크가 있을 때만 보인다. */
@Composable
internal fun AudioPanel(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel) {
    val musicPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.addMusic(uri) }
    val voicePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> viewModel.addVoice(uri) }
    val context = LocalContext.current
    val canRecord = remember { VoiceRecorder.isDeclared(context) && VoiceRecorder.hasMicrophone(context) }
    var recording by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Help(stringResource(UiR.string.framekit_track_sound_mp4_only))
        val selected = state.displayed.audio.firstOrNull { it.id == state.selectedAudioId }
        if (selected != null) {
            SelectedAudioControls(selected, viewModel)
        } else {
            Help(stringResource(R.string.framekit_motion_audio_help))
            AudioRole.entries.forEach { role ->
                AudioTrackSection(
                    title = stringResource(if (role == AudioRole.MUSIC) UiR.string.framekit_track_music else UiR.string.framekit_track_voice),
                    addLabel = stringResource(if (role == AudioRole.MUSIC) UiR.string.framekit_track_music_add else UiR.string.framekit_track_voice_add),
                    track = null,
                    busy = state.busy,
                    onAddFile = { (if (role == AudioRole.MUSIC) musicPicker else voicePicker).launch(arrayOf("audio/*")) },
                    onRemove = {},
                    onVolume = {},
                    onLoop = {},
                    onStartHere = {},
                    onOffset = {},
                    onGestureFinished = {},
                    onRecord = if (role == AudioRole.VOICE && canRecord) ({ recording = true }) else null,
                )
            }
        }
    }
    if (recording) {
        VoiceRecordDialog(
            onRecorded = { file ->
                recording = false
                viewModel.addRecordedVoice(file)
            },
            onDismiss = { recording = false },
        )
    }
}

/** 고른 소리 조각: 나누기·반복·삭제와 볼륨·페이드. */
@Composable
private fun SelectedAudioControls(audio: MotionAudio, viewModel: MotionEditorViewModel) {
    val colors = FrameKitTheme.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(if (audio.role == AudioRole.MUSIC) UiR.string.framekit_track_music else UiR.string.framekit_track_voice),
            color = colors.foreground,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 12.dp).weight(1f),
        )
        SmallAction(painterResource(UiR.drawable.framekit_ic_trim), stringResource(R.string.framekit_motion_audio_split), colors.foreground, viewModel::splitSelectedAudio)
        SmallAction(rememberVectorPainter(Icons.Filled.Refresh), stringResource(UiR.string.framekit_track_loop), if (audio.loop) colors.accent else colors.foregroundMuted) { viewModel.setSelectedAudioLoop(!audio.loop) }
        SmallAction(rememberVectorPainter(Icons.Filled.Delete), stringResource(UiR.string.framekit_track_remove), colors.foreground, viewModel::deleteSelectedAudio)
        SmallAction(rememberVectorPainter(Icons.Filled.Close), stringResource(UiR.string.framekit_action_close), colors.foregroundMuted) { viewModel.selectAudio(null) }
    }
    ValueSlider(
        label = stringResource(UiR.string.framekit_track_volume),
        value = (audio.volume * 100).toFloat(),
        valueRange = 0f..200f,
        onValueChange = viewModel::changeSelectedAudioVolume,
        onValueChangeFinished = viewModel::finishDrag,
        formatValue = { "${it.roundToInt()}%" },
        resetValue = 100f,
        snapThreshold = 4f,
    )
    listOf(true, false).forEach { fadeIn ->
        ValueSlider(
            label = stringResource(if (fadeIn) R.string.framekit_motion_audio_fade_in else R.string.framekit_motion_audio_fade_out),
            value = (if (fadeIn) audio.fadeInMs else audio.fadeOutMs) / 1000f,
            valueRange = 0f..MAX_FADE_S,
            onValueChange = { seconds -> val ms = (seconds * 1000).toLong(); if (fadeIn) viewModel.changeSelectedAudioFade(ms, null) else viewModel.changeSelectedAudioFade(null, ms) },
            onValueChangeFinished = viewModel::finishDrag,
            formatValue = { String.format(Locale.ROOT, "%.1fs", it) },
            resetValue = 0f,
        )
    }
}

@Composable
private fun SmallAction(icon: androidx.compose.ui.graphics.painter.Painter, label: String, tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            Text(label, color = tint, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

/** 텍스트·스티커의 움직임 고르기. */
@Composable
internal fun OverlayAnimationChips(selected: OverlayAnimation, onSelect: (OverlayAnimation) -> Unit) {
    SectionTitle(stringResource(R.string.framekit_motion_overlay_animation))
    ChoiceChips(options = OverlayAnimation.entries, selected = selected, label = { stringResource(it.labelRes()) }, onSelect = onSelect)
}

/** 스티커 도구 위쪽: 고른 항목의 움직임과 삭제, 고르는 법 안내. */
@Composable
internal fun StickerSelection(selected: MotionOverlay?, viewModel: MotionEditorViewModel) {
    if (selected == null) {
        Help(stringResource(R.string.framekit_motion_sticker_help))
        return
    }
    OverlayAnimationChips(selected.animation, viewModel::setOverlayAnimation)
    OutlinedButton(
        onClick = viewModel::deleteSelectedOverlay,
        border = BorderStroke(1.dp, FrameKitTheme.colors.raised),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Icon(Icons.Filled.Delete, contentDescription = null, tint = FrameKitTheme.colors.error, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.framekit_motion_delete_item), color = FrameKitTheme.colors.foreground, modifier = Modifier.padding(start = 6.dp))
    }
}

/** 슬라이드쇼의 사진 목록. 눌러서 그 사진으로 옮기고, 추가·순서 바꾸기·빼기를 한다. */
@Composable
internal fun SlideStrip(state: MotionEditorUiState.Ready, viewModel: MotionEditorViewModel) {
    val colors = FrameKitTheme.colors
    val thumbnails by viewModel.thumbnails.collectAsStateWithLifecycle()
    val ids = state.displayed.sourceIds
    val ui = FrameKitTheme.config
    val picker = rememberLauncherForActivityResult(MediaPickerContract()) { result ->
        viewModel.addSlides((result as? MediaPickerResult.Picked)?.items?.map { it.uri }.orEmpty())
    }
    val enabled = state.export == null && !state.transaction.isActive
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.framekit_motion_slide_position, state.selectedSlide + 1, ids.size),
                color = colors.foregroundMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { viewModel.moveSlide(-1) }, enabled = enabled && state.selectedSlide > 0) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.framekit_motion_slide_earlier), tint = colors.foreground)
            }
            IconButton(onClick = { viewModel.moveSlide(1) }, enabled = enabled && state.selectedSlide < ids.lastIndex) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, stringResource(R.string.framekit_motion_slide_later), tint = colors.foreground)
            }
            IconButton(onClick = viewModel::removeSlide, enabled = enabled && ids.size > 1) {
                Icon(Icons.Filled.Delete, stringResource(R.string.framekit_motion_slide_remove), tint = colors.foreground)
            }
        }
        val scroll = rememberScrollState()
        Row(
            Modifier.fillMaxWidth().horizontalFadingEdges(scroll).horizontalScroll(scroll).padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ids.forEachIndexed { index, id ->
                SlideThumb(thumbnails[id], index, selected = index == state.selectedSlide) { viewModel.selectSlide(index) }
            }
            if (ids.size < viewModel.config.maxImageCount) {
                Box(
                    Modifier.size(THUMB_DP.dp).clip(RoundedCornerShape(10.dp)).background(colors.raised)
                        .clickable(enabled = enabled, role = Role.Button) { picker.launch(MediaPickerRequest(MediaKind.IMAGE, maxItems = maxOf(1, viewModel.config.maxImageCount - ids.size), style = ui.picker, ui = ui)) },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Filled.Add, stringResource(R.string.framekit_motion_slide_add), tint = colors.foreground) }
            }
        }
    }
}

@Composable
private fun SlideThumb(bitmap: Bitmap?, index: Int, selected: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    Box(
        Modifier
            .size(THUMB_DP.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(colors.raised)
            .border(if (selected) 2.dp else 0.dp, if (selected) colors.accent else colors.raised, RoundedCornerShape(10.dp))
            .semantics {
                this.selected = selected
                role = Role.Tab
            }
            .clickable(onClick = onClick),
    ) {
        if (bitmap != null && !bitmap.isRecycled) Image(bitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        Text(
            "${index + 1}",
            color = colors.onAccent,
            style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.align(Alignment.TopStart).padding(4.dp).background(colors.accent.copy(alpha = 0.85f), RoundedCornerShape(6.dp)).padding(horizontal = 4.dp),
        )
    }
}

/**
 * 저장 형식과 크기를 고르는 시트. 형식마다 장단점을 함께 보여 준다.
 */
@Composable
internal fun SaveSheet(config: MotionExportConfig, hasSound: Boolean, onSave: (MotionFormat, MotionSize) -> Unit, onDismiss: () -> Unit) {
    var format by remember { mutableStateOf(config.format) }
    var size by remember { mutableStateOf(config.size) }
    FrameKitDialog(
        title = stringResource(R.string.framekit_motion_save_title),
        onDismiss = onDismiss,
        actions = listOf(
            DialogAction(stringResource(UiR.string.framekit_action_save), DialogActionStyle.PRIMARY) { onSave(format, size) },
            DialogAction(stringResource(UiR.string.framekit_action_cancel), DialogActionStyle.SECONDARY, onClick = onDismiss),
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            config.formats.forEach { option ->
                FormatOption(option, selected = option == format) { format = option }
            }
            // 소리를 넣었는데 GIF·WebP를 고르면 소리가 빠진다는 것을 미리 알린다.
            if (hasSound && format != MotionFormat.MP4) {
                Text(
                    stringResource(UiR.string.framekit_track_sound_mp4_only),
                    color = FrameKitTheme.colors.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                stringResource(R.string.framekit_motion_size),
                color = FrameKitTheme.colors.foregroundMuted,
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MotionSize.entries.forEach { option ->
                    SizeOption(option, format, selected = option == size, modifier = Modifier.weight(1f)) { size = option }
                }
            }
        }
    }
}

@Composable
private fun FormatOption(format: MotionFormat, selected: Boolean, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val shape = RoundedCornerShape(14.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.raised, shape)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            stringResource(
                when (format) {
                    MotionFormat.MP4 -> R.string.framekit_motion_format_mp4
                    MotionFormat.GIF -> R.string.framekit_motion_format_gif
                    MotionFormat.WEBP -> R.string.framekit_motion_format_webp
                },
            ),
            color = colors.foreground,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            stringResource(
                when (format) {
                    MotionFormat.MP4 -> R.string.framekit_motion_format_mp4_help
                    MotionFormat.GIF -> R.string.framekit_motion_format_gif_help
                    MotionFormat.WEBP -> R.string.framekit_motion_format_webp_help
                },
            ),
            color = colors.foregroundMuted,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

@Composable
private fun SizeOption(size: MotionSize, format: MotionFormat, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = FrameKitTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier
            .clip(shape)
            .background(if (selected) colors.accent.copy(alpha = 0.14f) else colors.background)
            .border(if (selected) 2.dp else 1.dp, if (selected) colors.accent else colors.raised, shape)
            .semantics {
                this.selected = selected
                role = Role.RadioButton
            }
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(
                when (size) {
                    MotionSize.SMALL -> R.string.framekit_motion_size_small
                    MotionSize.MEDIUM -> R.string.framekit_motion_size_medium
                    MotionSize.LARGE -> R.string.framekit_motion_size_large
                },
            ),
            color = colors.foreground,
            style = MaterialTheme.typography.labelLarge,
        )
        Text(stringResource(R.string.framekit_motion_size_px, size.longEdge(format)), color = colors.foregroundMuted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun Help(text: String) {
    Text(text, color = FrameKitTheme.colors.foregroundMuted, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp))
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = FrameKitTheme.colors.foregroundMuted,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = 2.dp),
    )
}

private fun CameraMotion.labelRes(): Int = when (this) {
    CameraMotion.NONE -> R.string.framekit_motion_none
    CameraMotion.AUTO -> R.string.framekit_motion_auto
    CameraMotion.ZOOM_IN -> R.string.framekit_motion_zoom_in
    CameraMotion.ZOOM_OUT -> R.string.framekit_motion_zoom_out
    CameraMotion.PAN_LEFT -> R.string.framekit_motion_pan_left
    CameraMotion.PAN_RIGHT -> R.string.framekit_motion_pan_right
}

private fun MotionTransition.labelRes(): Int = when (this) {
    MotionTransition.NONE -> R.string.framekit_motion_none
    MotionTransition.FADE -> R.string.framekit_motion_transition_fade
    MotionTransition.SLIDE -> R.string.framekit_motion_transition_slide
    MotionTransition.ZOOM -> R.string.framekit_motion_transition_zoom
}

private fun OverlayAnimation.labelRes(): Int = when (this) {
    OverlayAnimation.NONE -> R.string.framekit_motion_none
    OverlayAnimation.FADE_IN -> R.string.framekit_motion_anim_fade_in
    OverlayAnimation.POP -> R.string.framekit_motion_anim_pop
    OverlayAnimation.SLIDE_UP -> R.string.framekit_motion_anim_slide_up
    OverlayAnimation.BOUNCE -> R.string.framekit_motion_anim_bounce
    OverlayAnimation.WIGGLE -> R.string.framekit_motion_anim_wiggle
    OverlayAnimation.PULSE -> R.string.framekit_motion_anim_pulse
}

private val SPEEDS = listOf(0.5, 0.75, 1.0, 1.5, 2.0, 3.0)
private const val MIN_TRANSITION_MS = 200L
private const val MIN_TRIM_MS = 100L
private const val THUMB_DP = 56
private const val MAX_FADE_S = 5f
