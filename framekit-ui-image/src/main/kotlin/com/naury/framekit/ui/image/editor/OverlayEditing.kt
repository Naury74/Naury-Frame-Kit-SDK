package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.ui.tool.OverlayGestures
import kotlin.math.abs

/** 오버레이용 순수 프로젝트 편집. 직접 테스트할 수 있도록 ViewModel 밖에 둔다. */
internal object OverlayEditing {

    fun replace(project: ImageProject, overlay: ImageOverlay): ImageProject =
        project.copy(overlays = project.overlays.map { if (it.id == overlay.id) overlay else it })

    fun remove(project: ImageProject, id: String): ImageProject = project.copy(overlays = project.overlays.filterNot { it.id == id })

    fun duplicate(project: ImageProject, id: String, newId: String): ImageProject {
        val source = project.overlays.firstOrNull { it.id == id } ?: return project
        val center = source.transform.center
        val moved = source.transform.copy(center = PointN((center.x + DUPLICATE_OFFSET).coerceAtMost(1.0), (center.y + DUPLICATE_OFFSET).coerceAtMost(1.0)))
        val copy = when (source) {
            is ImageOverlay.Text -> source.copy(id = newId, transform = moved)
            is ImageOverlay.Sticker -> source.copy(id = newId, transform = moved)
        }
        return project.copy(overlays = project.overlays + copy)
    }

    fun transform(
        start: OverlayTransform,
        panX: Double,
        panY: Double,
        zoom: Double,
        rotationDegrees: Double,
        snapDistanceX: Double,
        snapDistanceY: Double,
    ): OverlayTransform = OverlayGestures.transform(start, panX, panY, zoom, rotationDegrees, snapDistanceX, snapDistanceY)

    fun snapRotation(degrees: Double): Double = OverlayGestures.snapRotation(degrees)

    /** 직전 점과의 거리가 [minDistance](정규화)보다 가깝지 않으면 점을 추가한다. */
    fun appendPoint(stroke: DrawingStroke, point: StrokePoint, minDistanceX: Double, minDistanceY: Double): DrawingStroke {
        val last = stroke.points.lastOrNull()
        if (last != null && abs(last.x - point.x) < minDistanceX && abs(last.y - point.y) < minDistanceY) return stroke
        if (stroke.points.size >= DrawingStroke.MAX_POINTS) return stroke
        return stroke.copy(points = stroke.points + point)
    }

    private const val DUPLICATE_OFFSET = 0.04
}
