package com.naury.framekit.image.overlay

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.DrawingStroke
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.StrokePoint
import com.naury.framekit.core.overlay.TextStyleSpec
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class OverlayRendererTest {

    private val renderer = OverlayRenderer()

    @Test
    fun `G05 overlay keeps normalized position and size between 1080p and 4K`() {
        val text = ImageOverlay.Text("t", "FrameKit 편집", TextStyleSpec(fontSizeHeightRatio = 0.05), OverlayTransform(PointN(0.3, 0.6), 1.2, 15.0))
        val sticker = ImageOverlay.Sticker("s", EmojiCatalog.assetId("🎉"), 0.2, OverlayTransform(PointN(0.7, 0.4), 0.8, -30.0))
        val hd = PixelSize(1920, 1080)
        val uhd = PixelSize(3840, 2160)

        listOf(text, sticker).forEach { overlay ->
            val small = renderer.corners(overlay, hd).map { PointN(it.x / hd.width, it.y / hd.height) }
            val large = renderer.corners(overlay, uhd).map { PointN(it.x / uhd.width, it.y / uhd.height) }
            small.zip(large).forEach { (a, b) ->
                // 글꼴 hinting 차이를 감안해 정규화 좌표 0.5% 이내.
                assertThat(a.x).isWithin(0.005).of(b.x)
                assertThat(a.y).isWithin(0.005).of(b.y)
            }
        }
    }

    @Test
    fun `eraser removes only the drawing layer`() {
        val size = PixelSize(100, 100)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val pen = stroke("pen", BrushKind.PEN, Color.BLUE, listOf(0.1 to 0.5, 0.9 to 0.5))
        val eraser = stroke("eraser", BrushKind.ERASER, Color.BLACK, listOf(0.5 to 0.1, 0.5 to 0.9))

        renderer.draw(Canvas(bitmap), size, emptyList(), listOf(pen, eraser))

        assertThat(bitmap.getPixel(20, 50)).isEqualTo(Color.BLUE)
        assertThat(bitmap.getPixel(50, 50)).isEqualTo(Color.RED)
        assertThat(bitmap.getPixel(50, 20)).isEqualTo(Color.RED)
    }

    @Test
    fun `highlighter does not darken where one stroke overlaps itself`() {
        val size = PixelSize(100, 100)
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }
        // 같은 지점을 두 번 지나는 획: (20,50)→(80,50)→(50,20)→(50,80)
        val highlight = stroke("h", BrushKind.HIGHLIGHTER, Color.YELLOW, listOf(0.2 to 0.5, 0.8 to 0.5, 0.5 to 0.2, 0.5 to 0.8))

        renderer.draw(Canvas(bitmap), size, emptyList(), listOf(highlight))

        val single = bitmap.getPixel(30, 50)
        val crossing = bitmap.getPixel(50, 50)
        assertThat(Color.blue(crossing)).isWithin(3).of(Color.blue(single))
        assertThat(Color.blue(single)).isLessThan(255)
    }

    @Test
    fun `text and sticker paint pixels inside their boxes only`() {
        val size = PixelSize(400, 300)
        val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
        val text = ImageOverlay.Text("t", "안녕 Hello", TextStyleSpec(colorArgb = Color.WHITE), OverlayTransform(PointN(0.5, 0.25)))
        val sticker = ImageOverlay.Sticker("s", EmojiCatalog.assetId("😀"), 0.2, OverlayTransform(PointN(0.5, 0.75)))

        renderer.draw(Canvas(bitmap), size, listOf(text, sticker), emptyList())

        assertThat(countOpaque(bitmap, 0, 0, 400, 150)).isGreaterThan(50)
        assertThat(countOpaque(bitmap, 0, 150, 400, 300)).isGreaterThan(50)
        assertThat(Color.alpha(bitmap.getPixel(5, 5))).isEqualTo(0)
    }

    @Test
    fun `hit test respects rotation and returns the top most overlay`() {
        val size = PixelSize(1000, 1000)
        val bottom = ImageOverlay.Sticker("bottom", EmojiCatalog.assetId("⭐"), 0.3)
        val top = ImageOverlay.Sticker("top", EmojiCatalog.assetId("❤️"), 0.1, OverlayTransform(rotationDegrees = 45.0))

        assertThat(renderer.hitTest(listOf(bottom, top), 500.0, 500.0, size)).isEqualTo("top")
        // 45° 회전한 정사각형의 모서리 방향(대각선 바깥)은 맞지 않고 변 방향 바깥만 맞는다.
        assertThat(renderer.hitTest(listOf(top), 545.0, 545.0, size)).isNull()
        assertThat(renderer.hitTest(listOf(top), 565.0, 500.0, size)).isEqualTo("top")
        assertThat(renderer.hitTest(listOf(bottom, top), 620.0, 500.0, size)).isEqualTo("bottom")
        assertThat(renderer.hitTest(listOf(bottom), 900.0, 900.0, size)).isNull()
    }

    private fun stroke(id: String, brush: BrushKind, color: Int, points: List<Pair<Double, Double>>) =
        DrawingStroke(id, points.map { StrokePoint(it.first, it.second) }, 0.1, color, 1.0, brush)

    private fun countOpaque(bitmap: Bitmap, left: Int, top: Int, right: Int, bottom: Int): Int {
        var count = 0
        for (y in top until bottom) for (x in left until right) if (Color.alpha(bitmap.getPixel(x, y)) > 0) count++
        return count
    }
}
