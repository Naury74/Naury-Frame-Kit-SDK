package com.naury.framekit.image.cutout

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.SubjectCutout
import com.naury.framekit.image.decode.ImageMetadataReader
import com.naury.framekit.image.export.ImageExportConfig
import com.naury.framekit.image.export.ImageExportCoordinator
import com.naury.framekit.image.export.ImageFormat
import com.naury.framekit.image.testing.FileSourceResolver
import com.naury.framekit.image.testing.TestImages
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class CutoutTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sourceFile = File(context.cacheDir, "subject.png")
    private val resolver = FileSourceResolver("s" to sourceFile)
    private val store = AppFileOutputStore(context, availableBytes = { Long.MAX_VALUE })
    private val assets = ProjectAssetStore(File(context.filesDir, "assets-test"))
    private val coordinator = ImageExportCoordinator(resolver, store, 256L * 1024 * 1024, Dispatchers.Unconfined)

    @Before
    fun setUp() {
        FileProvider::class.java.getDeclaredField("sCache").apply { isAccessible = true }.let { (it.get(null) as MutableMap<*, *>).clear() }
        sourceFile.outputStream().use { TestImages.quadrants(200, 100).compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    // 왼쪽 절반만 피사체인 저해상도 마스크(원본의 1/4 크기).
    private fun leftHalfMask(): Bitmap = Bitmap.createBitmap(50, 25, Bitmap.Config.ALPHA_8).apply {
        for (y in 0 until 25) for (x in 0 until 50) setPixel(x, y, if (x < 25) Color.BLACK else Color.TRANSPARENT)
    }

    @Test
    fun `mask asset round trips and removes the background in a PNG export`() = runBlocking {
        val id = CutoutMasks.save(leftHalfMask(), assets)
        assertThat(SubjectCutout.isValidAssetId(id)).isTrue()
        val project = ImageProject(ProjectId("p"), SourceId("s"), cutout = SubjectCutout(id))

        coordinator.export(project, ImageMetadataReader(resolver).read(SourceId("s")), ImageExportConfig(format = ImageFormat.PNG), assets = assets)

        val output = BitmapFactory.decodeFile(store.directory.listFiles()!!.single { !it.name.startsWith(".") }.absolutePath)
        assertThat(output.getPixel(20, 20)).isEqualTo(TestImages.TOP_LEFT)
        assertThat(Color.alpha(output.getPixel(180, 20))).isEqualTo(0)
    }

    @Test
    fun `JPEG export fills the removed background with the configured color`() = runBlocking {
        val id = CutoutMasks.save(leftHalfMask(), assets)
        val project = ImageProject(ProjectId("p"), SourceId("s"), cutout = SubjectCutout(id))

        coordinator.export(project, ImageMetadataReader(resolver).read(SourceId("s")), ImageExportConfig(jpegBackgroundArgb = Color.WHITE), assets = assets)

        val output = BitmapFactory.decodeFile(store.directory.listFiles()!!.single { !it.name.startsWith(".") }.absolutePath)
        val background = output.getPixel(180, 70)
        assertThat(Color.red(background)).isGreaterThan(240)
        assertThat(Color.blue(background)).isGreaterThan(240)
    }

    @Test
    fun `missing mask asset is an invalid project`() {
        val project = ImageProject(ProjectId("p"), SourceId("s"), cutout = SubjectCutout("missing.png"))

        val error = assertThrows(FrameKitException::class.java) {
            runBlocking { coordinator.export(project, ImageMetadataReader(resolver).read(SourceId("s")), ImageExportConfig(), assets = assets) }
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.INVALID_PROJECT)
    }

    @Test
    fun `no remover is found without the optional module`() {
        assertThat(BackgroundRemovers.find(context)).isNull()
    }

    @Test
    fun `asset ids cannot escape the asset folder`() {
        assertThat(assets.file("../exports/x.png")).isNull()
        assertThat(SubjectCutout.isValidAssetId("../x.png")).isFalse()
    }
}
