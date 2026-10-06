package com.naury.framekit.core.overlay

import com.naury.framekit.core.geometry.PointN

/**
 * Placement of an overlay in the output canvas (C space, `0..1` after crop).
 *
 * Overlays stay at the same normalized position when the crop changes; they are not anchored to
 * objects in the photo.
 *
 * @property center center of the overlay in C.
 * @property scale size multiplier; `1.0` is the size the overlay was created with.
 * @property rotationDegrees clockwise rotation.
 * @property opacity `0..1`.
 */
public data class OverlayTransform(
    val center: PointN = PointN(0.5, 0.5),
    val scale: Double = 1.0,
    val rotationDegrees: Double = 0.0,
    val opacity: Double = 1.0,
) {
    public companion object {
        public const val MIN_SCALE: Double = 0.1
        public const val MAX_SCALE: Double = 10.0
    }
}

/** Paragraph alignment inside a text box. */
public enum class TextAlignment {
    START,
    CENTER,
    END,
}

/** Outline around glyphs. [widthHeightRatio] is relative to the canvas height. */
public data class StrokeSpec(val colorArgb: Int, val widthHeightRatio: Double)

/** Drop shadow; offsets and blur are relative to the canvas height. */
public data class ShadowSpec(
    val colorArgb: Int,
    val offsetXHeightRatio: Double,
    val offsetYHeightRatio: Double,
    val blurHeightRatio: Double,
)

/** Rounded box behind the text. Padding and corner radius are relative to the canvas height. */
public data class BackgroundSpec(val colorArgb: Int, val paddingHeightRatio: Double = 0.008, val cornerHeightRatio: Double = 0.006)

/**
 * Text appearance. Sizes are fractions of the output canvas so the same project looks identical at any
 * export resolution.
 *
 * @property fontId id of a font in the font catalog, for example `sans` or `serif-bold`.
 * @property fontSizeHeightRatio text size divided by the canvas height.
 * @property maxWidthRatio line wrap width divided by the canvas width.
 */
public data class TextStyleSpec(
    val fontId: String = "sans",
    val fontSizeHeightRatio: Double = 0.06,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val alignment: TextAlignment = TextAlignment.CENTER,
    val letterSpacingEm: Double = 0.0,
    val lineSpacingMultiplier: Double = 1.0,
    val maxWidthRatio: Double = 0.8,
    val stroke: StrokeSpec? = null,
    val shadow: ShadowSpec? = null,
    val background: BackgroundSpec? = null,
)

/** Text or sticker placed on top of the image. The list order is the z-order, last on top. */
public sealed interface ImageOverlay {
    public val id: String
    public val transform: OverlayTransform

    public fun withTransform(transform: OverlayTransform): ImageOverlay

    public data class Text(
        override val id: String,
        val text: String,
        val style: TextStyleSpec = TextStyleSpec(),
        override val transform: OverlayTransform = OverlayTransform(),
    ) : ImageOverlay {
        override fun withTransform(transform: OverlayTransform): Text = copy(transform = transform)
    }

    /**
     * Sticker drawn from a catalog asset.
     *
     * @property assetId `emoji:<characters>` for a standard emoji rendered with the system emoji font,
     *   or an id from a host sticker catalog.
     * @property widthRatio sticker width at scale 1, divided by the canvas width.
     */
    public data class Sticker(
        override val id: String,
        val assetId: String,
        val widthRatio: Double = 0.25,
        override val transform: OverlayTransform = OverlayTransform(),
    ) : ImageOverlay {
        override fun withTransform(transform: OverlayTransform): Sticker = copy(transform = transform)
    }
}

/** Kind of brush in the drawing layer. */
public enum class BrushKind {
    PEN,
    MARKER,

    /** Translucent strokes; overlap within one stroke does not darken. */
    HIGHLIGHTER,

    /** Removes only the drawing layer under the stroke, never the photo, text or stickers. */
    ERASER,
}

/**
 * One sample of a stroke in C space.
 *
 * @property pressure `0..1`, used for width variation.
 */
public data class StrokePoint(val x: Double, val y: Double, val pressure: Double = 1.0)

/**
 * One finger-down to finger-up stroke. Width is a fraction of the canvas short edge.
 */
public data class DrawingStroke(
    val id: String,
    val points: List<StrokePoint>,
    val widthShortEdgeRatio: Double,
    val colorArgb: Int,
    val opacity: Double,
    val brush: BrushKind,
) {
    public companion object {
        /** Upper bound of points per stroke; longer strokes are thinned when recorded. */
        public const val MAX_POINTS: Int = 2_000
    }
}

/** Fonts every device can render. Hosts add their own in v1.0. */
public object FontCatalog {
    public val fontIds: List<String> = listOf("sans", "sans-bold", "serif", "serif-bold", "mono", "handwriting")

    public fun contains(id: String): Boolean = id in fontIds
}
