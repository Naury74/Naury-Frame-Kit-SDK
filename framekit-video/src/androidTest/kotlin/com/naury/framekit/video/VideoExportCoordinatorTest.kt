package com.naury.framekit.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.effect.FilterSelection
import com.naury.framekit.core.geometry.GeometryEdit
import com.naury.framekit.core.geometry.GeometryOperations
import com.naury.framekit.core.geometry.RectN
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.overlay.PrivacyMask
import com.naury.framekit.core.video.ClipEffects
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.export.VideoExportCoordinator
import com.naury.framekit.video.source.VideoMetadataReader
import com.naury.framekit.video.source.VideoSourceInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** Real Media3 exports on the device: exact trim, speed, geometry direction, color and cancel. */
@RunWith(AndroidJUnit4::class)
class VideoExportCoordinatorTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val sourceFile = File(context.cacheDir, "fixture.mp4")
    private val registry = SessionSourceRegistry(context)
    private val store = AppFileOutputStore(context)
    private val coordinator = VideoExportCoordinator(context, store)
    private var sourceId = SourceId("unset")
    private lateinit var info: VideoSourceInfo

    @Before
    fun setUp() {
        store.directory.listFiles()?.forEach(File::delete)
        if (!sourceFile.exists()) TestVideoFactory.create(sourceFile)
        sourceId = registry.register(EditorInput.FileSource(sourceFile.absolutePath))
        info = VideoMetadataReader(context, registry).read(sourceId)
    }

    @Test
    fun metadataReportsDurationSizeAndAudio() {
        assertThat(info.metadata.uprightSize.width).isEqualTo(TestVideoFactory.WIDTH)
        assertThat(info.metadata.uprightSize.height).isEqualTo(TestVideoFactory.HEIGHT)
        assertThat(info.metadata.hasAudio).isTrue()
        assertThat(info.metadata.durationUs!!).isIn(4_900_000L..5_100_000L)
    }

    @Test
    fun trimIsAccurateToOneFrame() = runBlocking {
        val media = export(clip(TimeRangeUs(1_000_000, 4_000_000)))

        assertDuration(media.durationMs!! * 1000, 3_000_000)
        assertThat(media.mimeType).isEqualTo("video/mp4")
    }

    @Test
    fun doubleSpeedHalvesTheDuration() = runBlocking {
        val media = export(clip(TimeRangeUs(1_000_000, 4_000_000)).copy(speed = 2.0))

        assertDuration(media.durationMs!! * 1000, 1_500_000)
    }

    @Test
    fun clockwiseTurnMatchesTheImageModel() = runBlocking {
        val geometry = GeometryOperations.rotateClockwise(GeometryEdit())
        val media = export(clip(TimeRangeUs(0, 2_000_000)).copy(effects = ClipEffects(geometry = geometry)))

        assertThat(media.width).isLessThan(media.height)
        val frame = firstFrame()
        // 사진과 같은 규칙: 시계 방향 90°면 원본 왼쪽 아래(파랑)가 왼쪽 위로 온다.
        assertColor("top left", frame, 0.2, 0.2, Color.BLUE)
        assertColor("top right", frame, 0.8, 0.2, Color.RED)
        assertColor("bottom left", frame, 0.2, 0.8, Color.WHITE)
        assertColor("bottom right", frame, 0.8, 0.8, Color.GREEN)
    }

    @Test
    fun horizontalFlipWithTurnMatchesTheImageModel() = runBlocking {
        val geometry = GeometryOperations.flipHorizontal(GeometryOperations.rotateClockwise(GeometryEdit()))
        export(clip(TimeRangeUs(0, 2_000_000)).copy(effects = ClipEffects(geometry = geometry)))

        val frame = firstFrame()
        assertColor("top left", frame, 0.2, 0.2, Color.RED)
        assertColor("top right", frame, 0.8, 0.2, Color.BLUE)
    }

    @Test
    fun monoFilterIsAppliedToFrames() = runBlocking {
        export(clip(TimeRangeUs(0, 2_000_000)).copy(effects = ClipEffects(filter = FilterSelection("mono", 1.0))))

        val pixel = sample(firstFrame(), 0.25, 0.25)
        assertThat(abs(Color.red(pixel) - Color.green(pixel))).isAtMost(12)
        assertThat(abs(Color.green(pixel) - Color.blue(pixel))).isAtMost(12)
    }

    @Test
    fun mosaicCoversItsAreaOnlyDuringItsRange() = runBlocking {
        val mask = PrivacyMask("m", MaskShape.Rectangle(RectN(0.25, 0.25, 0.75, 0.75)), PrivacyEffect.Mosaic(0.2))
        export(clip(TimeRangeUs(0, 2_000_000)), listOf(TimedPrivacyMask(mask, TimeRangeUs(0, 1_000_000))))

        // 중앙 블록은 네 사분면에 걸쳐 있어 평균색이 되고, 마스크 밖과 구간 밖 프레임은 그대로다.
        val masked = sample(firstFrame(), 0.52, 0.52)
        assertThat(maxOf(Color.red(masked), Color.green(masked), Color.blue(masked)) -
            minOf(Color.red(masked), Color.green(masked), Color.blue(masked))).isAtMost(110)
        assertColor("outside mask", firstFrame(), 0.1, 0.1, Color.RED)
        assertColor("after range", frameAt(1_500_000), 0.52, 0.52, Color.WHITE)
    }

    @Test
    fun cancelLeavesNoFile() = runBlocking {
        val job = async { export(clip(TimeRangeUs(0, 5_000_000))) }
        delay(300)
        job.cancel()
        runCatching { job.await() }.exceptionOrNull().let { assertThat(it).isInstanceOf(CancellationException::class.java) }

        assertThat(store.directory.listFiles().orEmpty()).isEmpty()
    }

    private fun clip(range: TimeRangeUs) = VideoClip("c", sourceId, range)

    private suspend fun export(clip: VideoClip, masks: List<TimedPrivacyMask> = emptyList()) = coordinator.export(
        project = VideoProject(ProjectId("p"), Timeline(listOf(clip), privacyMasks = masks)),
        sources = mapOf(sourceId to info),
        locations = mapOf(sourceId to checkNotNull(registry.location(sourceId))),
    )

    private fun published(): File = store.directory.listFiles()!!.single { !it.name.startsWith(".") }

    private fun firstFrame(): Bitmap = frameAt(200_000)

    private fun frameAt(timeUs: Long): Bitmap = MediaMetadataRetriever().run {
        setDataSource(published().absolutePath)
        checkNotNull(getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)).also { release() }
    }

    private fun sample(frame: Bitmap, u: Double, v: Double) = frame.getPixel((u * frame.width).toInt(), (v * frame.height).toInt())

    private fun assertColor(label: String, frame: Bitmap, u: Double, v: Double, expected: Int) {
        val actual = sample(frame, u, v)
        val close = abs(Color.red(actual) - Color.red(expected)) < 70 &&
            abs(Color.green(actual) - Color.green(expected)) < 70 &&
            abs(Color.blue(actual) - Color.blue(expected)) < 70
        assertWithMessage("$label was #${Integer.toHexString(actual)}").that(close).isTrue()
    }

    // 출력 길이는 요청 길이에서 1프레임(30fps 기준 33.3ms) 이내여야 한다.
    private fun assertDuration(actualUs: Long, expectedUs: Long) {
        assertThat(abs(actualUs - expectedUs)).isAtMost(FRAME_US + 1_000)
    }

    private companion object {
        const val FRAME_US = 1_000_000L / TestVideoFactory.FPS
    }
}
