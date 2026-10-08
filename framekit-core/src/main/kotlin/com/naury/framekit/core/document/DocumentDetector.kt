package com.naury.framekit.core.document

import com.naury.framekit.core.geometry.PointN

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
    public fun detect(luma: IntArray, width: Int, height: Int): DocumentQuad? {
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "Image is too small" }
        require(luma.size == width * height) { "Size does not match" }
        val blurred = boxBlur(boxBlur(luma, width, height), width, height)
        val threshold = otsu(blurred)
        // 종이와 바탕의 밝기 차이가 작으면 문서라고 보기 어렵다(풍경·인물 사진 등).
        var brightSum = 0L; var brightCount = 0; var darkSum = 0L; var darkCount = 0
        blurred.forEach { if (it > threshold) { brightSum += it; brightCount++ } else { darkSum += it; darkCount++ } }
        if (brightCount == 0 || darkCount == 0) return null
        if (brightSum / brightCount - darkSum / darkCount < MIN_CONTRAST) return null
        val bright = BooleanArray(blurred.size) { blurred[it] > threshold }

        // 가운데 영역(가로·세로 20%)에서 더 많은 쪽을 문서 쪽으로 본다.
        var brightVotes = 0
        var total = 0
        for (y in height * 2 / 5 until height * 3 / 5) for (x in width * 2 / 5 until width * 3 / 5) {
            total++
            if (bright[y * width + x]) brightVotes++
        }
        val documentIsBright = brightVotes * 2 >= total
        val seed = nearestToCenter(bright, documentIsBright, width, height) ?: return null
        val region = flood(bright, documentIsBright, seed, width, height)
        val count = region.count { it }
        val fraction = count.toDouble() / region.size
        if (fraction < MIN_FRACTION || fraction > MAX_FRACTION) return null
        // 종이는 바탕 위에 놓여 있어 이미지 가장자리에 넓게 닿지 않는다. 두 변 이상에 넓게 닿으면 하늘·벽 같은 배경으로 본다.
        if (edgesTouched(region, width, height) >= 2) return null

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
        if (!quad.isUsable || quad.area < MIN_DOCUMENT_AREA) return null
        // 종이는 네 모서리가 만드는 사각형을 거의 꽉 채운다. 모양이 들쭉날쭉한 영역(하늘·벽·자동차 등)은 걸러 낸다.
        val fill = fraction / quad.area
        if (fill < MIN_FILL || fill > MAX_FILL) return null
        // 변 길이 비가 지나치게 길쭉하면 영수증 띠보다도 극단적인 것이라 문서가 아니라고 본다.
        val (w, h) = quad.rectifiedSize(width, height)
        if (maxOf(w, h).toDouble() / minOf(w, h) > MAX_ASPECT) return null
        return quad
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
}
