package com.naury.framekit.ui.image.contract

/** Tools of the image editor. Only tools whose edits are rendered and exported are listed. */
public enum class ImageTool {
    /** Crop with free or fixed ratios. */
    CROP,

    /** 90° rotation, horizontal and vertical flip, and straighten up to ±45°. */
    ROTATE,
    ;

    public companion object {
        /** Tools available in this version, in rail order. */
        public val defaults: Set<ImageTool> = linkedSetOf(CROP, ROTATE)
    }
}
