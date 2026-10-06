// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다. 일정 배속(setSpeed)과 scrubbing 모드는
// @ExperimentalApi라 계측 테스트로 결과 길이·동작을 확인한다.
@file:OptIn(UnstableApi::class, ExperimentalApi::class)

package com.naury.framekit.video.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import android.net.Uri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.SpeedParameters
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.video.CanvasFit
import com.naury.framekit.video.ResolvedAudio
import com.naury.framekit.video.ResolvedClip
import com.naury.framekit.core.video.AudioPlacement
import com.naury.framekit.core.video.AudioSegment
import com.naury.framekit.core.video.TimelineTimeMapper
import androidx.media3.common.C
import androidx.media3.effect.OverlayEffect
import com.naury.framekit.video.VideoRenderPlan

/**
 * [VideoRenderPlan]을 Media3 [Composition]으로 변환한다. 미리보기와 내보내기가 같은 factory를 호출하므로
 * trim, 속도, geometry, 색 보정이 똑같이 적용된다. Media3 타입은 이 패키지 밖으로 나가지 않는다.
 *
 * 클립 단위: trim(clipping), 속도, 음소거/볼륨, geometry, 캔버스 맞춤, 색 보정. Composition 전체:
 * 출력 캔버스의 구간별 가리기 마스크와 그 위의 구간별 텍스트·스티커, HDR을 SDR로 tone-map.
 * 배경 음악은 클립마다 별도 오디오 sequence로 만들어 영상 소리와 섞는다.
 */
internal object Media3CompositionFactory {

    fun create(plan: VideoRenderPlan, maxFrameRate: Int?): Composition {
        val items = plan.clips.map { item(it, plan, maxFrameRate) }
        val timeline = plan.project.timeline
        val clipAudio = plan.clips.any { !it.clip.muted && it.source.metadata.hasAudio }
        // 소리가 있는 클립과 없는 클립이 섞이거나 배경 음악만 있으면 무음 트랙을 만들어 트랙 구성을 맞춘다.
        val video = EditedMediaItemSequence.Builder(items)
            .experimentalSetForceAudioTrack(clipAudio || plan.audio.isNotEmpty())
            .build()
        val durationUs = TimelineTimeMapper.durationUs(timeline)
        val music = plan.audio.mapNotNull { audioSequence(it, durationUs) }
        val effects = buildList<Effect> {
            if (timeline.privacyMasks.isNotEmpty()) add(PrivacyGlEffect(timeline.privacyMasks))
            if (timeline.overlays.isNotEmpty()) add(OverlayEffect(listOf(TimedBitmapOverlay(timeline.overlays))))
        }
        return Composition.Builder(listOf(video) + music)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .apply { if (effects.isNotEmpty()) setEffects(Effects(emptyList(), effects)) }
            .build()
    }

    private fun audioSequence(resolved: ResolvedAudio, videoDurationUs: Long): EditedMediaItemSequence? {
        val segments = AudioPlacement.segments(resolved.clip, videoDurationUs)
        if (segments.none { it is AudioSegment.Play }) return null
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        val volume = resolved.clip.volume.toFloat()
        segments.forEach { segment ->
            when (segment) {
                is AudioSegment.Silence -> builder.addGap(segment.durationUs)
                is AudioSegment.Play -> {
                    val mediaItem = MediaItem.Builder()
                        .setUri(uriOf(resolved.location))
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionUs(segment.sourceRange.startUs)
                                .setEndPositionUs(segment.sourceRange.endExclusiveUs)
                                .build(),
                        )
                        .build()
                    val audio = if (volume != 1f) listOf<AudioProcessor>(AudioEffects.volume(volume)) else emptyList()
                    builder.addItem(
                        EditedMediaItem.Builder(mediaItem)
                            .setRemoveVideo(true)
                            .apply { resolved.source.metadata.durationUs?.let(::setDurationUs) }
                            .setEffects(Effects(audio, emptyList()))
                            .build(),
                    )
                }
            }
        }
        return builder.build()
    }

    private fun item(resolved: ResolvedClip, plan: VideoRenderPlan, maxFrameRate: Int?): EditedMediaItem {
        val clip = resolved.clip
        val mediaItem = MediaItem.Builder()
            .setUri(uriOf(resolved.location))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionUs(clip.sourceRange.startUs)
                    .setEndPositionUs(clip.sourceRange.endExclusiveUs)
                    .build(),
            )
            .build()
        val video = buildList<Effect> {
            addAll(GeometryEffects.effects(clip.effects.geometry))
            val layout = if (plan.project.canvas.fit == CanvasFit.FILL) Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP else Presentation.LAYOUT_SCALE_TO_FIT
            add(Presentation.createForWidthAndHeight(plan.canvasSize.width, plan.canvasSize.height, layout))
            val spec = clip.effects.colorSpec(plan.project.grainSeed)
            if (!spec.copy(sharpenAmount = 0f).isIdentity) add(ColorGlEffect(spec))
            val sourceRate = resolved.source.frameRate
            if (maxFrameRate != null && sourceRate != null && sourceRate * clip.speed > maxFrameRate + FRAME_RATE_TOLERANCE) {
                add(FrameDropEffect.createDefaultFrameDropEffect(maxFrameRate.toFloat()))
            }
        }
        val audio = if (!clip.muted && clip.volume != 1.0) listOf<AudioProcessor>(AudioEffects.volume(clip.volume.toFloat())) else emptyList()
        return EditedMediaItem.Builder(mediaItem)
            // CompositionPlayer는 클립 길이를 미리 알아야 timeline을 만든다. 원본 전체 길이를 넘긴다.
            .apply { resolved.source.metadata.durationUs?.let(::setDurationUs) }
            .setRemoveAudio(clip.muted || !resolved.source.metadata.hasAudio)
            .setEffects(Effects(audio, video))
            .apply { if (clip.speed != 1.0) setSpeed(SpeedParameters(ConstantSpeedProvider(clip.speed.toFloat()), true)) }
            .build()
    }

    private fun uriOf(location: SourceLocation): Uri = when (location) {
        is SourceLocation.Content -> location.uri
        is SourceLocation.LocalFile -> Uri.fromFile(location.file)
    }

    private const val FRAME_RATE_TOLERANCE = 0.5f
}
