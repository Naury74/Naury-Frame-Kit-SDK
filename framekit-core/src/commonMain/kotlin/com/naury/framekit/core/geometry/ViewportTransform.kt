package com.naury.framekit.core.geometry

import kotlin.math.min

/**
 * 정규화된 콘텐츠 좌표(C, 자르기 중에는 G)와 뷰포트 픽셀(V) 사이를 양방향으로 변환한다.
 *
 * 콘텐츠는 뷰포트에서 [padding]을 뺀 영역 안에 맞춰 중앙에 배치된다. [zoom]은 뷰포트 중심을 기준으로
 * 확대하고 [panX]/[panY]는 그 결과를 뷰포트 픽셀 단위로 이동한다. 여기의 어떤 값도 화면 밀도에
 * 의존하지 않으며, 뷰포트 값은 프로젝트에 절대 저장되지 않는다.
 *
 * @param contentSize 임의 픽셀 단위의 콘텐츠 크기. 가로세로 비율만 의미가 있다.
 * @throws IllegalArgumentException 크기가 양수가 아니거나 [zoom]이 양수가 아닐 때.
 */
public class ViewportTransform(
    contentSize: Size2D,
    public val viewportWidth: Double,
    public val viewportHeight: Double,
    padding: Double = 0.0,
    zoom: Double = 1.0,
    panX: Double = 0.0,
    panY: Double = 0.0,
) {
    init {
        require(contentSize.width > 0.0 && contentSize.height > 0.0) { "Content size must be positive" }
        require(viewportWidth > 0.0 && viewportHeight > 0.0) { "Viewport size must be positive" }
        require(zoom > 0.0) { "Zoom must be positive" }
    }

    /** zoom 1에서 뷰포트 픽셀 기준으로 맞춰진 콘텐츠 크기. */
    public val fittedSize: Size2D

    /** 정규화 콘텐츠 → 뷰포트 픽셀. */
    public val contentToViewport: Affine2D

    /** 뷰포트 픽셀 → 정규화 콘텐츠. */
    public val viewportToContent: Affine2D

    init {
        val availableWidth = (viewportWidth - 2 * padding).coerceAtLeast(1.0)
        val availableHeight = (viewportHeight - 2 * padding).coerceAtLeast(1.0)
        val scale = min(availableWidth / contentSize.width, availableHeight / contentSize.height)
        fittedSize = Size2D(contentSize.width * scale, contentSize.height * scale)
        val fit = Affine2D.translate((viewportWidth - fittedSize.width) / 2.0, (viewportHeight - fittedSize.height) / 2.0) *
            Affine2D.scale(fittedSize.width, fittedSize.height)
        val zoomAroundCenter = Affine2D.translate(viewportWidth / 2.0 + panX, viewportHeight / 2.0 + panY) *
            Affine2D.scale(zoom) *
            Affine2D.translate(-viewportWidth / 2.0, -viewportHeight / 2.0)
        contentToViewport = zoomAroundCenter * fit
        viewportToContent = contentToViewport.inverted()
    }

    public fun toViewport(point: PointN): PointN = contentToViewport.map(point)

    /** 뷰포트 픽셀 아래의 콘텐츠 점. 픽셀이 레터박스 영역에 있으면 `null`. */
    public fun toContent(x: Double, y: Double): PointN? {
        val point = viewportToContent.map(x, y)
        return point.takeIf { it.x in 0.0..1.0 && it.y in 0.0..1.0 }
    }

    /** 레터박스 영역을 거부하지 않고 구한 뷰포트 픽셀 아래의 콘텐츠 점. */
    public fun toContentUnbounded(x: Double, y: Double): PointN = viewportToContent.map(x, y)

    /** 정규화 콘텐츠 사각형이 덮는 뷰포트 사각형. */
    public fun toViewport(rect: RectN): RectN {
        val topLeft = toViewport(PointN(rect.left, rect.top))
        val bottomRight = toViewport(PointN(rect.right, rect.bottom))
        return RectN(topLeft.x, topLeft.y, bottomRight.x, bottomRight.y)
    }
}
