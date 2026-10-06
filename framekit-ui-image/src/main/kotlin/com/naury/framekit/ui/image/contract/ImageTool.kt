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
    ;

    public companion object {
        /** Tools available in this version, in rail order. */
        public val defaults: Set<ImageTool> = linkedSetOf(CROP, ROTATE, ADJUST, FILTER)
    }
}
