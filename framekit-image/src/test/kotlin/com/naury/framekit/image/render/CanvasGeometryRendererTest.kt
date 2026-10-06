package com.naury.framekit.image.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.core.geometry.Affine2D
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.MediaType
import com.naury.framekit.core.model.PixelSize
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.model.SourceMetadata
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.testing.TestImages
import com.naury.framekit.image.testing.colorNear
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CanvasGeometryRendererTest {

    private val sourceId = SourceId("s")
    private val metadata = SourceMetadata(sourceId, MediaType.IMAGE, "image/png", PixelSize(400, 200))
    private val project = ImageProject(ProjectId("p"), sourceId)

    // 원본은 400x200이지만 preview처럼 절반 크기로 디코딩된 bitmap을 쓴다.
    private val decoded = DecodedImage(TestImages.quadrants(200, 100), PixelSize(400, 200), hasGainMap = false)

    @Test
    fun `clockwise turn puts the bottom left quadrant at top left`() {
        val geometry = GeometryOperations.rotateClockwise(GeometryEdit())
        val output = render(geometry)

        assertThat(PixelSize(output.width, output.height)).isEqualTo(PixelSize(200, 400))
        assertCorners(output, TestImages.BOTTOM_LEFT, TestImages.TOP_LEFT, TestImages.BOTTOM_RIGHT, TestImages.TOP_RIGHT)
    }

    @Test
    fun `horizontal flip mirrors the quadrants`() {
        val output = render(GeometryOperations.flipHorizontal(GeometryEdit()))

        assertCorners(output, TestImages.TOP_RIGHT, TestImages.TOP_LEFT, TestImages.BOTTOM_RIGHT, TestImages.BOTTOM_LEFT)
    }

    @Test
    fun `crop to the right half keeps only right quadrants at full resolution`() {
        val output = render(GeometryEdit(crop = RectN(0.5, 0.0, 1.0, 1.0)))

        assertThat(PixelSize(output.width, output.height)).isEqualTo(PixelSize(200, 200))
        assertCorners(output, TestImages.TOP_RIGHT, TestImages.TOP_RIGHT, TestImages.BOTTOM_RIGHT, TestImages.BOTTOM_RIGHT)
    }

    @Test
    fun `preview through a viewport transform matches the export pixels`() {
        val geometry = GeometryOperations.rotateClockwise(GeometryEdit(crop = RectN(0.1, 0.0, 0.9, 1.0)))
        val edited = project.copy(geometry = geometry)
        val outputSize = ImageRenderPlanFactory.outputSize(edited, metadata, 16_000_000L)
        val plan = ImageRenderPlanFactory.create(edited, metadata, outputSize)
        val exported = CanvasGeometryRenderer.render(decoded, plan, backgroundArgb = Color.BLACK)

        // 화면 preview: 같은 plan을 절반 크기 viewport에 맞추고 여백 offset을 더해 그린다.
        val viewport = Bitmap.createBitmap(outputSize.width / 2 + 40, outputSize.height / 2 + 40, Bitmap.Config.ARGB_8888)
        val outputToViewport = Affine2D.translate(20.0, 20.0) * Affine2D.scale(0.5)
        Canvas(viewport).apply {
            drawColor(Color.BLACK)
            CanvasGeometryRenderer.draw(this, decoded, plan, outputToViewport)
        }

        listOf(0.2 to 0.2, 0.8 to 0.3, 0.3 to 0.85, 0.75 to 0.75).forEach { (nx, ny) ->
            val exportPixel = exported.getPixel((nx * exported.width).toInt(), (ny * exported.height).toInt())
            val previewX = (20 + nx * outputSize.width / 2).toInt()
            val previewY = (20 + ny * outputSize.height / 2).toInt()
            assertWithMessage("($nx, $ny)").that(viewport.colorNear(previewX, previewY, exportPixel, tolerance = 4)).isTrue()
        }
        // clip 밖 여백에는 아무것도 그리지 않는다.
        assertThat(viewport.getPixel(5, 5)).isEqualTo(Color.BLACK)
    }

    @Test
    fun `transparent areas are kept unless a background is given`() {
        val transparent = DecodedImage(TestImages.quadrants(200, 100, hasAlpha = true), PixelSize(400, 200), hasGainMap = false)
        val plan = ImageRenderPlanFactory.create(project, metadata, PixelSize(400, 200))

        val kept = CanvasGeometryRenderer.render(transparent, plan, backgroundArgb = null)
        val flattened = CanvasGeometryRenderer.render(transparent, plan, backgroundArgb = Color.BLACK)

        assertThat(Color.alpha(kept.getPixel(350, 150))).isEqualTo(0)
        assertThat(flattened.getPixel(350, 150)).isEqualTo(Color.BLACK)
    }

    private fun render(geometry: GeometryEdit): Bitmap {
        val edited = project.copy(geometry = geometry)
        val size = ImageRenderPlanFactory.outputSize(edited, metadata, 16_000_000L)
        return CanvasGeometryRenderer.render(decoded, ImageRenderPlanFactory.create(edited, metadata, size), backgroundArgb = null)
    }

    private fun assertCorners(bitmap: Bitmap, topLeft: Int, topRight: Int, bottomLeft: Int, bottomRight: Int) {
        val left = bitmap.width / 8
        val right = bitmap.width * 7 / 8
        val top = bitmap.height / 8
        val bottom = bitmap.height * 7 / 8
        assertWithMessage("top left").that(bitmap.colorNear(left, top, topLeft)).isTrue()
        assertWithMessage("top right").that(bitmap.colorNear(right, top, topRight)).isTrue()
        assertWithMessage("bottom left").that(bitmap.colorNear(left, bottom, bottomLeft)).isTrue()
        assertWithMessage("bottom right").that(bitmap.colorNear(right, bottom, bottomRight)).isTrue()
    }
}
