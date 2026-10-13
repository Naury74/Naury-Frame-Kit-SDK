package com.naury.framekit.scanner

import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.geometry.PointN
import kotlin.math.abs
import kotlin.math.max

/**
 * 카메라 프레임마다 찾은 문서 모서리를 이어 받아, 흔들림을 줄인 모서리와 자동 촬영 시점을 정한다.
 *
 * - 새 모서리를 이전 값과 섞어(지수 평활) 화면의 테두리가 떨리지 않게 한다.
 * - 한두 프레임 놓쳐도 바로 지우지 않고 [lostGraceMs] 동안 이전 테두리를 유지한다.
 * - 모서리가 [moveTolerance](정규화 좌표) 안에서 [stableMs] 동안 머무르면 촬영할 때가 됐다고 본다.
 * - 한 번 찍은 뒤에는 문서가 화면에서 빠지거나 크게 움직일 때까지(다음 쪽으로 바꿀 때까지) 다시 찍지 않는다.
 *
 * @param smoothing 새 값의 비중, `0..1`. 클수록 빠르게 따라가고 덜 부드럽다.
 */
internal class QuadTracker(
    private val stableMs: Long = STABLE_MS,
    private val moveTolerance: Double = MOVE_TOLERANCE,
    private val lostGraceMs: Long = LOST_GRACE_MS,
    private val rearmShift: Double = REARM_SHIFT,
    private val smoothing: Double = SMOOTHING,
) {
    /** 화면에 그릴 모서리. 문서가 없으면 `null`. */
    var quad: DocumentQuad? = null
        private set

    private var lastSeenMs = 0L
    private var stableSinceMs: Long? = null
    private var anchor: DocumentQuad? = null
    private var armed = true
    private var capturedAt: DocumentQuad? = null

    /** 지금 상태. [progress]는 자동 촬영까지의 진행(`0..1`), [ready]면 지금 찍으면 된다. */
    data class State(val quad: DocumentQuad?, val progress: Float, val ready: Boolean)

    /** 프레임 하나의 감지 결과([found], 없으면 `null`)를 [nowMs] 시각에 반영한다. */
    fun update(found: DocumentQuad?, nowMs: Long): State {
        if (found == null) {
            if (quad != null && nowMs - lastSeenMs > lostGraceMs) {
                quad = null
                anchor = null
                stableSinceMs = null
                // 문서가 화면에서 빠졌으니 다음 쪽을 찍을 수 있다.
                armed = true
                capturedAt = null
            }
            return state(nowMs)
        }
        lastSeenMs = nowMs
        val previous = quad
        val smoothed = if (previous == null) found else blend(previous, found)
        quad = smoothed
        val reference = anchor
        if (reference == null || shift(reference, found) > moveTolerance) {
            anchor = found
            stableSinceMs = nowMs
        }
        // 찍은 자리에서 크게 움직였으면 다른 쪽으로 본다.
        capturedAt?.let { if (shift(it, found) > rearmShift) { armed = true; capturedAt = null } }
        return state(nowMs)
    }

    /** 찍었다고 알린다. 문서를 바꿀 때까지 자동 촬영을 멈춘다. */
    fun onCaptured() {
        armed = false
        capturedAt = quad
        stableSinceMs = null
        anchor = null
    }

    private fun state(nowMs: Long): State {
        val since = stableSinceMs
        if (quad == null || since == null || !armed) return State(quad, 0f, false)
        val progress = ((nowMs - since).toFloat() / stableMs).coerceIn(0f, 1f)
        return State(quad, progress, progress >= 1f)
    }

    private fun blend(a: DocumentQuad, b: DocumentQuad): DocumentQuad {
        fun mix(p: PointN, q: PointN) = PointN(p.x + (q.x - p.x) * smoothing, p.y + (q.y - p.y) * smoothing)
        return DocumentQuad(mix(a.topLeft, b.topLeft), mix(a.topRight, b.topRight), mix(a.bottomRight, b.bottomRight), mix(a.bottomLeft, b.bottomLeft))
    }

    private fun shift(a: DocumentQuad, b: DocumentQuad): Double =
        a.corners.zip(b.corners).maxOf { (p, q) -> max(abs(p.x - q.x), abs(p.y - q.y)) }

    private companion object {
        const val STABLE_MS = 1200L
        const val MOVE_TOLERANCE = 0.025
        const val LOST_GRACE_MS = 400L
        const val REARM_SHIFT = 0.12
        const val SMOOTHING = 0.45
    }
}
