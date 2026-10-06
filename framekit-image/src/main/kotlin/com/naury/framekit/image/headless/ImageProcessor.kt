package com.naury.framekit.image.headless

import com.naury.framekit.android.catalog.EditorCatalog
import com.naury.framekit.image.catalog.CatalogAssets
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.naury.framekit.android.export.CoroutineExportHandle
import com.naury.framekit.android.export.ExportHandle
import com.naury.framekit.android.export.ExportState
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.output.OutputTarget
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.core.validation.ImageProjectValidator
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.core.overlay.SubjectCutout
import com.naury.framekit.image.cutout.BackgroundRemover
import com.naury.framekit.image.cutout.BackgroundRemovers
import com.naury.framekit.image.cutout.CutoutMasks
import com.naury.framekit.image.decode.BitmapDecoder
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.decode.PreviewResolution
import com.naury.framekit.image.decode.SampleSize
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.image.effect.ColorEffectRenderer
import com.naury.framekit.image.effect.DefaultColorEffectRenderer
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.image.export.ImageExportStage
import com.naury.framekit.image.export.ImageMemoryBudget
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlin.random.Random

/**
 * [ImageProcessor]로 연 원본이다.
 *
 * @property metadata 정방향 크기와 MIME type. crop과 오버레이는 이 값을 기준으로 만든다.
 */
public class ImageSource internal constructor(
    public val metadata: SourceMetadata,
    internal val info: ImageSourceInfo,
)

/**
 * UI 없이 이미지를 편집하고 내보낸다.
 *
 * ```
 * ImageProcessor(context).use { processor ->
 *     val source = processor.open(EditorInput.UriSource(uri))
 *     val project = processor.newProject(source).copy(filter = FilterSelection("bright", 1.0))
 *     val handle = processor.startExport(project, source, ImageExportConfig(), lifecycleScope)
 *     when (val result = handle.awaitResult()) { ... }
 * }
 * ```
 *
 * 에디터와 같은 decoder, renderer, 내보내기 파이프라인을 쓰므로 같은 프로젝트를 headless로 내보내든
 * UI로 내보내든 결과가 같다. GPU context와 가져온 bitmap을 해제하려면 [close]를 호출한다.
 *
 * @param catalog 호스트 필터·스티커·폰트. 프로젝트가 호스트 항목 id를 쓰면 같은 카탈로그를 넘긴다.
 */
public class ImageProcessor(
    context: Context,
    private val colorRenderer: ColorEffectRenderer = DefaultColorEffectRenderer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
    backgroundRemover: BackgroundRemover? = null,
    catalog: EditorCatalog = EditorCatalog(),
) : AutoCloseable {

    private val appContext = context.applicationContext.also { CatalogAssets.install(it, catalog) }
    private val registry = SessionSourceRegistry(appContext)
    private val outputStore = AppFileOutputStore(appContext)
    private val coordinator = ImageExportCoordinator(
        resolver = registry,
        outputStore = outputStore,
        memoryBudgetBytes = ImageMemoryBudget.bytes(appContext),
        dispatcher = exportDispatcher,
        contentResolver = appContext.contentResolver,
        colorRenderer = colorRenderer,
    )
    private val importDirectory = File(appContext.cacheDir, IMPORT_DIRECTORY)
    private val assets = ProjectAssetStore(File(appContext.cacheDir, "$ASSET_DIRECTORY/${UUID.randomUUID()}"))
    private val remover: BackgroundRemover? = backgroundRemover ?: BackgroundRemovers.find(appContext)

    /** 선택 artifact인 `framekit-segmentation`이 설치되어 있으면 `true`. */
    public val canRemoveBackground: Boolean get() = remover != null
    private val imports = mutableListOf<File>()

    /**
     * Uri 또는 파일 원본을 열고 메타데이터를 읽는다.
     *
     * @throws FrameKitException 원본·포맷 오류, 또는 UI가 필요한 [EditorInput.Pick]이면
     *   `INVALID_CONFIGURATION`.
     */
    public suspend fun open(input: EditorInput): ImageSource = withContext(ioDispatcher) {
        if (input is EditorInput.Pick) throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Picking needs the editor UI")
        val id = registry.register(input)
        val info = ImageMetadataReader(registry).read(id)
        ImageSource(info.metadata, info)
    }

    /**
     * 메모리상의 bitmap을 연다. bitmap은 먼저 private PNG로 복사되므로 크기에 비례한 시간과 저장 공간이
     * 든다. 소유권은 호출자에게 남으며 이후 recycle해도 된다.
     */
    public suspend fun open(bitmap: Bitmap): ImageSource = withContext(ioDispatcher) {
        if (!importDirectory.isDirectory && !importDirectory.mkdirs()) {
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Could not create import directory")
        }
        val file = File(importDirectory, "${UUID.randomUUID()}.png")
        try {
            file.outputStream().buffered().use { stream ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Bitmap could not be encoded")
            }
        } catch (error: IOException) {
            file.delete()
            throw FrameKitException(EditorErrorCode.OUTPUT_WRITE_FAILED, "Bitmap could not be imported", error)
        }
        synchronized(imports) { imports += file }
        open(EditorInput.FileSource(file.absolutePath))
    }

    /** 새 grain seed를 가진, [source]에 대한 편집 전 프로젝트. */
    public fun newProject(source: ImageSource): ImageProject =
        ImageProject(ProjectId(UUID.randomUUID().toString()), source.metadata.id, grainSeed = Random.nextLong())

    /**
     * [source]의 배경을 제거하고 누끼가 적용된 [project]를 반환한다.
     *
     * 피사체는 미리보기 크기로 디코딩한 이미지에서 찾고, mask는 이 processor의 수명 동안만 유지되므로
     * [close]를 호출하기 전에 내보내야 한다.
     *
     * @throws FrameKitException remover가 설치되지 않았거나 모델이 아직 준비되지 않았으면
     *   `UNSUPPORTED_OPERATION`.
     */
    public suspend fun removeBackground(project: ImageProject, source: ImageSource): ImageProject {
        val remover = remover ?: throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Add framekit-segmentation to remove backgrounds")
        val preview = withContext(ioDispatcher) {
            BitmapDecoder(registry, appContext.contentResolver).decode(source.info, SampleSize.forMinimumLongEdge(source.info.encodedSize, PreviewResolution.DEFAULT_LONG_EDGE))
        }
        try {
            val mask = remover.subjectMask(preview.bitmap)
            val id = withContext(ioDispatcher) { CutoutMasks.save(mask, assets) }
            mask.recycle()
            return project.copy(cutout = SubjectCutout(id))
        } finally {
            preview.recycle()
        }
    }

    /**
     * [project]를 검증하고 [scope]에서 즉시 내보내기를 시작한다.
     *
     * @throws FrameKitException 시작 전에 `INVALID_PROJECT` 또는 `INVALID_CONFIGURATION`. 내보내기 도중의
     *   실패는 대신 handle로 전달된다.
     */
    public fun startExport(
        project: ImageProject,
        source: ImageSource,
        config: ImageExportConfig = ImageExportConfig(),
        scope: CoroutineScope,
        target: OutputTarget = OutputTarget.AppFile,
    ): ExportHandle {
        val projectCheck = ImageProjectValidator.validate(project, source.metadata)
        if (projectCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_PROJECT, projectCheck.issues.joinToString { "${it.path}: ${it.code}" })
        }
        val configCheck = config.validate()
        if (configCheck is ValidationResult.Invalid) {
            throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, configCheck.issues.joinToString { it.path })
        }
        return CoroutineExportHandle(scope) { report ->
            coordinator.export(project, source.info, config, target, assets) { stage ->
                report(
                    when (stage) {
                        ImageExportStage.PREPARING -> ExportState.Preparing
                        ImageExportStage.RENDERING, ImageExportStage.ENCODING -> ExportState.Running()
                        ImageExportStage.FINALIZING -> ExportState.Finalizing
                    },
                )
            }
        }
    }

    /** 내보낸 파일을 삭제한다. `FrameKitOutputs.deleteOutput` 참고. */
    public fun deleteOutput(uri: Uri): Boolean = outputStore.delete(uri)

    /** GPU context, 가져온 bitmap, 배경 mask를 해제한다. 내보낸 파일은 유지한다. */
    override fun close() {
        colorRenderer.release()
        synchronized(imports) {
            imports.forEach(File::delete)
            imports.clear()
        }
        assets.clear()
        assets.directory.delete()
    }

    private companion object {
        const val IMPORT_DIRECTORY = "framekit/imports"
        const val ASSET_DIRECTORY = "framekit/assets"
    }
}
