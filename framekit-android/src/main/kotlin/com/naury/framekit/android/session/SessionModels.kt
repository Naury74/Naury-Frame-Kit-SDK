package com.naury.framekit.android.session

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.overlay.BackgroundSpec
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.ShadowSpec
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.core.overlay.StrokeSpec
import com.naury.framekit.core.overlay.TextAlignment
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Values that identify the exact image a session was editing.
 *
 * After process death the source is opened again and compared with this fingerprint. A different
 * image behind the same reference is never treated as the edited one.
 *
 * @property encodedWidth stored pixel width before orientation.
 * @property encodedHeight stored pixel height before orientation.
 * @property orientation EXIF orientation tag value.
 */
@Serializable
public data class SourceFingerprint(
    val mimeType: String,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val orientation: Int,
)

/** Reference that can reopen a source after recreation. Only strings are stored. */
@Serializable
public sealed interface SourceReference {
    @Serializable
    @SerialName("content")
    public data class Content(val uri: String) : SourceReference

    @Serializable
    @SerialName("file")
    public data class LocalFile(val absolutePath: String) : SourceReference
}

/**
 * Committed image edits as stored on disk. Drafts of an open tool are never written.
 *
 * The source key is not stored because a [SourceId] is only valid for one registry; the restored
 * project gets the key of the newly registered source.
 */
@Serializable
public data class ImageProjectSnapshot(
    val projectId: String,
    val revision: Long,
    val quarterTurns: Int,
    val straightenDegrees: Double,
    val flipX: Boolean,
    val flipY: Boolean,
    val cropLeft: Double,
    val cropTop: Double,
    val cropRight: Double,
    val cropBottom: Double,
    val adjustments: AdjustmentsSnapshot = AdjustmentsSnapshot(),
    val filterPresetId: String = FilterCatalog.ORIGINAL_ID,
    val filterIntensity: Double = 0.0,
    val grainSeed: Long = 0L,
    val overlays: List<OverlaySnapshot> = emptyList(),
    val drawing: List<StrokeSnapshot> = emptyList(),
) {
    public fun toProject(source: SourceId): ImageProject = ImageProject(
        id = ProjectId(projectId),
        source = source,
        geometry = GeometryEdit(
            quarterTurns = quarterTurns,
            straightenDegrees = straightenDegrees,
            flipX = flipX,
            flipY = flipY,
            crop = RectN(cropLeft, cropTop, cropRight, cropBottom),
        ),
        adjustments = adjustments.toModel(),
        filter = FilterSelection(filterPresetId, filterIntensity),
        grainSeed = grainSeed,
        overlays = overlays.map(OverlaySnapshot::toModel),
        drawing = drawing.map(StrokeSnapshot::toModel),
        revision = revision,
    )

    public companion object {
        public fun of(project: ImageProject): ImageProjectSnapshot = with(project.geometry) {
            ImageProjectSnapshot(
                projectId = project.id.value,
                revision = project.revision,
                quarterTurns = quarterTurns,
                straightenDegrees = straightenDegrees,
                flipX = flipX,
                flipY = flipY,
                cropLeft = crop.left,
                cropTop = crop.top,
                cropRight = crop.right,
                cropBottom = crop.bottom,
                adjustments = AdjustmentsSnapshot.of(project.adjustments),
                filterPresetId = project.filter.presetId,
                filterIntensity = project.filter.intensity,
                grainSeed = project.grainSeed,
                overlays = project.overlays.map(OverlaySnapshot::of),
                drawing = project.drawing.map(StrokeSnapshot::of),
            )
        }
    }
}

/** Stored form of [Adjustments]; every field defaults to 0 so older snapshots still load. */
@Serializable
public data class AdjustmentsSnapshot(
    val brightness: Double = 0.0,
    val exposure: Double = 0.0,
    val contrast: Double = 0.0,
    val highlights: Double = 0.0,
    val shadows: Double = 0.0,
    val saturation: Double = 0.0,
    val temperature: Double = 0.0,
    val tint: Double = 0.0,
    val sharpness: Double = 0.0,
    val fade: Double = 0.0,
    val vignette: Double = 0.0,
    val grain: Double = 0.0,
) {
    public fun toModel(): Adjustments =
        Adjustments(brightness, exposure, contrast, highlights, shadows, saturation, temperature, tint, sharpness, fade, vignette, grain)

    public companion object {
        public fun of(a: Adjustments): AdjustmentsSnapshot = AdjustmentsSnapshot(
            a.brightness, a.exposure, a.contrast, a.highlights, a.shadows, a.saturation, a.temperature, a.tint,
            a.sharpness, a.fade, a.vignette, a.grain,
        )
    }
}

/** Stored form of an [ImageOverlay]. */
@Serializable
public sealed interface OverlaySnapshot {
    public fun toModel(): ImageOverlay

    @Serializable
    @SerialName("text")
    public data class Text(
        val id: String,
        val text: String,
        val style: TextStyleSnapshot,
        val transform: TransformSnapshot,
    ) : OverlaySnapshot {
        override fun toModel(): ImageOverlay = ImageOverlay.Text(id, text, style.toModel(), transform.toModel())
    }

    @Serializable
    @SerialName("sticker")
    public data class Sticker(
        val id: String,
        val assetId: String,
        val widthRatio: Double,
        val transform: TransformSnapshot,
    ) : OverlaySnapshot {
        override fun toModel(): ImageOverlay = ImageOverlay.Sticker(id, assetId, widthRatio, transform.toModel())
    }

    public companion object {
        public fun of(overlay: ImageOverlay): OverlaySnapshot = when (overlay) {
            is ImageOverlay.Text -> Text(overlay.id, overlay.text, TextStyleSnapshot.of(overlay.style), TransformSnapshot.of(overlay.transform))
            is ImageOverlay.Sticker -> Sticker(overlay.id, overlay.assetId, overlay.widthRatio, TransformSnapshot.of(overlay.transform))
        }
    }
}

@Serializable
public data class TransformSnapshot(val x: Double, val y: Double, val scale: Double, val rotation: Double, val opacity: Double) {
    public fun toModel(): OverlayTransform = OverlayTransform(PointN(x, y), scale, rotation, opacity)

    public companion object {
        public fun of(t: OverlayTransform): TransformSnapshot = TransformSnapshot(t.center.x, t.center.y, t.scale, t.rotationDegrees, t.opacity)
    }
}

@Serializable
public data class TextStyleSnapshot(
    val fontId: String,
    val fontSize: Double,
    val color: Int,
    val alignment: String,
    val letterSpacing: Double,
    val lineSpacing: Double,
    val maxWidth: Double,
    val strokeColor: Int? = null,
    val strokeWidth: Double? = null,
    val shadowColor: Int? = null,
    val shadowDx: Double? = null,
    val shadowDy: Double? = null,
    val shadowBlur: Double? = null,
    val backgroundColor: Int? = null,
    val backgroundPadding: Double? = null,
    val backgroundCorner: Double? = null,
) {
    public fun toModel(): TextStyleSpec = TextStyleSpec(
        fontId = fontId,
        fontSizeHeightRatio = fontSize,
        colorArgb = color,
        alignment = TextAlignment.entries.firstOrNull { it.name == alignment } ?: TextAlignment.CENTER,
        letterSpacingEm = letterSpacing,
        lineSpacingMultiplier = lineSpacing,
        maxWidthRatio = maxWidth,
        stroke = if (strokeColor != null && strokeWidth != null) StrokeSpec(strokeColor, strokeWidth) else null,
        shadow = if (shadowColor != null && shadowDx != null && shadowDy != null && shadowBlur != null) {
            ShadowSpec(shadowColor, shadowDx, shadowDy, shadowBlur)
        } else {
            null
        },
        background = if (backgroundColor != null && backgroundPadding != null && backgroundCorner != null) {
            BackgroundSpec(backgroundColor, backgroundPadding, backgroundCorner)
        } else {
            null
        },
    )

    public companion object {
        public fun of(s: TextStyleSpec): TextStyleSnapshot = TextStyleSnapshot(
            fontId = s.fontId,
            fontSize = s.fontSizeHeightRatio,
            color = s.colorArgb,
            alignment = s.alignment.name,
            letterSpacing = s.letterSpacingEm,
            lineSpacing = s.lineSpacingMultiplier,
            maxWidth = s.maxWidthRatio,
            strokeColor = s.stroke?.colorArgb,
            strokeWidth = s.stroke?.widthHeightRatio,
            shadowColor = s.shadow?.colorArgb,
            shadowDx = s.shadow?.offsetXHeightRatio,
            shadowDy = s.shadow?.offsetYHeightRatio,
            shadowBlur = s.shadow?.blurHeightRatio,
            backgroundColor = s.background?.colorArgb,
            backgroundPadding = s.background?.paddingHeightRatio,
            backgroundCorner = s.background?.cornerHeightRatio,
        )
    }
}

/** Stored form of a [DrawingStroke]; points are packed as x, y, pressure triples. */
@Serializable
public data class StrokeSnapshot(
    val id: String,
    val points: List<Double>,
    val width: Double,
    val color: Int,
    val opacity: Double,
    val brush: String,
) {
    public fun toModel(): DrawingStroke = DrawingStroke(
        id = id,
        points = points.chunked(3).filter { it.size == 3 }.map { StrokePoint(it[0], it[1], it[2]) },
        widthShortEdgeRatio = width,
        colorArgb = color,
        opacity = opacity,
        brush = BrushKind.entries.firstOrNull { it.name == brush } ?: BrushKind.PEN,
    )

    public companion object {
        public fun of(stroke: DrawingStroke): StrokeSnapshot = StrokeSnapshot(
            id = stroke.id,
            points = stroke.points.flatMap { listOf(it.x, it.y, it.pressure) },
            width = stroke.widthShortEdgeRatio,
            color = stroke.colorArgb,
            opacity = stroke.opacity,
            brush = stroke.brush.name,
        )
    }
}

@Serializable
internal data class SessionDescriptor(
    val schemaVersion: Int,
    val sessionId: String,
    val source: SourceReference,
    val fingerprint: SourceFingerprint,
    val persistedGrant: Boolean,
    val createdAt: Long,
)

@Serializable
internal data class SessionSnapshotFile(
    val schemaVersion: Int,
    val updatedAt: Long,
    val exportInProgress: Boolean,
    val image: ImageProjectSnapshot?,
)

/**
 * Everything restored for a session.
 *
 * @property snapshot last committed edits, or `null` when nothing was committed yet.
 * @property exportWasInterrupted `true` when the process died while an export was running. The
 *   export did not complete and must be started again.
 */
public data class SessionRecord(
    val sessionId: String,
    val source: SourceReference,
    val fingerprint: SourceFingerprint,
    val snapshot: ImageProjectSnapshot?,
    val exportWasInterrupted: Boolean,
)
