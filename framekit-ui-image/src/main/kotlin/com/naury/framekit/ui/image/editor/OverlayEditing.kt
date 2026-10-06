package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import kotlin.math.abs
import kotlin.math.roundToInt

/** Pure project edits for overlays, kept out of the ViewModel so they can be tested directly. */
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
     * Applies a gesture to [start], the transform when the gesture began.
     *
     * @param panX horizontal movement in normalized canvas units.
     * @param snapDistance distance from the canvas center, in normalized units, within which the
     *   center snaps to it.
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

    /** Snaps to 0, 90, 180 or 270 degrees within ±3° and normalizes to -180..180. */
    fun snapRotation(degrees: Double): Double {
        var normalized = ((degrees % 360) + 540) % 360 - 180
        val nearest = (normalized / 90).roundToInt() * 90.0
        if (abs(normalized - nearest) <= ROTATION_SNAP_DEGREES) normalized = nearest
        return if (normalized == -180.0) 180.0 else normalized
    }

    /** Appends a point unless it is closer than [minDistance] (normalized) to the previous one. */
    fun appendPoint(stroke: DrawingStroke, point: StrokePoint, minDistanceX: Double, minDistanceY: Double): DrawingStroke {
        val last = stroke.points.lastOrNull()
        if (last != null && abs(last.x - point.x) < minDistanceX && abs(last.y - point.y) < minDistanceY) return stroke
        if (stroke.points.size >= DrawingStroke.MAX_POINTS) return stroke
        return stroke.copy(points = stroke.points + point)
    }

    private const val DUPLICATE_OFFSET = 0.04
    private const val ROTATION_SNAP_DEGREES = 3.0
}
