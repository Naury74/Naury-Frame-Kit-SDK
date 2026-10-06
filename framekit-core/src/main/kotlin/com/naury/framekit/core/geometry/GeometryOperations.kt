package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize

/**
 * 사용자에게 현재 결과를 회전하거나 반전하는 것으로 보이는 편집.
 *
 * 뒤집기는 회전 뒤에 적용되므로, 반전된 이미지를 화면상 시계 방향으로 돌리는 것은 모델에서는 반시계
 * 방향 90° 회전이다. 이 함수들은 그 계산을 한곳에서 처리하고, 선택 영역이 유지되도록 자르기를 내용과
 * 함께 옮긴다.
 */
public object GeometryOperations {

    /** 보이는 결과를 시계 방향으로 90° 회전한다. */
    public fun rotateClockwise(geometry: GeometryEdit): GeometryEdit {
        val step = if (geometry.flipX != geometry.flipY) -1 else 1
        val crop = geometry.crop
        return geometry.copy(
            quarterTurns = Math.floorMod(geometry.quarterTurns + step, 4),
            crop = RectN(1.0 - crop.bottom, crop.left, 1.0 - crop.top, crop.right),
        )
    }

    /** 보이는 결과를 반시계 방향으로 90° 회전한다. */
    public fun rotateCounterClockwise(geometry: GeometryEdit): GeometryEdit =
        rotateClockwise(rotateClockwise(rotateClockwise(geometry)))

    /** 보이는 결과를 좌우 반전한다. */
    public fun flipHorizontal(geometry: GeometryEdit): GeometryEdit {
        val crop = geometry.crop
        return geometry.copy(flipX = !geometry.flipX, crop = RectN(1.0 - crop.right, crop.top, 1.0 - crop.left, crop.bottom))
    }

    /** 보이는 결과를 상하 반전한다. */
    public fun flipVertical(geometry: GeometryEdit): GeometryEdit {
        val crop = geometry.crop
        return geometry.copy(flipY = !geometry.flipY, crop = RectN(crop.left, 1.0 - crop.bottom, crop.right, 1.0 - crop.top))
    }

    /**
     * [start]에 새 수평 보정 각도를 적용하고 자르기를 새 이미지 영역에 맞춘다.
     *
     * 항상 제스처 시작 시점의 기하에서 계산한다. 작은 단계를 연달아 적용하면 슬라이더가 시작 값으로
     * 돌아와도 자르기가 영구적으로 줄어든다.
     *
     * @param degrees 시계 방향 각도. `-45.0..45.0`으로 제한된다.
     */
    public fun withStraighten(sourceSize: PixelSize, start: GeometryEdit, degrees: Double): GeometryEdit {
        require(degrees.isFinite()) { "Straighten angle must be finite" }
        val clamped = degrees.coerceIn(GeometryEdit.MIN_STRAIGHTEN_DEGREES, GeometryEdit.MAX_STRAIGHTEN_DEGREES)
        val startFrame = GeometryFrame(sourceSize, start)
        val next = start.copy(straightenDegrees = clamped)
        val nextFrame = GeometryFrame(sourceSize, next)
        // crop의 픽셀 크기와 중심 오프셋을 유지한 채 새 G 공간으로 옮긴 뒤 이미지 영역 안으로 줄인다.
        val crop = start.crop
        val centerOffsetX = (crop.centerX - 0.5) * startFrame.bounds.width
        val centerOffsetY = (crop.centerY - 0.5) * startFrame.bounds.height
        val cropSize = startFrame.cropPixelSize(crop)
        val moved = RectN.fromCenter(
            centerX = 0.5 + centerOffsetX / nextFrame.bounds.width,
            centerY = 0.5 + centerOffsetY / nextFrame.bounds.height,
            width = cropSize.width / nextFrame.bounds.width,
            height = cropSize.height / nextFrame.bounds.height,
        )
        return next.copy(crop = CropBoundsCalculator.clampCrop(nextFrame, moved))
    }

    /** 자르기를 [aspect]에 대한 가장 큰 중앙 자르기로 바꾼다. */
    public fun withAspect(sourceSize: PixelSize, geometry: GeometryEdit, aspect: CropAspectRatio): GeometryEdit =
        geometry.copy(crop = CropBoundsCalculator.maxCrop(GeometryFrame(sourceSize, geometry), aspect))
}
