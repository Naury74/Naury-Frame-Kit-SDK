package com.naury.framekit.image.headless

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
 * A source opened by [ImageProcessor].
 *
 * @property metadata upright size and MIME type; build crops and overlays against this.
 */
public class ImageSource internal constructor(
    public val metadata: SourceMetadata,
    internal val info: ImageSourceInfo,
)

/**
 * Edits and exports images without any UI.
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
 * Uses the same decoder, renderers and export pipeline as the editor, so headless and UI exports of
 * the same project are identical. Call [close] to release the GPU context and imported bitmaps.
 */
public class ImageProcessor(
    context: Context,
    private val colorRenderer: ColorEffectRenderer = DefaultColorEffectRenderer(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    exportDispatcher: CoroutineDispatcher = Dispatchers.Default,
    backgroundRemover: BackgroundRemover? = null,
) : AutoCloseable {

    private val appContext = context.applicationContext
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

    /** `true` when the optional `framekit-segmentation` artifact is installed. */
    public val canRemoveBackground: Boolean get() = remover != null
    private val imports = mutableListOf<File>()

    /**
     * Opens a Uri or file source and reads its metadata.
     *
     * @throws FrameKitException with a source or format error, or `INVALID_CONFIGURATION` for
     *   [EditorInput.Pick], which needs a UI.
     */
    public suspend fun open(input: EditorInput): ImageSource = withContext(ioDispatcher) {
        if (input is EditorInput.Pick) throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Picking needs the editor UI")
        val id = registry.register(input)
        val info = ImageMetadataReader(registry).read(id)
        ImageSource(info.metadata, info)
    }

    /**
     * Opens an in-memory bitmap. The bitmap is copied to a private PNG first, which costs time and
     * storage proportional to its size; the caller keeps ownership and may recycle it afterwards.
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

    /** An unedited project for [source] with a fresh grain seed. */
    public fun newProject(source: ImageSource): ImageProject =
        ImageProject(ProjectId(UUID.randomUUID().toString()), source.metadata.id, grainSeed = Random.nextLong())

    /**
     * Removes the background of [source] and returns [project] with the cutout applied.
     *
     * The subject is found on a preview-sized decode, and the mask is kept for this processor's
     * lifetime, so export it before calling [close].
     *
     * @throws FrameKitException with `UNSUPPORTED_OPERATION` when no remover is installed or its model
     *   is not ready yet.
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
     * Validates [project] and starts exporting it in [scope] right away.
     *
     * @throws FrameKitException with `INVALID_PROJECT` or `INVALID_CONFIGURATION` before anything
     *   starts. Failures during the export are reported through the handle instead.
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

    /** Deletes an exported file; see `FrameKitOutputs.deleteOutput`. */
    public fun deleteOutput(uri: Uri): Boolean = outputStore.delete(uri)

    /** Releases the GPU context, imported bitmaps and background masks. Exported files are kept. */
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
