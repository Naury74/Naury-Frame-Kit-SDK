package com.naury.framekit.ui.video.contract

/** Tools of the video editor. Only tools whose edits are played back and exported are listed. */
public enum class VideoTool {
    TRIM,
    CROP,
    ROTATE,
    ADJUST,
    FILTER,
    SPEED,
    AUDIO,
    PRIVACY,
    ;

    /** Draft tools are applied or cancelled as a whole; the others commit each change. */
    internal val isDraft: Boolean get() = this == TRIM || this == CROP || this == ROTATE

    /** Geometry tools show the whole rotated frame with the crop frame on top. */
    internal val isGeometry: Boolean get() = this == CROP || this == ROTATE

    public companion object {
        public val defaults: Set<VideoTool> = entries.toSet()
    }
}
