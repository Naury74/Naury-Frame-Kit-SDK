package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.CropHandle

/** 에디터 상태 홀더로 전달되는 캔버스 제스처. 따로 명시하지 않으면 좌표는 정규화 값이다. */
internal interface ImageCanvasActions {
    fun onViewportSize(width: Int, height: Int)
    fun beginCropDrag(handle: CropHandle)
    fun dragCrop(dx: Double, dy: Double)
    fun endCropDrag()
    fun showOriginal(show: Boolean)
    fun selectOverlay(id: String?)
    fun editText(id: String)
    fun beginOverlayGesture(id: String)
    fun updateOverlayGesture(panX: Double, panY: Double, zoom: Double, rotation: Double, snapX: Double, snapY: Double)
    fun finishOverlayGesture()
    fun deleteOverlay(id: String)
    fun duplicateOverlay(id: String)
    fun beginStroke(x: Double, y: Double, pressure: Double)
    fun extendStroke(x: Double, y: Double, pressure: Double, minDistanceX: Double, minDistanceY: Double)
    fun finishStroke()

    /** 두 번째 손가락이 닿아 확대·이동으로 바뀌었다. 그리던 획은 남기지 않는다. */
    fun cancelStroke()

    /** 문서 보정에서 모서리 [corner](0: 왼쪽 위부터 시계 방향)를 정규화 좌표로 옮긴다. */
    fun moveDocumentCorner(corner: Int, x: Double, y: Double)
}
