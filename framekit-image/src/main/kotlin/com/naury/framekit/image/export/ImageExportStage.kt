package com.naury.framekit.image.export

/** 이미지 내보내기 중 보고되는 진행 단계다. */
public enum class ImageExportStage {
    PREPARING,
    RENDERING,
    ENCODING,
    FINALIZING,
}
