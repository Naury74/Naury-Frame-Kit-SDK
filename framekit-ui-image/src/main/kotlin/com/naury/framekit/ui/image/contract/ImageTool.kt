package com.naury.framekit.ui.image.contract

/** Tools of the image editor. Only tools whose edits are rendered and exported are listed. */
public enum class ImageTool(internal val isDraft: Boolean) {
    /** Crop with free or fixed ratios. Changes are a draft until Apply. */
    CROP(isDraft = true),

    /** 90° rotation, horizontal and vertical flip, and straighten up to ±45°. Changes are a draft until Apply. */
    ROTATE(isDraft = true),

    /** Twelve color and tone adjustments. Each slider drag is committed as one undo step. */
    ADJUST(isDraft = false),

    /** Filter presets with intensity. Each selection or intensity drag is one undo step. */
    FILTER(isDraft = false),

    /** Text with font, color, alignment, outline and background. One edit session is one undo step. */
    TEXT(isDraft = true),

    /** Standard emoji stickers. Adding one, and each move, scale or rotation, is one undo step. */
    STICKER(isDraft = false),

    /** Pen, marker, highlighter and eraser. Each stroke is one undo step. */
    DRAW(isDraft = false),
    ;

    /** Tools that show the whole rotated image instead of the cropped result. */
    internal val isGeometry: Boolean get() = this == CROP || this == ROTATE

    public companion object {
        /** Tools available in this version, in rail order. */
        public val defaults: Set<ImageTool> = linkedSetOf(CROP, ROTATE, ADJUST, FILTER, TEXT, STICKER, DRAW)
    }
}
