package com.naury.framekit.core.document

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import org.junit.Test
import kotlin.math.abs
import kotlin.math.hypot

class DocumentDetectorTest {

    // 어두운 책상(40) 위에 밝은 종이(220)를 사각형 [quad]로 그린 이미지. [text]면 종이 위에 글자 줄을 그린다.
    private fun scene(width: Int, height: Int, quad: List<Pair<Double, Double>>, paper: Int = 220, desk: Int = 40, text: Boolean = true): IntArray {
        val pts = quad.map { (x, y) -> x * width to y * height }
        val ink = if (paper > desk) paper - 150 else paper + 150
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (!inside(pts, x + 0.5, y + 0.5)) {
                desk
            } else if (text && y % 9 in 3..4 && x % 7 in 1..4) {
                ink.coerceIn(0, 255)
            } else {
                paper
            }
        }
    }

    private fun inside(poly: List<Pair<Double, Double>>, x: Double, y: Double): Boolean {
        var result = false
        var j = poly.size - 1
        for (i in poly.indices) {
            val (xi, yi) = poly[i]
            val (xj, yj) = poly[j]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) result = !result
            j = i
        }
        return result
    }

    private fun assertNear(actual: PointN, x: Double, y: Double) {
        assertThat(abs(actual.x - x)).isLessThan(0.03)
        assertThat(abs(actual.y - y)).isLessThan(0.03)
    }

    @Test
    fun `tilted paper on a dark desk gives its four corners`() {
        val quad = listOf(0.22 to 0.12, 0.80 to 0.18, 0.86 to 0.88, 0.14 to 0.82)
        val found = DocumentDetector.detect(scene(200, 260, quad), 200, 260)!!
        assertNear(found.topLeft, 0.22, 0.12)
        assertNear(found.topRight, 0.80, 0.18)
        assertNear(found.bottomRight, 0.86, 0.88)
        assertNear(found.bottomLeft, 0.14, 0.82)
    }

    @Test
    fun `dark document on a light background is found too`() {
        val quad = listOf(0.3 to 0.25, 0.7 to 0.25, 0.7 to 0.75, 0.3 to 0.75)
        val found = DocumentDetector.detect(scene(160, 160, quad, paper = 30, desk = 230), 160, 160)!!
        assertNear(found.topLeft, 0.3, 0.25)
        assertNear(found.bottomRight, 0.7, 0.75)
    }

    @Test
    fun `flat images or full frame documents return null`() {
        assertThat(DocumentDetector.detect(IntArray(100 * 100) { 128 }, 100, 100)).isNull()
        val full = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 1.0, 0.0 to 1.0)
        assertThat(DocumentDetector.detect(scene(100, 100, full), 100, 100)).isNull()
    }

    @Test
    fun `dense forms with table lines that nearly fill the width are still documents`() {
        // 가까이 찍어 좌우가 거의 사진 끝까지 차는 서식 문서. 표 선과 빽빽한 글자(어두운 잉크)가 종이를 칸으로 나눈다.
        val width = 192
        val height = 256
        val quad = listOf(0.01 to 0.12, 0.99 to 0.11, 0.99 to 0.86, 0.02 to 0.87)
        val base = scene(width, height, quad, paper = 225, desk = 25, text = false)
        val inked = IntArray(base.size) { i ->
            val x = i % width
            val y = i / width
            val onPaper = base[i] > 128
            val tableLine = (y in 60..200 && (y % 14 == 0 || x % 24 == 0))
            val text = (y in 40..56 || y in 90..190) && (x / 3 + y / 2) % 3 != 0 && x % 24 in 3..20
            if (onPaper && (tableLine || text)) 50 else base[i]
        }
        val analysis = DocumentDetector.analyze(inked, width, height)
        assertThat(analysis.reason).startsWith("ok")
        val found = analysis.quad
        assertNear(found!!.topLeft, 0.01, 0.12)
        assertNear(found.bottomRight, 0.99, 0.86)
    }

    @Test
    fun `close ups that touch three edges count only when the page has text`() {
        val width = 192
        val height = 256
        // 위·좌·우가 사진 끝까지 차고 아래쪽에만 바탕이 보이는 근접 촬영.
        val quad = listOf(0.0 to 0.0, 1.0 to 0.0, 1.0 to 0.82, 0.0 to 0.84)
        val blank = scene(width, height, quad, paper = 225, desk = 25, text = false)
        assertThat(DocumentDetector.analyze(blank, width, height).quad).isNull()
        val text = IntArray(blank.size) { i ->
            val x = i % width
            val y = i / width
            if (blank[i] > 128 && y % 12 in 4..6 && x % 20 in 3..16) 40 else blank[i]
        }
        val analysis = DocumentDetector.analyze(text, width, height)
        assertThat(analysis.reason).startsWith("ok")
        assertNear(analysis.quad!!.bottomRight, 1.0, 0.82)
        // 줄인 사진의 작은 글자처럼 1px 굵기의 흐린 글자만 있어도 글자로 센다.
        val fine = IntArray(blank.size) { i ->
            val x = i % width
            val y = i / width
            if (blank[i] > 128 && y % 9 == 4 && x % 5 != 0) 150 else blank[i]
        }
        assertThat(DocumentDetector.analyze(fine, width, height).reason).startsWith("ok")
    }

    @Test
    fun `textured bright areas such as buildings or roads are not paper`() {
        // 밝기가 넓게 퍼진(130~250) 밝은 영역이 사진 폭을 채우고 아래에 어두운 바닥이 있는 장면.
        val width = 200
        val height = 200
        val random = java.util.Random(7)
        val scene = IntArray(width * height) { i ->
            val y = i / width
            if (y < 140) 130 + random.nextInt(121) else 30
        }
        assertThat(DocumentDetector.analyze(scene, width, height).quad).isNull()
    }

    @Test
    fun `smooth bright panels such as a shelf wall are not documents`() {
        // 어두운 바탕 안에 밝기가 부드럽게 변하는(그늘진) 크림색 판. 사각형이지만 글자가 없다.
        val width = 200
        val height = 200
        val wall = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x in 40..160 && y in 30..170) 170 + (x - 40) / 3 else 45
        }
        assertThat(DocumentDetector.analyze(wall, width, height).reason).startsWith("no text strokes")
    }

    @Test
    fun `irregular bright shapes and low contrast scenes are not documents`() {
        // 사각형이 아닌 큰 밝은 영역(구름·자동차 차체 같은 모양): 네 모서리 사각형을 채우지 못한다.
        val blob = IntArray(200 * 200) { i ->
            val x = i % 200 - 100.0; val y = i / 200 - 100.0
            if (x * x + y * y < 80.0 * 80.0 || (x > 0 && y > 0 && x + y < 95)) 220 else 40
        }
        assertThat(DocumentDetector.detect(blob, 200, 200)).isNull()
        // 종이와 바탕의 밝기 차이가 거의 없는 장면.
        val quad = listOf(0.2 to 0.2, 0.8 to 0.2, 0.8 to 0.8, 0.2 to 0.8)
        assertThat(DocumentDetector.detect(scene(200, 200, quad, paper = 130, desk = 124), 200, 200)).isNull()
        // 이미지의 작은 일부인 사각형(간판 등)은 문서로 보지 않는다.
        val small = listOf(0.4 to 0.4, 0.6 to 0.4, 0.6 to 0.6, 0.4 to 0.6)
        assertThat(DocumentDetector.detect(scene(200, 200, small), 200, 200)).isNull()
    }

    // 여백 안쪽에 글자 줄이 있는 종이를 [paperAt]으로 칠한 장면. [paperAt]은 (x, y)의 종이 밝기, [deskAt]은 바탕 밝기.
    private fun shaded(width: Int, height: Int, quad: List<Pair<Double, Double>>, paperAt: (Int, Int) -> Int, deskAt: (Int, Int) -> Int): IntArray {
        val pts = quad.map { (x, y) -> x * width to y * height }
        val cx = pts.sumOf { it.first } / 4
        val cy = pts.sumOf { it.second } / 4
        val margin = pts.map { (x, y) -> cx + (x - cx) * 0.86 to cy + (y - cy) * 0.86 }
        return IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            when {
                !inside(pts, x + 0.5, y + 0.5) -> deskAt(x, y)
                inside(margin, x + 0.5, y + 0.5) && y % 9 in 3..4 && x % 7 in 1..4 -> paperAt(x, y) - 120
                else -> paperAt(x, y)
            }
        }
    }

    @Test
    fun `white paper on a light gray desk is found`() {
        // 밝기 차이가 30 정도뿐이라 문턱값 하나로는 나뉘지 않던 장면.
        val quad = listOf(0.18 to 0.15, 0.84 to 0.12, 0.88 to 0.86, 0.12 to 0.9)
        val scene = shaded(192, 256, quad, { _, _ -> 228 }, { _, _ -> 196 })
        val analysis = DocumentDetector.analyze(scene, 192, 256)
        assertThat(analysis.reason).startsWith("ok")
        assertNear(analysis.quad!!.topLeft, 0.18, 0.15)
        assertNear(analysis.quad!!.bottomRight, 0.88, 0.86)
    }

    @Test
    fun `a shadow across the page does not cut the document`() {
        // 왼쪽 위에서 오른쪽 아래로 갈수록 어두워지는 그림자. 종이 오른쪽 아래가 바탕보다 어두운 밝기까지 내려간다.
        val width = 192
        val height = 256
        val quad = listOf(0.15 to 0.1, 0.85 to 0.12, 0.88 to 0.9, 0.1 to 0.88)
        val scene = shaded(width, height, quad, { x, y -> 235 - (x + y) * 130 / (width + height) }, { x, y -> 70 + (x + y) * 40 / (width + height) })
        val found = DocumentDetector.detect(scene, width, height)!!
        assertNear(found.topLeft, 0.15, 0.1)
        assertNear(found.topRight, 0.85, 0.12)
        assertNear(found.bottomRight, 0.88, 0.9)
        assertNear(found.bottomLeft, 0.1, 0.88)
    }

    @Test
    fun `paper on a colored desk of the same brightness is found by color`() {
        // 종이(회색 200)와 나무색 책상(밝기 약 200)의 밝기가 같고 색만 다르다.
        val width = 160
        val height = 200
        val quad = listOf(0.2 to 0.15, 0.8 to 0.15, 0.8 to 0.85, 0.2 to 0.85)
        val luma = shaded(width, height, quad, { _, _ -> 200 }, { _, _ -> 199 })
        val pts = quad.map { (x, y) -> x * width to y * height }
        val chroma = IntArray(width * height) { i -> if (inside(pts, i % width + 0.5, i / width + 0.5)) 4 else 80 }
        assertThat(DocumentDetector.detect(luma, width, height)).isNull()
        val found = DocumentDetector.detect(luma, width, height, chroma)!!
        assertNear(found.topLeft, 0.2, 0.15)
        assertNear(found.bottomRight, 0.8, 0.85)
    }

    @Test
    fun `rounded or covered corners still land on the paper corners`() {
        val width = 200
        val height = 260
        val quad = listOf(0.2 to 0.12, 0.82 to 0.16, 0.86 to 0.88, 0.14 to 0.84)
        val base = scene(width, height, quad)
        // 왼쪽 위 모서리를 둥글게 깎고, 오른쪽 아래 모서리는 손가락처럼 어두운 원으로 가린다.
        val corner = 0.2 * width to 0.12 * height
        val finger = 0.86 * width to 0.88 * height
        val edited = IntArray(base.size) { i ->
            val x = i % width + 0.5
            val y = i / width + 0.5
            val nearTopLeft = x < corner.first + 10 && y < corner.second + 10 && hypot(x - (corner.first + 10), y - (corner.second + 10)) > 10
            val underFinger = hypot(x - finger.first, y - finger.second) < 12
            if ((nearTopLeft && base[i] > 128) || underFinger) 40 else base[i]
        }
        val found = DocumentDetector.detect(edited, width, height)!!
        assertNear(found.topLeft, 0.2, 0.12)
        assertNear(found.bottomRight, 0.86, 0.88)
    }

    @Test
    fun `quad measures area convexity and rectified size`() {
        val quad = DocumentQuad(PointN(0.1, 0.1), PointN(0.9, 0.1), PointN(0.9, 0.6), PointN(0.1, 0.6))
        assertThat(quad.area).isWithin(1e-9).of(0.4)
        assertThat(quad.isUsable).isTrue()
        assertThat(quad.rectifiedSize(1000, 2000)).isEqualTo(800 to 1000)
        val twisted = DocumentQuad(PointN(0.1, 0.1), PointN(0.9, 0.6), PointN(0.9, 0.1), PointN(0.1, 0.6))
        assertThat(twisted.isUsable).isFalse()
    }
}
