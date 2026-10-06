package com.naury.framekit.image.export

/** Progress stages reported while an image export runs. */
public enum class ImageExportStage {
    PREPARING,
    RENDERING,
    ENCODING,
    FINALIZING,
}
