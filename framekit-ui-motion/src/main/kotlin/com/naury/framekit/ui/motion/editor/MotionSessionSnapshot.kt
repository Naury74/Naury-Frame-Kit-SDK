package com.naury.framekit.ui.motion.editor

import com.naury.framekit.android.session.AdjustmentsSnapshot
import com.naury.framekit.android.session.OverlaySnapshot
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.video.AudioRole
import com.naury.framekit.motion.model.CameraMotion
import com.naury.framekit.motion.model.FitMode
import com.naury.framekit.motion.model.LoopMode
import com.naury.framekit.motion.model.MotionAspect
import com.naury.framekit.motion.model.MotionAudio
import com.naury.framekit.motion.model.MotionKind
import com.naury.framekit.motion.model.MotionOverlay
import com.naury.framekit.motion.model.MotionProject
import com.naury.framekit.motion.model.MotionTransition
import com.naury.framekit.motion.model.OverlayAnimation
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * 프로세스가 끝나도 움직이는 이미지 편집을 이어 가기 위한 저장 형식. 원본은 [MotionProject]의 id 대신 함께 저장한 원본 목록의 번호로 가리킨다
 * (다시 열면 원본 id가 새로 매겨진다).
 */
@Serializable
internal data class MotionSessionSnapshot(
    val kind: String,
    val slides: List<Int>,
    val slideDurationMs: Long,
    val transition: String,
    val transitionMs: Long,
    val cameraMotion: String,
    val speed: Double,
    val trimStartMs: Long,
    val trimEndMs: Long?,
    val loop: String,
    val aspect: String,
    val fit: String,
    val backgroundArgb: Int,
    val adjustments: AdjustmentsSnapshot,
    val filterId: String,
    val filterIntensity: Double,
    val overlays: List<OverlayItem>,
    val audio: List<AudioItem>,
    val grainSeed: Long,
) {
    @Serializable
    data class OverlayItem(val overlay: OverlaySnapshot, val animation: String)

    @Serializable
    data class AudioItem(
        val source: Int,
        val role: String,
        val sourceStartMs: Long,
        val sourceDurationMs: Long,
        val timelineStartMs: Long,
        val volume: Double,
        val loop: Boolean,
        val id: String,
        val timelineEndMs: Long? = null,
        val fadeInMs: Long = 0,
        val fadeOutMs: Long = 0,
        val startOffsetMs: Long = 0,
    )

    /** [ids]는 저장한 원본 목록 순서대로 새로 매긴 id. */
    fun toProject(ids: List<String>): MotionProject = MotionProject(
        kind = MotionKind.valueOf(kind),
        sourceIds = slides.map { ids[it] },
        slideDurationMs = slideDurationMs,
        transition = MotionTransition.valueOf(transition),
        transitionMs = transitionMs,
        cameraMotion = CameraMotion.valueOf(cameraMotion),
        speed = speed,
        trimStartMs = trimStartMs,
        trimEndMs = trimEndMs,
        loop = LoopMode.valueOf(loop),
        aspect = MotionAspect.valueOf(aspect),
        fit = FitMode.valueOf(fit),
        backgroundArgb = backgroundArgb,
        adjustments = adjustments.toModel(),
        filter = FilterSelection(filterId, filterIntensity),
        overlays = overlays.map { MotionOverlay(it.overlay.toModel(), OverlayAnimation.valueOf(it.animation)) },
        audio = audio.map {
            MotionAudio(
                it.id, ids[it.source], AudioRole.valueOf(it.role), it.sourceStartMs, it.sourceDurationMs, it.timelineStartMs, it.volume, it.loop,
                it.timelineEndMs, it.fadeInMs, it.fadeOutMs, it.startOffsetMs,
            )
        },
        grainSeed = grainSeed,
    )

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }

        /** 쓰인 원본 id 목록(순서가 번호)과 함께 저장 형식을 만든다. */
        fun of(project: MotionProject): Pair<List<String>, MotionSessionSnapshot> {
            val ids = (project.sourceIds + project.audio.map { it.sourceId }).distinct()
            val snapshot = MotionSessionSnapshot(
                kind = project.kind.name,
                slides = project.sourceIds.map(ids::indexOf),
                slideDurationMs = project.slideDurationMs,
                transition = project.transition.name,
                transitionMs = project.transitionMs,
                cameraMotion = project.cameraMotion.name,
                speed = project.speed,
                trimStartMs = project.trimStartMs,
                trimEndMs = project.trimEndMs,
                loop = project.loop.name,
                aspect = project.aspect.name,
                fit = project.fit.name,
                backgroundArgb = project.backgroundArgb,
                adjustments = AdjustmentsSnapshot.of(project.adjustments),
                filterId = project.filter.presetId,
                filterIntensity = project.filter.intensity,
                overlays = project.overlays.map { OverlayItem(OverlaySnapshot.of(it.overlay), it.animation.name) },
                audio = project.audio.map {
                    AudioItem(
                        ids.indexOf(it.sourceId), it.role.name, it.sourceStartMs, it.sourceDurationMs, it.timelineStartMs, it.volume, it.loop, it.id,
                        it.timelineEndMs, it.fadeInMs, it.fadeOutMs, it.startOffsetMs,
                    )
                },
                grainSeed = project.grainSeed,
            )
            return ids to snapshot
        }

        /** 읽을 수 없으면 `null`(형식이 바뀐 이전 버전 등). */
        fun decode(text: String): MotionSessionSnapshot? = runCatching { JSON.decodeFromString(serializer(), text) }.getOrNull()
    }
}
