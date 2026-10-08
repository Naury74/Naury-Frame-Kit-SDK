package com.naury.framekit.core.document

import com.naury.framekit.core.geometry.PointN
import kotlin.math.abs

/**
 * 작은 흑백 이미지에서 문서의 네 모서리를 찾는다. 기기 의존성 없이 동작하며 빠르다(256px 기준 수 ms).
 *
 * 방법: 흐리게 한 뒤 Otsu 문턱값으로 밝은·어두운 영역을 나누고, 이미지 가운데가 속한 쪽에서 가운데와 이어진
 * 영역을 문서로 본다. 그 영역에서 대각선 방향으로 가장 먼 점 네 개를 모서리로 고른다. 배경과 대비가 있는
 * 종이를 비스듬히 찍은 일반적인 사진을 대상으로 하며, 찾지 못하면 `null`을 돌려 호출하는 쪽이
 * [DocumentQuad.inset] 같은 기본값을 쓰게 한다.
 */
public object DocumentDetector {

    /**
     * @param luma 밝기 `0..255`, 행 우선. 크기는 `width * height`.
     * @return 문서 모서리(정규화 좌표). 문서가 너무 작거나 화면 전체이거나 모양이 이상하면 `null`.
     * @throws IllegalArgumentException 크기가 맞지 않거나 너무 작을 때(가로·세로 8px 미만).
     */
    public fun detect(luma: IntArray, width: Int, height: Int): DocumentQuad? = analyze(luma, width, height).quad

    /** 판별 결과와, 문서가 아니라고 본 경우 그 이유(로그·테스트용). */
    public data class Analysis(val quad: DocumentQuad?, val reason: String)

    /** [detect]와 같지만 탈락한 단계를 함께 돌려준다. */
    public fun analyze(luma: IntArray, width: Int, height: Int): Analysis {
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "Image is too small" }
        require(luma.size == width * height) { "Size does not match" }
        // 단계마다 수치를 모아, 문서가 아니라고 볼 때 이유와 함께 돌려준다.
        val trace = StringBuilder()
        fun note(key: String, value: Double) { trace.append(key).append('=').append(String.format(java.util.Locale.ROOT, "%.3f", value)).append(' ') }
        fun reject(reason: String) = Analysis(null, "$reason | ${trace.toString().trim()}")
        val blurred = boxBlur(boxBlur(luma, width, height), width, height)
        val threshold = otsu(blurred)
        // 종이와 바탕의 밝기 차이가 작으면 문서라고 보기 어렵다(풍경·인물 사진 등).
        var brightSum = 0L; var brightCount = 0; var darkSum = 0L; var darkCount = 0
        blurred.forEach { if (it > threshold) { brightSum += it; brightCount++ } else { darkSum += it; darkCount++ } }
        if (brightCount == 0 || darkCount == 0) return reject("flat")
        note("contrast", (brightSum / brightCount - darkSum / darkCount).toDouble())
        if (brightSum / brightCount - darkSum / darkCount < MIN_CONTRAST) return reject("low contrast")
        val raw = BooleanArray(blurred.size) { blurred[it] > threshold }

        // 표 선·빽빽한 글자가 종이를 작은 칸으로 쪼개지 않도록, 종이 쪽 영역을 넓혔다 줄여(닫힘) 잉크 자리를 메운다.
        // 대부분의 문서는 바탕보다 밝으므로 먼저 밝은 종이로 가정해 메운 뒤, 가운데(가로·세로 20%)가 그 쪽인지 본다.
        val closeRadius = maxOf(2, minOf(width, height) / CLOSE_DIVISOR)
        val closedBright = close(raw, true, width, height, closeRadius)
        var brightVotes = 0
        var total = 0
        for (y in height * 2 / 5 until height * 3 / 5) for (x in width * 2 / 5 until width * 3 / 5) {
            total++
            if (closedBright[y * width + x]) brightVotes++
        }
        val documentIsBright = brightVotes * 2 >= total
        val bright = if (documentIsBright) closedBright else close(raw, false, width, height, closeRadius)
        val seed = nearestToCenter(bright, documentIsBright, width, height) ?: return reject("no seed")
        val region = flood(bright, documentIsBright, seed, width, height)
        val count = region.count { it }
        val fraction = count.toDouble() / region.size
        note("fraction", fraction)
        if (fraction < MIN_FRACTION || fraction > MAX_FRACTION) return reject("region fraction $fraction")
        // 가까이 찍은 문서는 사진 가장자리에 닿을 수 있다. 하늘·벽 같은 배경과 구별하려고, 두 변 이상에 닿으면 영역 안에
        // 글자(잉크)가 어느 정도 있을 때만 문서로 본다. 네 변에 모두 닿으면 종이 모서리를 알 수 없어 문서로 보지 않는다.
        // 종이는 글자를 빼면 거의 한 가지 밝기다. 하늘·건물·도로처럼 밝기가 넓게 퍼진 영역은 문서가 아니다.
        // 테두리에서 충분히 안쪽에서, 가장 흔한 밝기(종이색) 근처에 있는 점의 비율을 잰다.
        val interior = morph(region, width, height, closeRadius * 2, grow = false)
        val histogram = IntArray(256)
        var inside = 0
        for (i in region.indices) if (interior[i]) {
            histogram[luma[i].coerceIn(0, 255)]++
            inside++
        }
        if (inside == 0) return reject("no interior")
        val paperLevel = histogram.indices.maxBy { level -> (maxOf(0, level - 4)..minOf(255, level + 4)).sumOf { histogram[it] } }
        val paperShare = (maxOf(0, paperLevel - PAPER_BAND)..minOf(255, paperLevel + PAPER_BAND)).sumOf { histogram[it] }.toDouble() / inside
        note("paper", paperShare)
        if (paperShare < MIN_PAPER_SHARE) return reject("not uniform like paper")
        val edges = edgesTouched(region, width, height)
        note("edges", edges.toDouble())
        if (edges >= 4) return reject("touches all edges")
        if (edges >= 2) {
            // 작은 글자는 흐림을 거치면 종이 색에 섞이므로, 흐리기 전 밝기에서 종이색과 확실히 다른 점을 잉크로 센다.
            var ink = 0
            for (i in region.indices) if (interior[i] && abs(luma[i] - paperLevel) > INK_DIFFERENCE) ink++
            val inkRatio = ink.toDouble() / inside
            note("ink", inkRatio)
            if (inkRatio < MIN_INK_NEAR_EDGES) return reject("touches edges without text $inkRatio")
        }

        var tl = 0; var tr = 0; var br = 0; var bl = 0
        var minSum = Int.MAX_VALUE; var maxSum = Int.MIN_VALUE; var minDiff = Int.MAX_VALUE; var maxDiff = Int.MIN_VALUE
        for (i in region.indices) {
            if (!region[i]) continue
            val x = i % width
            val y = i / width
            val sum = x + y
            val diff = x - y
            if (sum < minSum) { minSum = sum; tl = i }
            if (sum > maxSum) { maxSum = sum; br = i }
            if (diff > maxDiff) { maxDiff = diff; tr = i }
            if (diff < minDiff) { minDiff = diff; bl = i }
        }
        fun point(index: Int) = PointN((index % width + 0.5) / width, (index / width + 0.5) / height)
        val quad = DocumentQuad(point(tl), point(tr), point(br), point(bl))
        if (!quad.isUsable || quad.area < MIN_DOCUMENT_AREA) return reject("quad area ${quad.area}")
        // 종이는 네 모서리가 만드는 사각형을 거의 꽉 채운다. 모양이 들쭉날쭉한 영역(하늘·벽·자동차 등)은 걸러 낸다.
        note("area", quad.area)
        val fill = fraction / quad.area
        note("fill", fill)
        if (fill < MIN_FILL || fill > MAX_FILL) return reject("fill $fill")
        // 변 길이 비가 지나치게 길쭉하면 영수증 띠보다도 극단적인 것이라 문서가 아니라고 본다.
        val (w, h) = quad.rectifiedSize(width, height)
        if (maxOf(w, h).toDouble() / minOf(w, h) > MAX_ASPECT) return reject("aspect")
        return Analysis(quad, "ok | ${trace.toString().trim()}")
    }

    // [value] 쪽 영역을 반경 [radius]만큼 넓혔다가 다시 줄인다. 반경보다 가는 반대쪽 무늬(글자·선)가 메워진다.
    private fun close(mask: BooleanArray, value: Boolean, width: Int, height: Int, radius: Int): BooleanArray {
        val target = BooleanArray(mask.size) { mask[it] == value }
        val grown = morph(target, width, height, radius, grow = true)
        val closed = morph(grown, width, height, radius, grow = false)
        return BooleanArray(mask.size) { if (closed[it]) value else !value }
    }

    // 가로·세로를 나눠 처리하는 사각형 팽창·침식. 이미지 밖은 보지 않아, 가장자리에 닿은 종이가 깎이지 않는다.
    private fun morph(source: BooleanArray, width: Int, height: Int, radius: Int, grow: Boolean): BooleanArray {
        val horizontal = BooleanArray(source.size)
        for (y in 0 until height) {
            val row = y * width
            var count = 0
            for (x in 0..minOf(radius, width - 1)) if (source[row + x]) count++
            for (x in 0 until width) {
                val span = minOf(x + radius, width - 1) - maxOf(x - radius, 0) + 1
                horizontal[row + x] = if (grow) count > 0 else count == span
                if (x - radius >= 0 && source[row + x - radius]) count--
                if (x + radius + 1 < width && source[row + x + radius + 1]) count++
            }
        }
        val out = BooleanArray(source.size)
        for (x in 0 until width) {
            var count = 0
            for (y in 0..minOf(radius, height - 1)) if (horizontal[y * width + x]) count++
            for (y in 0 until height) {
                val span = minOf(y + radius, height - 1) - maxOf(y - radius, 0) + 1
                out[y * width + x] = if (grow) count > 0 else count == span
                if (y - radius >= 0 && horizontal[(y - radius) * width + x]) count--
                if (y + radius + 1 < height && horizontal[(y + radius + 1) * width + x]) count++
            }
        }
        return out
    }

    private fun edgesTouched(region: BooleanArray, width: Int, height: Int): Int {
        fun coverage(indices: IntProgression): Double = indices.count { region[it] }.toDouble() / indices.count()
        val top = coverage(0 until width)
        val bottom = coverage((height - 1) * width until height * width)
        val left = coverage(0 until width * height step width)
        val right = coverage(width - 1 until width * height step width)
        return listOf(top, bottom, left, right).count { it > EDGE_COVERAGE }
    }

    private fun boxBlur(source: IntArray, width: Int, height: Int): IntArray {
        val out = IntArray(source.size)
        for (y in 0 until height) for (x in 0 until width) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx
                val ny = y + dy
                if (nx in 0 until width && ny in 0 until height) {
                    sum += source[ny * width + nx]
                    n++
                }
            }
            out[y * width + x] = sum / n
        }
        return out
    }

    // 두 밝기 집단의 분산이 가장 잘 갈리는 문턱값.
    private fun otsu(values: IntArray): Int {
        val histogram = IntArray(256)
        values.forEach { histogram[it.coerceIn(0, 255)]++ }
        val total = values.size.toDouble()
        val sumAll = histogram.indices.sumOf { it.toDouble() * histogram[it] }
        var sumBack = 0.0
        var weightBack = 0.0
        var best = 0.0
        var threshold = 127
        for (t in 0 until 256) {
            weightBack += histogram[t]
            if (weightBack == 0.0) continue
            val weightFore = total - weightBack
            if (weightFore == 0.0) break
            sumBack += t.toDouble() * histogram[t]
            val meanBack = sumBack / weightBack
            val meanFore = (sumAll - sumBack) / weightFore
            val between = weightBack * weightFore * (meanBack - meanFore) * (meanBack - meanFore)
            if (between > best) {
                best = between
                threshold = t
            }
        }
        return threshold
    }

    private fun nearestToCenter(mask: BooleanArray, value: Boolean, width: Int, height: Int): Int? {
        val cx = width / 2
        val cy = height / 2
        val maxRadius = minOf(width, height) / 4
        for (r in 0..maxRadius) {
            for (y in (cy - r)..(cy + r)) for (x in (cx - r)..(cx + r)) {
                if (x !in 0 until width || y !in 0 until height) continue
                if (mask[y * width + x] == value) return y * width + x
            }
        }
        return null
    }

    private fun flood(mask: BooleanArray, value: Boolean, seed: Int, width: Int, height: Int): BooleanArray {
        val region = BooleanArray(mask.size)
        val stack = IntArray(mask.size)
        var top = 0
        stack[top++] = seed
        region[seed] = true
        while (top > 0) {
            val i = stack[--top]
            val x = i % width
            val y = i / width
            if (x > 0) visit(i - 1, mask, value, region, stack, top).also { top = it }
            if (x < width - 1) visit(i + 1, mask, value, region, stack, top).also { top = it }
            if (y > 0) visit(i - width, mask, value, region, stack, top).also { top = it }
            if (y < height - 1) visit(i + width, mask, value, region, stack, top).also { top = it }
        }
        return region
    }

    private fun visit(i: Int, mask: BooleanArray, value: Boolean, region: BooleanArray, stack: IntArray, top: Int): Int {
        if (region[i] || mask[i] != value) return top
        region[i] = true
        stack[top] = i
        return top + 1
    }

    /** 감지에 쓰기 좋은 긴 변 길이(px). 더 크면 느리기만 하고 정확도는 비슷하다. */
    public const val WORKING_LONG_EDGE: Int = 256

    private const val MIN_SIDE = 8
    private const val MIN_FRACTION = 0.08
    private const val MAX_FRACTION = 0.97
    private const val MIN_CONTRAST = 45
    private const val MIN_DOCUMENT_AREA = 0.12
    private const val MIN_FILL = 0.9
    private const val MAX_FILL = 1.06
    private const val MAX_ASPECT = 6.0
    private const val EDGE_COVERAGE = 0.3
    private const val CLOSE_DIVISOR = 40
    private const val MIN_INK_NEAR_EDGES = 0.01
    private const val INK_DIFFERENCE = 40
    private const val PAPER_BAND = 20
    private const val MIN_PAPER_SHARE = 0.5
}
