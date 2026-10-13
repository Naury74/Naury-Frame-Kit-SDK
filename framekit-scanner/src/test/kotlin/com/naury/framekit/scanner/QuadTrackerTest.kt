package com.naury.framekit.scanner

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.geometry.PointN
import org.junit.Test

class QuadTrackerTest {

    private fun quad(dx: Double = 0.0) = DocumentQuad(PointN(0.2 + dx, 0.2), PointN(0.8 + dx, 0.2), PointN(0.8 + dx, 0.8), PointN(0.2 + dx, 0.8))

    @Test
    fun `holding the page still for a moment makes it ready once`() {
        val tracker = QuadTracker()
        assertThat(tracker.update(quad(), 0).ready).isFalse()
        assertThat(tracker.update(quad(0.005), 600).progress).isWithin(0.01f).of(0.5f)
        assertThat(tracker.update(quad(), 1200).ready).isTrue()

        tracker.onCaptured()
        // 같은 쪽을 계속 비추면 다시 찍지 않는다.
        assertThat(tracker.update(quad(), 1300).ready).isFalse()
        assertThat(tracker.update(quad(), 4000).ready).isFalse()
    }

    @Test
    fun `moving resets the timer and a new page rearms auto capture`() {
        val tracker = QuadTracker()
        tracker.update(quad(), 0)
        tracker.update(quad(), 1000)
        // 크게 움직이면 처음부터 다시 잰다.
        assertThat(tracker.update(quad(0.1), 1100).progress).isEqualTo(0f)
        assertThat(tracker.update(quad(0.1), 2300).ready).isTrue()
        tracker.onCaptured()

        // 문서를 빼면(몇 프레임 동안 못 찾으면) 테두리가 사라지고, 다음 쪽은 다시 자동으로 찍힌다.
        assertThat(tracker.update(null, 2400).quad).isNotNull()
        assertThat(tracker.update(null, 2900).quad).isNull()
        tracker.update(quad(), 3000)
        assertThat(tracker.update(quad(), 4300).ready).isTrue()
    }

    @Test
    fun `smoothing moves the drawn corners part of the way`() {
        val tracker = QuadTracker()
        tracker.update(quad(), 0)
        val drawn = tracker.update(quad(0.02), 100).quad!!
        assertThat(drawn.topLeft.x).isGreaterThan(0.2)
        assertThat(drawn.topLeft.x).isLessThan(0.22)
    }
}
