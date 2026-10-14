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

    /** 두 번째 손가락이 닿아 확대·이동으로 바뀌었다. 그리던 획이나 도형은 남기지 않는다. */
    fun cancelStroke()

    /** 도형 그리기를 ([x], [y])에서 시작한다. 커밋은 [finishShape]가 한다. */
    fun beginShape(x: Double, y: Double)

    /**
     * 시작점에서 ([x], [y])까지 끌어 도형 크기·방향을 정한다.
     *
     * @param aspect 출력 캔버스의 너비 ÷ 높이. 정사각형·45도로 맞출 때 쓴다.
     */
    fun dragShape(x: Double, y: Double, aspect: Double)

    /** 도형 그리기를 마친다. 거의 끌지 않았으면 도형을 남기지 않는다. */
    fun finishShape()

    /** 도형 [id]의 크기 손잡이를 잡았다. 마칠 때는 [finishOverlayGesture]를 부른다. */
    fun beginShapeResize(id: String)

    /**
     * 크기 손잡이를 잡은 뒤 ([dx], [dy])만큼 옮겼다.
     *
     * @param aspect 출력 캔버스의 너비 ÷ 높이.
     */
    fun resizeShape(dx: Double, dy: Double, aspect: Double)

    /** 사진의 빈 곳을 한 번 눌렀다. 도구가 닫혀 있으면 조작부를 숨기거나 다시 보인다. */
    fun toggleChrome()

    /** 문서 보정에서 모서리 [corner](0: 왼쪽 위부터 시계 방향)를 정규화 좌표로 옮긴다. */
    fun moveDocumentCorner(corner: Int, x: Double, y: Double)
}
