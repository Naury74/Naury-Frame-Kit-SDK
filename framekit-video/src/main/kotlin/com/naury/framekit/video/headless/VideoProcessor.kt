package com.naury.framekit.video.headless

import android.content.Context
import android.net.Uri
import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.android.export.CoroutineExportHandle
import com.naury.framekit.android.export.ExportHandle
import com.naury.framekit.android.export.ExportState
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.isSingleSource
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.core.validation.VideoProjectValidator
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.image.catalog.CatalogAssets
import com.naury.framekit.video.export.VideoExportConfig
import com.naury.framekit.video.export.VideoExportCoordinator
import com.naury.framekit.video.export.VideoExportProgress
import com.naury.framekit.video.source.AudioSourceInfo
import com.naury.framekit.video.source.VideoMetadataReader
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.random.Random

/**
 * [VideoProcessor]로 연 영상 원본.
 *
 * @property metadata 회전을 적용한 크기, 길이(µs), 소리 유무. 클립 구간은 이 값을 기준으로 만든다.
 */
public class VideoSource internal constructor(
    public val metadata: SourceMetadata,
    internal val info: VideoSourceInfo,
    internal val location: SourceLocation,
)

/**
 * [VideoProcessor]로 연 배경 음악 원본.
 *
 * @property metadata 길이(µs)와 MIME 형식.
 */
public class AudioSource internal constructor(
    public val metadata: SourceMetadata,
    internal val info: AudioSourceInfo,
    internal val location: SourceLocation,
)

/**
 * UI 없이 영상을 편집하고 MP4로 내보낸다.
 *
 * ```
 * val processor = VideoProcessor(context)
 * val source = processor.open(EditorInput.UriSource(uri))
 * val project = processor.newProject(source).let { p ->
 *     p.copy(timeline = p.timeline.copy(videoClips = p.timeline.videoClips.map { it.copy(speed = 2.0) }))
 * }
 * val handle = processor.startExport(project, listOf(source), scope = lifecycleScope)
 * when (val result = handle.awaitResult()) { ... }
 * ```
 *
 * 편집 화면과 같은 Media3 구성과 내보내기를 쓰므로 같은 프로젝트면 결과가 같다. 내보내기는 main
 * looper가 있는 프로세스에서 실행해야 한다(Media3 Transformer 요구).
 *
 * @param catalog 호스트 필터·스티커·폰트. 프로젝트가 호스트 항목 id를 쓰면 같은 카탈로그를 넘긴다.
 *   `null`이면 이미 등록된 카탈로그(편집기가 등록한 것 포함)를 그대로 쓴다.
 */
public class VideoProcessor(
    context: Context,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    catalog: EditorCatalog? = null,
) {
    private val appContext = context.applicationContext.also { if (catalog != null) CatalogAssets.install(it, catalog) else CatalogAssets.attach(it) }
    private val registry = SessionSourceRegistry(appContext)
    private val reader = VideoMetadataReader(appContext, registry)
    private val outputStore = AppFileOutputStore(appContext)
    private val coordinator = VideoExportCoordinator(appContext, outputStore)

    /**
     * 영상 원본을 열고 정보를 읽는다.
     *
     * @throws FrameKitException 원본·형식 오류, 또는 UI가 필요한 [EditorInput.Pick]·[EditorInput.Capture]나 여러 원본([EditorInput.Multiple])이면 `INVALID_CONFIGURATION`.
     */
    public suspend fun open(input: EditorInput): VideoSource = withContext(ioDispatcher) {
        if (!input.isSingleSource) throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Open one Uri or file at a time")
        val id = registry.register(input)
        val info = reader.read(id)
        VideoSource(info.metadata, info, registry.location(id))
    }

    /** 배경 음악으로 쓸 원본을 연다. 영상 파일이면 그 소리만 쓴다. */
    public suspend fun openAudio(input: EditorInput): AudioSource = withContext(ioDispatcher) {
        if (!input.isSingleSource) throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Open one Uri or file at a time")
        val id = registry.register(input)
        val info = reader.readAudio(id)
        AudioSource(info.metadata, info, registry.location(id))
    }

    /** [source] 전체를 한 클립으로 담은 편집 전 프로젝트. grain seed는 새로 만든다. */
    public fun newProject(source: VideoSource): VideoProject {
        val clip = VideoClip(UUID.randomUUID().toString(), source.metadata.id, TimeRangeUs(0, source.metadata.durationUs ?: 0L))
        return VideoProject(ProjectId(UUID.randomUUID().toString()), Timeline(listOf(clip)), grainSeed = Random.nextLong())
    }

    /**
     * [project]를 검증하고 [scope]에서 바로 내보내기를 시작한다.
     *
     * @param sources 프로젝트 클립이 쓰는 영상 원본.
     * @param music 프로젝트 배경 음악이 쓰는 원본.
     * @param minClipOutputDurationUs 허용하는 가장 짧은 클립(출력 시간 µs).
     * @throws FrameKitException 시작 전 `INVALID_PROJECT` 또는 `INVALID_CONFIGURATION`. 내보내는 중의 실패는
     *   handle로 전달된다.
     */
    public fun startExport(
        project: VideoProject,
        sources: List<VideoSource>,
        config: VideoExportConfig = VideoExportConfig(),
        scope: CoroutineScope,
        music: List<AudioSource> = emptyList(),
        target: OutputTarget = OutputTarget.AppFile,
        minClipOutputDurationUs: Long = DEFAULT_MIN_CLIP_US,
    ): ExportHandle {
        val metadata = sources.associate { it.metadata.id to it.metadata } + music.associate { it.metadata.id to it.metadata }
        val projectCheck = VideoProjectValidator.validate(project, metadata, minClipOutputDurationUs)
        if (projectCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_PROJECT, projectCheck.issues.joinToString { "${it.path}: ${it.code}" })
        }
        val configCheck = config.validate()
        if (configCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, configCheck.issues.joinToString { it.path })
        }
        val videoInfo: Map<SourceId, VideoSourceInfo> = sources.associate { it.metadata.id to it.info }
        val audioInfo: Map<SourceId, AudioSourceInfo> = music.associate { it.metadata.id to it.info }
        val locations = sources.associate { it.metadata.id to it.location } + music.associate { it.metadata.id to it.location }
        return CoroutineExportHandle(scope) { report ->
            coordinator.export(project, videoInfo, locations, config, target, minClipOutputDurationUs, audioInfo) { progress ->
                report(
                    when (progress) {
                        VideoExportProgress.Preparing -> ExportState.Preparing
                        is VideoExportProgress.Encoding -> ExportState.Running(progress.percent?.let { it / 100f })
                        VideoExportProgress.Finalizing -> ExportState.Finalizing
                    },
                )
            }
        }
    }

    /** 이전 내보내기가 남긴 임시 파일을 지운다. 앱 시작 때 한 번 부르면 된다. */
    public fun deleteStalePartials(): Unit = coordinator.deleteStalePartials()

    /** 내보낸 파일을 지운다. `FrameKitOutputs.deleteOutput` 참고. */
    public fun deleteOutput(uri: Uri): Boolean = outputStore.delete(uri)

    public companion object {
        /** 편집 화면 기본값과 같은 최소 클립 길이(1초). */
        public const val DEFAULT_MIN_CLIP_US: Long = 1_000_000L
    }
}
