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
import com.naury.framekit.video.ResolvedClip
import com.naury.framekit.video.VideoRenderPlan

/**
 * [VideoRenderPlan]을 Media3 [Composition]으로 변환한다. 미리보기와 내보내기가 같은 factory를 호출하므로
 * trim, 속도, geometry, 색 보정이 똑같이 적용된다. Media3 타입은 이 패키지 밖으로 나가지 않는다.
 *
 * 클립 단위: trim(clipping), 속도, 음소거/볼륨, geometry, 캔버스 맞춤, 색 보정. Composition 전체:
 * 출력 캔버스의 구간별 가리기 마스크, HDR을 SDR로 tone-map.
 */
internal object Media3CompositionFactory {

    fun create(plan: VideoRenderPlan, maxFrameRate: Int?): Composition {
        val items = plan.clips.map { item(it, plan, maxFrameRate) }
        val masks = plan.project.timeline.privacyMasks
        return Composition.Builder(EditedMediaItemSequence.Builder(items).build())
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .apply { if (masks.isNotEmpty()) setEffects(Effects(emptyList(), listOf(PrivacyGlEffect(masks)))) }
            .build()
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
