package com.naury.framekit.core.document

import com.naury.framekit.core.geometry.PointN
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 작은 이미지에서 문서의 네 모서리를 찾는다. 기기 의존성 없이 동작하며 빠르다(256px 기준 수십 ms 이내).
 *
 * 방법: 서로 다른 세 가지 방식으로 "문서일 수 있는 영역"을 만든 뒤, 각각을 같은 기준으로 검사해 통과한 것 중
 * 가장 큰 사각형을 고른다.
 * 1. 밝기: Otsu 문턱값으로 밝은·어두운 쪽을 나누고 가운데와 이어진 쪽을 문서로 본다. 대비가 큰 일반 사진에 강하다.
 * 2. 윤곽선: 밝기가 급하게 바뀌는 선(종이 테두리)을 넘지 않고 가운데에서 퍼져 나간 영역. 종이와 바탕의 밝기가
 *    비슷하거나 그림자가 져서 한 문턱값으로 나뉘지 않는 사진에 강하다. 문턱을 두 단계로 바꿔 가며 시도한다.
 * 3. 채도: 종이는 색이 거의 없다. 나무 책상·색 있는 천처럼 밝기는 비슷하고 색만 다른 바탕에 강하다.
 *    [analyze]에 `chroma`를 넘길 때만 쓴다.
 *
 * 모서리는 영역의 볼록 껍질에 들어가는 가장 넓은 사각형에서 시작해, 네 변마다 테두리 점에 직선을 맞추고 이웃 직선의
 * 교점으로 다시 잡는다. 그래서 둥글거나 접힌 모서리, 손가락에 조금 가린 모서리도 실제 종이 모서리 쪽으로 맞춰진다.
 * 찾지 못하면 `null`을 돌려 호출하는 쪽이 [DocumentQuad.inset] 같은 기본값을 쓰게 한다.
 */
public object DocumentDetector {

    /**
     * @param luma 밝기 `0..255`, 행 우선. 크기는 `width * height`.
     * @param chroma 채도 `0..255`(픽셀의 RGB 최댓값 - 최솟값), 행 우선. `null`이면 채도로는 찾지 않는다.
     * @return 문서 모서리(정규화 좌표). 문서가 너무 작거나 화면 전체이거나 모양이 이상하면 `null`.
     * @throws IllegalArgumentException 크기가 맞지 않거나 너무 작을 때(가로·세로 8px 미만).
     */
    @JvmOverloads
    public fun detect(luma: IntArray, width: Int, height: Int, chroma: IntArray? = null): DocumentQuad? =
        analyze(luma, width, height, chroma).quad

    /** 판별 결과와, 문서가 아니라고 본 경우 그 이유(로그·테스트용). 성공하면 [reason]이 `ok`로 시작한다. */
    public data class Analysis(val quad: DocumentQuad?, val reason: String)

    /** [detect]와 같지만 방식마다 탈락한 단계를 함께 돌려준다. 이유는 밝기 방식부터 차례로 적는다. */
    @JvmOverloads
    public fun analyze(luma: IntArray, width: Int, height: Int, chroma: IntArray? = null): Analysis {
        require(width >= MIN_SIDE && height >= MIN_SIDE) { "Image is too small" }
        require(luma.size == width * height) { "Size does not match" }
        require(chroma == null || chroma.size == luma.size) { "Chroma size does not match" }
        val image = Image(luma, width, height)
        val attempts = ArrayList<Pair<String, Analysis>>()
        attempts += "luma" to image.evaluate(image.brightnessRegion())
        val gradient = image.gradient()
        for (step in EDGE_STEPS) attempts += "edge$step" to image.evaluate(image.edgeRegion(gradient, step))
        if (chroma != null) attempts += "chroma" to image.evaluate(image.chromaRegion(chroma))
        // 바탕까지 번진 영역은 사진 가장자리에 닿는다. 가장자리에 덜 닿은 후보를 먼저 고르고, 같으면 넓은 쪽을 고른다.
        val found = attempts.filter { it.second.quad != null }
            .maxWithOrNull(compareBy<Pair<String, Analysis>>({ -image.cornersOnBorder(it.second.quad!!) }, { it.second.quad!!.area }))
        if (found != null) return Analysis(found.second.quad, "ok(${found.first}) | ${found.second.reason}")
        return Analysis(null, attempts.joinToString(" ; ") { (label, analysis) -> if (label == "luma") analysis.reason else "$label: ${analysis.reason}" })
    }

    /** 영역을 만드는 단계의 결과. 실패하면 [region]이 `null`이고 [reason]에 이유가 있다. */
    private class Candidate(val region: BooleanArray?, val reason: String = "", val trace: String = "")

    private class Image(val luma: IntArray, val width: Int, val height: Int) {
        val size = width * height
        val blurred = boxBlur(boxBlur(luma, width, height), width, height)
        val closeRadius = maxOf(2, minOf(width, height) / CLOSE_DIVISOR)

        // ---- 영역 만들기 ----

        fun brightnessRegion(): Candidate {
            val threshold = otsu(blurred)
            // 종이와 바탕의 밝기 차이가 작으면 이 방식으로는 나눌 수 없다(윤곽선·채도 방식에 맡긴다).
            var brightSum = 0L; var brightCount = 0; var darkSum = 0L; var darkCount = 0
            blurred.forEach { if (it > threshold) { brightSum += it; brightCount++ } else { darkSum += it; darkCount++ } }
            if (brightCount == 0 || darkCount == 0) return Candidate(null, "flat")
            val contrast = brightSum / brightCount - darkSum / darkCount
            val trace = "contrast=$contrast"
            if (contrast < MIN_CONTRAST) return Candidate(null, "low contrast", trace)
            val raw = BooleanArray(size) { blurred[it] > threshold }
            // 표 선·빽빽한 글자가 종이를 작은 칸으로 쪼개지 않도록, 종이 쪽 영역을 넓혔다 줄여(닫힘) 잉크 자리를 메운다.
            // 대부분의 문서는 바탕보다 밝으므로 먼저 밝은 종이로 가정해 메운 뒤, 가운데(가로·세로 20%)가 그 쪽인지 본다.
            val closedBright = close(raw, true, width, height, closeRadius)
            val documentIsBright = centerShare(closedBright) >= 0.5
            val mask = if (documentIsBright) closedBright else close(raw, false, width, height, closeRadius)
            val seed = nearestToCenter { mask[it] == documentIsBright } ?: return Candidate(null, "no seed", trace)
            return Candidate(flood(seed) { mask[it] == documentIsBright }, trace = trace)
        }

        /**
         * [gradient]가 [step]을 넘는 점을 테두리로 보고, 가운데의 종이에서 테두리를 넘지 않고 퍼진 영역을 만든다.
         * 글자 줄은 여백을 돌아 넘어가므로 종이 전체가 이어지고, 글자 자리 구멍은 메운다.
         */
        fun edgeRegion(gradient: IntArray, step: Int): Candidate {
            // 테두리가 한두 픽셀 끊긴 틈으로 바탕까지 번지지 않게 테두리를 1px 넓힌다.
            val edge = morph(BooleanArray(size) { gradient[it] > step }, width, height, 1, grow = true)
            // 가운데 영역에서 가장 흔한 밝기를 종이색으로 보고, 그 색인 점에서 시작한다(글자 줄 안에서 시작하지 않게).
            val histogram = IntArray(256)
            forCenter { i -> if (!edge[i]) histogram[structure[i]]++ }
            if (histogram.sum() == 0) return Candidate(null, "no seed")
            val paper = modeOf(histogram)
            val seed = nearestToCenter { !edge[it] && abs(structure[it] - paper) <= SEED_TOLERANCE } ?: return Candidate(null, "no seed")
            val region = fillHoles(flood(seed) { !edge[it] })
            // 테두리 띠의 가운데가 실제 종이 끝이므로 띠 폭의 절반만큼 넓힌다.
            return Candidate(morph(region, width, height, EDGE_GROW, grow = true))
        }

        fun chromaRegion(chroma: IntArray): Candidate {
            val smooth = boxBlur(boxBlur(chroma, width, height), width, height)
            val threshold = otsu(smooth)
            var lowSum = 0L; var lowCount = 0; var highSum = 0L; var highCount = 0
            smooth.forEach { if (it > threshold) { highSum += it; highCount++ } else { lowSum += it; lowCount++ } }
            if (lowCount == 0 || highCount == 0) return Candidate(null, "flat")
            val contrast = highSum / highCount - lowSum / lowCount
            val trace = "chroma=$contrast"
            if (contrast < MIN_CHROMA_CONTRAST) return Candidate(null, "low chroma contrast", trace)
            // 색이 적은 쪽이 종이다. 색 있는 도장·형광펜 자리는 닫힘으로 메운다.
            val neutral = close(BooleanArray(size) { smooth[it] <= threshold }, true, width, height, closeRadius)
            if (centerShare(neutral) < 0.5) return Candidate(null, "colorful center", trace)
            val seed = nearestToCenter { neutral[it] } ?: return Candidate(null, "no seed", trace)
            return Candidate(flood(seed) { neutral[it] }, trace = trace)
        }

        // ---- 검사 ----

        /** 영역이 문서처럼 보이는지 검사하고 모서리를 맞춘다. */
        fun evaluate(candidate: Candidate): Analysis {
            val trace = StringBuilder(candidate.trace)
            fun note(key: String, value: Double) {
                if (trace.isNotEmpty()) trace.append(' ')
                trace.append(key).append('=').append(String.format(java.util.Locale.ROOT, "%.3f", value))
            }
            fun reject(reason: String) = Analysis(null, "$reason | $trace")
            val region = candidate.region ?: return reject(candidate.reason)
            val count = region.count { it }
            val fraction = count.toDouble() / size
            note("fraction", fraction)
            if (fraction < MIN_FRACTION || fraction > MAX_FRACTION) return reject("region fraction $fraction")
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
            val paperLevel = modeOf(histogram)
            val paperShare = (maxOf(0, paperLevel - PAPER_BAND)..minOf(255, paperLevel + PAPER_BAND)).sumOf { histogram[it] }.toDouble() / inside
            note("paper", paperShare)
            if (paperShare < MIN_PAPER_SHARE) return reject("not uniform like paper")
            // 가까이 찍은 문서는 사진 가장자리에 닿을 수 있다. 네 변에 모두 닿으면 종이 모서리를 알 수 없어 문서로 보지 않는다.
            val edges = edgesTouched(region)
            note("edges", edges.toDouble())
            if (edges >= 4) return reject("touches all edges")
            // 문서에는 글자가 있다. 글자 획은 이웃 픽셀과 밝기가 급하게 달라지는 점이 촘촘하고, 벽·하늘처럼 매끄럽게
            // 변하는 면에는 이런 점이 거의 없다. 테두리에서 충분히 안쪽에서 흐리기 전 밝기로 센다.
            var strokes = 0
            for (i in region.indices) {
                if (!interior[i]) continue
                val x = i % width
                val y = i / width
                val right = if (x + 1 < width) abs(luma[i] - luma[i + 1]) else 0
                val down = if (y + 1 < height) abs(luma[i] - luma[i + width]) else 0
                if (maxOf(right, down) > STROKE_STEP) strokes++
            }
            val strokeRatio = strokes.toDouble() / inside
            note("strokes", strokeRatio)
            if (strokeRatio < MIN_STROKES) return reject("no text strokes")
            val quad = fitQuad(region) ?: return reject("no quad")
            if (!quad.isUsable || quad.area < MIN_DOCUMENT_AREA) return reject("quad area ${quad.area}")
            note("area", quad.area)
            // 종이는 네 모서리가 만드는 사각형을 거의 꽉 채운다. 모양이 들쭉날쭉한 영역(하늘·벽·자동차 등)은 걸러 낸다.
            val fill = fraction / quad.area
            note("fill", fill)
            if (fill < MIN_FILL || fill > MAX_FILL) return reject("fill $fill")
            // 변 길이 비가 지나치게 길쭉하면 영수증 띠보다도 극단적인 것이라 문서가 아니라고 본다.
            val (w, h) = quad.rectifiedSize(width, height)
            if (maxOf(w, h).toDouble() / minOf(w, h) > MAX_ASPECT) return reject("aspect")
            return Analysis(quad, trace.toString())
        }

        // ---- 모서리 맞추기 ----

        /** 영역 테두리에 맞는 네 모서리. 볼록 껍질 위의 가장 넓은 사각형을 구한 뒤 변마다 직선을 맞춰 다듬는다. */
        fun fitQuad(region: BooleanArray): DocumentQuad? {
            val points = boundarySamples(region)
            if (points.size < 8) return null
            var hull = convexHull(points)
            if (hull.size < 4) return null
            if (hull.size > MAX_HULL) hull = List(MAX_HULL) { hull[it * hull.size / MAX_HULL] }
            val rough = largestQuad(hull) ?: return null
            val corners = refine(orderClockwise(rough), points)
            fun normalized(p: Point) = PointN((p.x / width).coerceIn(0.0, 1.0), (p.y / height).coerceIn(0.0, 1.0))
            return DocumentQuad(normalized(corners[0]), normalized(corners[1]), normalized(corners[2]), normalized(corners[3]))
        }

        // 줄마다 가장 왼쪽·오른쪽, 열마다 가장 위·아래 점. 네 변 모두에 고르게 점이 생긴다.
        private fun boundarySamples(region: BooleanArray): List<Point> {
            val points = ArrayList<Point>(2 * (width + height))
            for (y in 0 until height) {
                val row = y * width
                var first = -1; var last = -1
                for (x in 0 until width) if (region[row + x]) { if (first < 0) first = x; last = x }
                if (first >= 0) {
                    points += Point(first + 0.5, y + 0.5)
                    if (last != first) points += Point(last + 0.5, y + 0.5)
                }
            }
            for (x in 0 until width) {
                var first = -1; var last = -1
                for (y in 0 until height) if (region[y * width + x]) { if (first < 0) first = y; last = y }
                if (first >= 0) {
                    points += Point(x + 0.5, first + 0.5)
                    if (last != first) points += Point(x + 0.5, last + 0.5)
                }
            }
            return points
        }

        /**
         * 대략 맞춘 [corners]의 네 변마다 근처 테두리 점에서 직선을 다시 찾고, 이웃 직선의 교점을 새 모서리로 쓴다.
         * 손가락·접힌 모서리처럼 변에서 벗어난 점이 섞여도 흔들리지 않도록, 두 점으로 만든 직선 중 가장 많은 점이
         * 가까이 모이는 것을 고른 뒤(RANSAC) 그 점들로만 맞춘다. 교점이 너무 멀리 가면 원래 모서리를 둔다.
         */
        private fun refine(corners: List<Point>, points: List<Point>): List<Point> {
            val diagonal = hypot(width.toDouble(), height.toDouble())
            val search = maxOf(3.0, diagonal * SIDE_SEARCH)
            val random = java.util.Random(RANSAC_SEED)
            val lines = (0 until 4).map { side ->
                val a = corners[side]
                val b = corners[(side + 1) % 4]
                val dx = b.x - a.x; val dy = b.y - a.y
                val length = hypot(dx, dy)
                if (length < 1e-6) return@map null
                val near = points.filter { p ->
                    val t = ((p.x - a.x) * dx + (p.y - a.y) * dy) / (length * length)
                    val distance = abs((p.x - a.x) * dy - (p.y - a.y) * dx) / length
                    t in -SIDE_OVERRUN..(1 + SIDE_OVERRUN) && distance <= search
                }
                if (near.size < MIN_SIDE_POINTS) return@map null
                var best: List<Point> = emptyList()
                repeat(RANSAC_ROUNDS) {
                    val p = near[random.nextInt(near.size)]
                    val q = near[random.nextInt(near.size)]
                    val ql = hypot(q.x - p.x, q.y - p.y)
                    // 너무 가까운 두 점은 방향이 불안정하다.
                    if (ql < length * 0.2) return@repeat
                    val inliers = near.filter { r -> abs((r.x - p.x) * (q.y - p.y) - (r.y - p.y) * (q.x - p.x)) / ql <= INLIER_DISTANCE }
                    if (inliers.size > best.size) best = inliers
                }
                if (best.size < MIN_SIDE_POINTS) null else fitLine(best)
            }
            return (0 until 4).map { corner ->
                val before = lines[(corner + 3) % 4]
                val after = lines[corner]
                val original = corners[corner]
                val crossing = if (before != null && after != null) intersect(before, after) else null
                if (crossing == null || hypot(crossing.x - original.x, crossing.y - original.y) > diagonal * MAX_CORNER_SHIFT) original else crossing
            }
        }

        // ---- 보조 ----

        /** 사진 가장자리(1px 이내)에 놓인 모서리 수. */
        fun cornersOnBorder(quad: DocumentQuad): Int = quad.corners.count { p ->
            val x = p.x * width
            val y = p.y * height
            x <= 1.0 || y <= 1.0 || x >= width - 1.0 || y >= height - 1.0
        }

        /**
         * 글자를 지운 밝기([structure])의 이웃 차 크기. 어두운 글자·표 선은 회색조 닫힘(최댓값 뒤 최솟값)으로 지워
         * 종이 안에 테두리가 생기지 않게 하고, 종이와 바탕 사이처럼 넓은 면이 바뀌는 곳만 남긴다.
         */
        fun gradient(): IntArray {
            val s = structure
            return IntArray(size) { i ->
                val x = i % width
                val y = i / width
                val dx = if (x in 1 until width - 1) s[i + 1] - s[i - 1] else 0
                val dy = if (y in 1 until height - 1) s[i + width] - s[i - width] else 0
                hypot(dx.toDouble(), dy.toDouble()).toInt()
            }
        }

        // 흐리기 전에 닫아야 글자 줄 사이의 종이 밝기가 남는다(먼저 흐리면 글자 덩어리가 회색 면이 된다).
        val structure: IntArray by lazy {
            val closed = grayMorph(grayMorph(luma, closeRadius, max = true), closeRadius, max = false)
            boxBlur(boxBlur(closed, width, height), width, height)
        }

        // 가로·세로를 나눠 처리하는 사각형 창의 최댓값(또는 최솟값) 필터.
        private fun grayMorph(source: IntArray, radius: Int, max: Boolean): IntArray {
            val horizontal = IntArray(size)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) {
                    var v = source[row + x]
                    for (k in maxOf(0, x - radius)..minOf(width - 1, x + radius)) {
                        val c = source[row + k]
                        if (if (max) c > v else c < v) v = c
                    }
                    horizontal[row + x] = v
                }
            }
            val out = IntArray(size)
            for (x in 0 until width) for (y in 0 until height) {
                var v = horizontal[y * width + x]
                for (k in maxOf(0, y - radius)..minOf(height - 1, y + radius)) {
                    val c = horizontal[k * width + x]
                    if (if (max) c > v else c < v) v = c
                }
                out[y * width + x] = v
            }
            return out
        }

        private inline fun forCenter(action: (Int) -> Unit) {
            for (y in height * 2 / 5 until height * 3 / 5) for (x in width * 2 / 5 until width * 3 / 5) action(y * width + x)
        }

        private fun centerShare(mask: BooleanArray): Double {
            var votes = 0
            var total = 0
            forCenter { i -> total++; if (mask[i]) votes++ }
            return if (total == 0) 0.0 else votes.toDouble() / total
        }

        private inline fun nearestToCenter(accept: (Int) -> Boolean): Int? {
            val cx = width / 2
            val cy = height / 2
            val maxRadius = minOf(width, height) / 4
            for (r in 0..maxRadius) {
                for (y in (cy - r)..(cy + r)) for (x in (cx - r)..(cx + r)) {
                    if (x !in 0 until width || y !in 0 until height) continue
                    if (maxOf(abs(x - cx), abs(y - cy)) != r) continue
                    if (accept(y * width + x)) return y * width + x
                }
            }
            return null
        }

        private inline fun flood(seed: Int, passable: (Int) -> Boolean): BooleanArray {
            val region = BooleanArray(size)
            val stack = IntArray(size)
            var top = 0
            stack[top++] = seed
            region[seed] = true
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                if (x > 0 && !region[i - 1] && passable(i - 1)) { region[i - 1] = true; stack[top++] = i - 1 }
                if (x < width - 1 && !region[i + 1] && passable(i + 1)) { region[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && !region[i - width] && passable(i - width)) { region[i - width] = true; stack[top++] = i - width }
                if (y < height - 1 && !region[i + width] && passable(i + width)) { region[i + width] = true; stack[top++] = i + width }
            }
            return region
        }

        // 사진 가장자리와 이어지지 않은 바깥 점(글자 자리 구멍)을 영역에 넣는다.
        private fun fillHoles(region: BooleanArray): BooleanArray {
            val outside = BooleanArray(size)
            val stack = IntArray(size)
            var top = 0
            fun push(i: Int) { if (!region[i] && !outside[i]) { outside[i] = true; stack[top++] = i } }
            for (x in 0 until width) { push(x); push((height - 1) * width + x) }
            for (y in 0 until height) { push(y * width); push(y * width + width - 1) }
            while (top > 0) {
                val i = stack[--top]
                val x = i % width
                val y = i / width
                if (x > 0) push(i - 1)
                if (x < width - 1) push(i + 1)
                if (y > 0) push(i - width)
                if (y < height - 1) push(i + width)
            }
            return BooleanArray(size) { !outside[it] }
        }

        private fun edgesTouched(region: BooleanArray): Int {
            fun coverage(indices: IntProgression): Double = indices.count { region[it] }.toDouble() / indices.count()
            val top = coverage(0 until width)
            val bottom = coverage((height - 1) * width until height * width)
            val left = coverage(0 until width * height step width)
            val right = coverage(width - 1 until width * height step width)
            return listOf(top, bottom, left, right).count { it > EDGE_COVERAGE }
        }
    }

    private data class Point(val x: Double, val y: Double)

    /** 점 ([x], [y])를 지나고 방향이 ([dx], [dy])인 직선. */
    private data class Line(val x: Double, val y: Double, val dx: Double, val dy: Double)

    // 점들의 주성분 방향으로 직선을 맞춘다(가로·세로 어느 쪽 변이든 같은 식으로 맞는다).
    private fun fitLine(points: List<Point>): Line {
        val mx = points.sumOf { it.x } / points.size
        val my = points.sumOf { it.y } / points.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (p in points) {
            val dx = p.x - mx; val dy = p.y - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val angle = 0.5 * atan2(2 * sxy, sxx - syy)
        return Line(mx, my, kotlin.math.cos(angle), kotlin.math.sin(angle))
    }

    private fun intersect(a: Line, b: Line): Point? {
        val denominator = a.dx * b.dy - a.dy * b.dx
        if (abs(denominator) < 1e-6) return null
        val t = ((b.x - a.x) * b.dy - (b.y - a.y) * b.dx) / denominator
        return Point(a.x + a.dx * t, a.y + a.dy * t)
    }

    private fun cross(o: Point, a: Point, b: Point) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)

    // Andrew monotone chain. 같은 직선 위의 점은 뺀다.
    private fun convexHull(points: List<Point>): List<Point> {
        val sorted = points.distinct().sortedWith(compareBy({ it.x }, { it.y }))
        if (sorted.size < 3) return sorted
        val hull = ArrayList<Point>(sorted.size * 2)
        for (p in sorted) {
            while (hull.size >= 2 && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
            hull += p
        }
        val lower = hull.size + 1
        for (i in sorted.size - 2 downTo 0) {
            val p = sorted[i]
            while (hull.size >= lower && cross(hull[hull.size - 2], hull[hull.size - 1], p) <= 0) hull.removeAt(hull.size - 1)
            hull += p
        }
        hull.removeAt(hull.size - 1)
        return hull
    }

    /** 볼록 다각형 [hull]의 꼭짓점 넷으로 만드는 가장 넓은 사각형. 대각선 (i, k)마다 양쪽에서 가장 먼 점을 고른다. */
    private fun largestQuad(hull: List<Point>): List<Point>? {
        val n = hull.size
        var best = 0.0
        var result: List<Point>? = null
        for (i in 0 until n) for (k in i + 2 until n) {
            if (i == 0 && k == n - 1) continue
            var left = 0.0; var j = -1
            for (m in i + 1 until k) {
                val area = abs(cross(hull[i], hull[k], hull[m]))
                if (area > left) { left = area; j = m }
            }
            var right = 0.0; var l = -1
            for (m in (k + 1 until n) + (0 until i)) {
                val area = abs(cross(hull[i], hull[k], hull[m]))
                if (area > right) { right = area; l = m }
            }
            if (j < 0 || l < 0) continue
            if (left + right > best) {
                best = left + right
                result = listOf(hull[i], hull[j], hull[k], hull[l])
            }
        }
        return result
    }

    // 무게중심을 기준으로 화면 시계 방향(y가 아래로) 순서로 놓고, x+y가 가장 작은 점(왼쪽 위)부터 시작한다.
    private fun orderClockwise(corners: List<Point>): List<Point> {
        val cx = corners.sumOf { it.x } / 4
        val cy = corners.sumOf { it.y } / 4
        val sorted = corners.sortedBy { atan2(it.y - cy, it.x - cx) }
        val start = sorted.indices.minBy { sorted[it].x + sorted[it].y }
        return List(4) { sorted[(start + it) % 4] }
    }

    private fun modeOf(histogram: IntArray): Int =
        histogram.indices.maxBy { level -> (maxOf(0, level - 4)..minOf(255, level + 4)).sumOf { histogram[it] } }

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

    // 두 집단의 분산이 가장 잘 갈리는 문턱값.
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

    /** 감지에 쓰기 좋은 긴 변 길이(px). 더 크면 느리기만 하고 정확도는 비슷하다. */
    public const val WORKING_LONG_EDGE: Int = 256

    private const val MIN_SIDE = 8
    private const val MIN_FRACTION = 0.08
    private const val MAX_FRACTION = 0.97
    private const val MIN_CONTRAST = 45
    private const val MIN_CHROMA_CONTRAST = 25
    private const val MIN_DOCUMENT_AREA = 0.12
    private const val MIN_FILL = 0.85
    private const val MAX_FILL = 1.06
    private const val MAX_ASPECT = 6.0
    private const val EDGE_COVERAGE = 0.3
    private const val CLOSE_DIVISOR = 40
    private const val STROKE_STEP = 30
    private const val MIN_STROKES = 0.03
    private const val PAPER_BAND = 20
    private const val MIN_PAPER_SHARE = 0.4

    // 윤곽선 방식의 테두리 문턱(흐린 밝기의 이웃 차). 큰 값부터 시도한다. 7이면 밝기 차 약 13인 테두리까지 잡는다.
    private val EDGE_STEPS = intArrayOf(12, 7)
    private const val SEED_TOLERANCE = 12
    private const val EDGE_GROW = 3

    private const val MAX_HULL = 64
    private const val SIDE_SEARCH = 0.05
    private const val SIDE_OVERRUN = 0.05
    private const val INLIER_DISTANCE = 1.5
    private const val RANSAC_ROUNDS = 120
    private const val RANSAC_SEED = 17L
    private const val MIN_SIDE_POINTS = 6
    private const val MAX_CORNER_SHIFT = 0.1
}
