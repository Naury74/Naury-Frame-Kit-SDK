package com.naury.framekit.core.geometry

import com.naury.framekit.core.model.PixelSize

/**
 * Edits that the user sees as rotating or mirroring the current result.
 *
 * Because flip is applied after rotation, a visual clockwise turn of a mirrored image is a
 * counter-clockwise quarter turn in the model. These functions keep that bookkeeping in one place and
 * move the crop with the content so that the selected area is preserved.
 */
public object GeometryOperations {

    /** Rotates the visible result 90° clockwise. */
    public fun rotateClockwise(geometry: GeometryEdit): GeometryEdit {
        val step = if (geometry.flipX != geometry.flipY) -1 else 1
        val crop = geometry.crop
        return geometry.copy(
            quarterTurns = Math.floorMod(geometry.quarterTurns + step, 4),
            crop = RectN(1.0 - crop.bottom, crop.left, 1.0 - crop.top, crop.right),
        )
    }

    /** Rotates the visible result 90° counter-clockwise. */
    public fun rotateCounterClockwise(geometry: GeometryEdit): GeometryEdit =
        rotateClockwise(rotateClockwise(rotateClockwise(geometry)))

    /** Mirrors the visible result left to right. */
    public fun flipHorizontal(geometry: GeometryEdit): GeometryEdit {
        val crop = geometry.crop
        return geometry.copy(flipX = !geometry.flipX, crop = RectN(1.0 - crop.right, crop.top, 1.0 - crop.left, crop.bottom))
    }

    /** Mirrors the visible result top to bottom. */
    public fun flipVertical(geometry: GeometryEdit): GeometryEdit {
        val crop = geometry.crop
        return geometry.copy(flipY = !geometry.flipY, crop = RectN(crop.left, 1.0 - crop.bottom, crop.right, 1.0 - crop.top))
    }

    /**
     * Applies a new straighten angle to [start] and fits the crop into the new image area.
     *
     * Always derive from the geometry at the start of the gesture. Applying small steps one after
     * another would shrink the crop permanently even when the slider returns to its start value.
     *
     * @param degrees clockwise angle, clamped to `-45.0..45.0`.
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

    /** Replaces the crop with the largest centered crop for [aspect]. */
    public fun withAspect(sourceSize: PixelSize, geometry: GeometryEdit, aspect: CropAspectRatio): GeometryEdit =
        geometry.copy(crop = CropBoundsCalculator.maxCrop(GeometryFrame(sourceSize, geometry), aspect))
}
