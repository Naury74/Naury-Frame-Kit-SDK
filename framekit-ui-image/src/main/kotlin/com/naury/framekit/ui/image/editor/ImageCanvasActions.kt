package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.CropHandle

/** Canvas gestures forwarded to the editor state holder. Coordinates are normalized unless stated. */
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
