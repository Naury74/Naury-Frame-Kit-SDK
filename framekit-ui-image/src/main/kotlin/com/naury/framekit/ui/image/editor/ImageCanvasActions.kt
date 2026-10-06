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
}
