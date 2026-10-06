package com.naury.framekit.android.session

import com.naury.framekit.core.effect.Adjustments
import com.naury.framekit.core.effect.FilterCatalog
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.overlay.BackgroundSpec
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.overlay.ShadowSpec
import com.naury.framekit.core.overlay.SubjectCutout
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.core.overlay.StrokeSpec
import com.naury.framekit.core.overlay.TextAlignment
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 세션이 편집하던 이미지를 정확히 식별하는 값.
 *
 * 프로세스가 종료된 뒤에는 원본을 다시 열어 이 fingerprint와 비교한다. 같은 참조 뒤에 다른 이미지가
 * 있으면 편집하던 이미지로 취급하지 않는다.
 *
 * @property encodedWidth orientation 적용 전 저장된 픽셀 너비.
 * @property encodedHeight orientation 적용 전 저장된 픽셀 높이.
 * @property orientation EXIF orientation 태그 값. 영상은 컨테이너 회전 각도(도).
 * @property durationUs 영상 길이(µs). 이미지는 `null`.
 */
@Serializable
public data class SourceFingerprint(
    val mimeType: String,
    val encodedWidth: Int,
    val encodedHeight: Int,
    val orientation: Int,
    val durationUs: Long? = null,
)

/** 재생성 후 원본을 다시 열 수 있는 참조. 문자열만 저장한다. */
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
 * 디스크에 저장되는 확정된 이미지 편집. 열려 있는 도구의 초안은 기록하지 않는다.
 *
 * [SourceId]는 하나의 레지스트리 안에서만 유효하므로 원본 키는 저장하지 않는다. 복원된 프로젝트는
 * 새로 등록한 원본의 키를 받는다.
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
    val privacyMasks: List<PrivacyMaskSnapshot> = emptyList(),
    val cutoutMaskAssetId: String? = null,
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
        privacyMasks = privacyMasks.map(PrivacyMaskSnapshot::toModel),
        cutout = cutoutMaskAssetId?.let(::SubjectCutout),
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
                privacyMasks = project.privacyMasks.map(PrivacyMaskSnapshot::of),
                cutoutMaskAssetId = project.cutout?.maskAssetId,
            )
        }
    }
}

/** [Adjustments]의 저장 형태. 이전 snapshot도 읽을 수 있도록 모든 필드의 기본값은 0이다. */
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

/** [ImageOverlay]의 저장 형태. */
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

/** [DrawingStroke]의 저장 형태. 점은 x, y, pressure 세 값씩 묶어 저장한다. */
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

/**
 * [PrivacyMask]의 저장 형태. [shape]는 `brush`, `rectangle`, `ellipse` 중 하나이고, [values]는
 * 사각형 경계 또는 x, y 쌍으로 묶은 브러시 점을 담는다. [effect]는 `blur` 또는 `mosaic`이다.
 */
@Serializable
public data class PrivacyMaskSnapshot(
    val id: String,
    val shape: String,
    val values: List<Double>,
    val brushWidth: Double = 0.0,
    val effect: String,
    val strength: Double,
) {
    public fun toModel(): PrivacyMask {
        val maskShape = when (shape) {
            "brush" -> MaskShape.Brush(values.chunked(2).filter { it.size == 2 }.map { PointN(it[0], it[1]) }, brushWidth)
            "ellipse" -> MaskShape.Ellipse(rectOf(values))
            else -> MaskShape.Rectangle(rectOf(values))
        }
        val maskEffect = if (effect == "blur") PrivacyEffect.Blur(strength) else PrivacyEffect.Mosaic(strength)
        return PrivacyMask(id, maskShape, maskEffect)
    }

    private fun rectOf(v: List<Double>) = if (v.size >= 4) RectN(v[0], v[1], v[2], v[3]) else RectN(0.0, 0.0, 0.0, 0.0)

    public companion object {
        public fun of(mask: PrivacyMask): PrivacyMaskSnapshot {
            val (shape, values, width) = when (val s = mask.shape) {
                is MaskShape.Brush -> Triple("brush", s.points.flatMap { listOf(it.x, it.y) }, s.widthShortEdgeRatio)
                is MaskShape.Rectangle -> Triple("rectangle", listOf(s.rect.left, s.rect.top, s.rect.right, s.rect.bottom), 0.0)
                is MaskShape.Ellipse -> Triple("ellipse", listOf(s.rect.left, s.rect.top, s.rect.right, s.rect.bottom), 0.0)
            }
            val (effect, strength) = when (val e = mask.effect) {
                is PrivacyEffect.Blur -> "blur" to e.radiusShortEdgeRatio
                is PrivacyEffect.Mosaic -> "mosaic" to e.blockShortEdgeRatio
            }
            return PrivacyMaskSnapshot(mask.id, shape, values, width, effect, strength)
        }
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
    val video: VideoProjectSnapshot? = null,
)

/**
 * 세션에 대해 복원된 모든 정보.
 *
 * @property snapshot 마지막으로 확정된 이미지 편집. 아직 확정한 것이 없으면 `null`.
 * @property videoSnapshot 마지막으로 확정된 영상 편집. 없으면 `null`.
 * @property exportWasInterrupted export 실행 중에 프로세스가 종료되었으면 `true`. export는 완료되지
 *   않았으므로 다시 시작해야 한다.
 */
public data class SessionRecord(
    val sessionId: String,
    val source: SourceReference,
    val fingerprint: SourceFingerprint,
    val snapshot: ImageProjectSnapshot?,
    val exportWasInterrupted: Boolean,
    val videoSnapshot: VideoProjectSnapshot? = null,
)
