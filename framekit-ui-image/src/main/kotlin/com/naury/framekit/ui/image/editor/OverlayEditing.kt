package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import kotlin.math.abs
import kotlin.math.roundToInt

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

    /**
     * 제스처 시작 시점의 변환인 [start]에 제스처를 적용한다.
     *
     * @param panX 정규화 캔버스 단위의 가로 이동량.
     * @param snapDistance 중심이 캔버스 중앙으로 스냅되는, 캔버스 중앙으로부터의 거리(정규화 단위).
     */
    fun transform(
        start: OverlayTransform,
        panX: Double,
        panY: Double,
        zoom: Double,
        rotationDegrees: Double,
        snapDistanceX: Double,
        snapDistanceY: Double,
    ): OverlayTransform {
        var x = (start.center.x + panX).coerceIn(0.0, 1.0)
        var y = (start.center.y + panY).coerceIn(0.0, 1.0)
        if (abs(x - 0.5) <= snapDistanceX) x = 0.5
        if (abs(y - 0.5) <= snapDistanceY) y = 0.5
        val rotation = snapRotation(start.rotationDegrees + rotationDegrees)
        val scale = (start.scale * zoom).coerceIn(OverlayTransform.MIN_SCALE, OverlayTransform.MAX_SCALE)
        return start.copy(center = PointN(x, y), scale = scale, rotationDegrees = rotation)
    }

    /** ±3° 이내면 0, 90, 180, 270도로 스냅하고 -180..180으로 정규화한다. */
    fun snapRotation(degrees: Double): Double {
        var normalized = ((degrees % 360) + 540) % 360 - 180
        val nearest = (normalized / 90).roundToInt() * 90.0
        if (abs(normalized - nearest) <= ROTATION_SNAP_DEGREES) normalized = nearest
        return if (normalized == -180.0) 180.0 else normalized
    }

    /** 직전 점과의 거리가 [minDistance](정규화)보다 가깝지 않으면 점을 추가한다. */
    fun appendPoint(stroke: DrawingStroke, point: StrokePoint, minDistanceX: Double, minDistanceY: Double): DrawingStroke {
        val last = stroke.points.lastOrNull()
        if (last != null && abs(last.x - point.x) < minDistanceX && abs(last.y - point.y) < minDistanceY) return stroke
        if (stroke.points.size >= DrawingStroke.MAX_POINTS) return stroke
        return stroke.copy(points = stroke.points + point)
    }

    private const val DUPLICATE_OFFSET = 0.04
    private const val ROTATION_SNAP_DEGREES = 3.0
}
