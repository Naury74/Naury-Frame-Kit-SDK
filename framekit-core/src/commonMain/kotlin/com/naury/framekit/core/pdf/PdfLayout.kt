package com.naury.framekit.core.pdf

import com.naury.framekit.core.model.PixelSize
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** PDF 쪽 크기. 치수는 mm다. */
public enum class PdfPageSize(public val widthMm: Double, public val heightMm: Double) {
    /** 쪽을 사진 크기에 맞춘다. 여백만 둘레에 더한다. */
    FIT_IMAGE(0.0, 0.0),
    A4(210.0, 297.0),
    A5(148.0, 210.0),
    LETTER(215.9, 279.4),
    LEGAL(215.9, 355.6),
}

/** 종이 방향. [AUTO]는 사진이 가로로 길면 가로 쪽을 쓴다. */
public enum class PdfOrientation { AUTO, PORTRAIT, LANDSCAPE }

/**
 * 쪽 하나의 배치. 좌표는 PDF 단위(pt, 1/72인치)이고 원점은 쪽의 왼쪽 아래다.
 *
 * @property pixels 쪽에 그릴 이미지의 픽셀 크기. 고른 해상도(dpi)에서 그림 영역을 넘지 않고, 원본보다 키우지 않는다.
 */
public data class PdfPageLayout(
    val pageWidthPt: Double,
    val pageHeightPt: Double,
    val imageXPt: Double,
    val imageYPt: Double,
    val imageWidthPt: Double,
    val imageHeightPt: Double,
    val pixels: PixelSize,
)

/** 사진을 쪽에 맞춰 배치하는 계산. 사진 비율은 유지하고 가운데 놓는다. */
public object PdfLayout {

    public const val POINTS_PER_INCH: Double = 72.0
    public const val MM_PER_INCH: Double = 25.4

    /**
     * @param imageSize 편집 결과(자르기 뒤)의 원래 픽셀 크기.
     * @param dpi 쪽에 그릴 해상도. 결과 이미지는 그림 영역 × dpi를 넘지 않는다.
     * @param marginMm 네 변의 여백.
     * @param maxPixels 한 쪽 이미지의 픽셀 수 상한.
     */
    public fun layout(
        imageSize: PixelSize,
        pageSize: PdfPageSize,
        orientation: PdfOrientation,
        marginMm: Double,
        dpi: Int,
        maxPixels: Long,
    ): PdfPageLayout {
        require(imageSize.isValid) { "Image size must be positive" }
        val margin = mmToPt(marginMm)
        val landscapeImage = imageSize.width > imageSize.height
        if (pageSize == PdfPageSize.FIT_IMAGE) {
            // 사진 원래 크기를 dpi로 환산한 크기의 쪽. 픽셀 수 상한을 넘으면 줄인다.
            val pixels = limitPixels(imageSize, maxPixels)
            val width = pixels.width * POINTS_PER_INCH / dpi
            val height = pixels.height * POINTS_PER_INCH / dpi
            return PdfPageLayout(width + 2 * margin, height + 2 * margin, margin, margin, width, height, pixels)
        }
        val landscape = when (orientation) {
            PdfOrientation.AUTO -> landscapeImage
            PdfOrientation.PORTRAIT -> false
            PdfOrientation.LANDSCAPE -> true
        }
        val shortPt = mmToPt(min(pageSize.widthMm, pageSize.heightMm))
        val longPt = mmToPt(max(pageSize.widthMm, pageSize.heightMm))
        val pageWidth = if (landscape) longPt else shortPt
        val pageHeight = if (landscape) shortPt else longPt
        val boxWidth = max(1.0, pageWidth - 2 * margin)
        val boxHeight = max(1.0, pageHeight - 2 * margin)
        val scale = min(boxWidth / imageSize.width, boxHeight / imageSize.height)
        val drawWidth = imageSize.width * scale
        val drawHeight = imageSize.height * scale
        // 그림 영역을 dpi로 환산한 픽셀 수를 넘기지 않고, 원본보다 키우지 않는다.
        val targetWidth = min(imageSize.width.toDouble(), drawWidth / POINTS_PER_INCH * dpi)
        val ratio = targetWidth / imageSize.width
        val pixels = limitPixels(
            PixelSize(max(1, (imageSize.width * ratio).roundToInt()), max(1, (imageSize.height * ratio).roundToInt())),
            maxPixels,
        )
        return PdfPageLayout(
            pageWidthPt = pageWidth,
            pageHeightPt = pageHeight,
            imageXPt = (pageWidth - drawWidth) / 2,
            imageYPt = (pageHeight - drawHeight) / 2,
            imageWidthPt = drawWidth,
            imageHeightPt = drawHeight,
            pixels = pixels,
        )
    }

    public fun mmToPt(mm: Double): Double = mm / MM_PER_INCH * POINTS_PER_INCH

    private fun limitPixels(size: PixelSize, maxPixels: Long): PixelSize {
        if (size.pixelCount <= maxPixels) return size
        val scale = kotlin.math.sqrt(maxPixels.toDouble() / size.pixelCount)
        return PixelSize(max(1, floor(size.width * scale).toInt()), max(1, floor(size.height * scale).toInt()))
    }
}
