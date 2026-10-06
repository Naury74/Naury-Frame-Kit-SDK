package com.naury.framekit.video

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.output.AppFileOutputStore
import com.naury.framekit.android.source.SessionSourceRegistry
import com.naury.framekit.core.geometry.PointN
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.BackgroundSpec
import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.core.overlay.OverlayTransform
import com.naury.framekit.core.overlay.TextStyleSpec
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.CanvasFit
import com.naury.framekit.core.video.CanvasSpec
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.TimedOverlay
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.TimelineTimeMapper
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import com.naury.framekit.video.export.VideoExportCoordinator
import com.naury.framekit.video.source.VideoMetadataReader
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/** 여러 클립·분할·순서 변경·배경 음악·구간 텍스트를 실제 Media3로 저장해 확인한다. */
@RunWith(AndroidJUnit4::class)
class MultiTrackExportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val registry = SessionSourceRegistry(context)
    private val store = AppFileOutputStore(context)
    private val coordinator = VideoExportCoordinator(context, store)
    private val reader = VideoMetadataReader(context, registry)
    private var withSound = SourceId("unset")
    private var silent = SourceId("unset")
    private var music = SourceId("unset")

    @Before
    fun setUp() {
        store.directory.listFiles()?.forEach(File::delete)
        val a = File(context.cacheDir, "fixture.mp4").also { if (!it.exists()) TestVideoFactory.create(it) }
        val b = File(context.cacheDir, "fixture_silent.mp4").also { if (!it.exists()) TestVideoFactory.create(it, 3_000_000, withAudio = false) }
        val m = File(context.cacheDir, "music.m4a").also { if (!it.exists()) TestVideoFactory.createAudio(it, 2_000_000) }
        withSound = registry.register(EditorInput.FileSource(a.absolutePath))
        silent = registry.register(EditorInput.FileSource(b.absolutePath))
        music = registry.register(EditorInput.FileSource(m.absolutePath))
    }

    @Test
    fun clipsWithAndWithoutSoundJoinInOrder() = runBlocking {
        val timeline = Timeline(
            listOf(
                VideoClip("a", withSound, TimeRangeUs(0, 2_000_000)),
                VideoClip("b", silent, TimeRangeUs(0, 1_500_000)),
            ),
        )
        val media = export(timeline)

        assertDuration(media.durationMs!! * 1000, 3_500_000)
        assertThat(hasAudioTrack()).isTrue()
    }

    @Test
    fun splitAndReorderKeepTheTotalDuration() = runBlocking {
        val original = Timeline(listOf(VideoClip("a", withSound, TimeRangeUs(0, 4_000_000), speed = 2.0)))
        val split = checkNotNull(TimelineTimeMapper.split(original, 500_000, 100_000, "b"))
        val reordered = TimelineTimeMapper.move(split, 1, 0)

        val media = export(reordered)

        assertThat(TimelineTimeMapper.durationUs(reordered)).isEqualTo(2_000_000)
        assertDuration(media.durationMs!! * 1000, 2_000_000)
    }

    @Test
    fun loopedMusicNeverMakesTheVideoLonger() = runBlocking {
        val timeline = Timeline(
            videoClips = listOf(VideoClip("a", silent, TimeRangeUs(0, 3_000_000))),
            audioClips = listOf(AudioClip("m", music, TimeRangeUs(0, 1_000_000), timelineStartUs = 500_000, volume = 0.8, loop = true)),
        )
        val media = export(timeline)

        assertDuration(media.durationMs!! * 1000, 3_000_000)
        assertThat(hasAudioTrack()).isTrue()
    }

    @Test
    fun timedTextIsDrawnOnlyDuringItsRange() = runBlocking {
        val text = ImageOverlay.Text(
            "t",
            "FrameKit",
            TextStyleSpec(colorArgb = Color.BLACK, background = BackgroundSpec(Color.BLACK, 0.05, 0.0)),
            OverlayTransform(PointN(0.5, 0.5), scale = 2.0),
        )
        val timeline = Timeline(
            videoClips = listOf(VideoClip("a", withSound, TimeRangeUs(0, 2_000_000))),
            overlays = listOf(TimedOverlay(text, TimeRangeUs(0, 1_000_000))),
        )
        export(timeline)

        // 사분면 경계(가운데)는 원래 밝은 색이 섞이는 곳이라, 검은 글상자가 있으면 어두워진다.
        val during = frameAt(300_000).getPixel(320 - 40, 180)
        val after = frameAt(1_500_000).getPixel(320 - 40, 180)
        assertWithMessage("during #${Integer.toHexString(during)}").that(brightness(during)).isLessThan(60)
        assertWithMessage("after #${Integer.toHexString(after)}").that(brightness(after)).isGreaterThan(60)
    }

    @Test
    fun squareCanvasFitsWithBarsOrFillsWithoutThem() = runBlocking {
        val timeline = Timeline(listOf(VideoClip("a", withSound, TimeRangeUs(0, 1_000_000))))

        val fit = export(timeline, CanvasSpec(1, 1, CanvasFit.FIT))
        assertThat(fit.width).isEqualTo(fit.height)
        assertWithMessage("fit top").that(brightness(frameAt(300_000).getPixel(fit.width / 2, 4))).isLessThan(20)
        store.directory.listFiles()?.forEach(File::delete)

        val fill = export(timeline, CanvasSpec(1, 1, CanvasFit.FILL))
        assertThat(fill.width).isEqualTo(fill.height)
        assertWithMessage("fill top").that(brightness(frameAt(300_000).getPixel(fill.width / 4, 4))).isGreaterThan(60)
    }

    private suspend fun export(timeline: Timeline, canvas: CanvasSpec = CanvasSpec()) = coordinator.export(
        project = VideoProject(ProjectId("p"), timeline, canvas),
        sources = timeline.videoClips.map { it.source }.distinct().associateWith { reader.read(it) },
        locations = (timeline.videoClips.map { it.source } + timeline.audioClips.map { it.source }).distinct()
            .associateWith { checkNotNull(registry.location(it)) },
        audioSources = timeline.audioClips.map { it.source }.distinct().associateWith { reader.readAudio(it) },
    )

    private fun published(): File = store.directory.listFiles()!!.single { !it.name.startsWith(".") }

    private fun hasAudioTrack(): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(published().absolutePath)
            (0 until extractor.trackCount).any { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
        } finally {
            extractor.release()
        }
    }

    private fun frameAt(timeUs: Long): Bitmap = MediaMetadataRetriever().run {
        setDataSource(published().absolutePath)
        checkNotNull(getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)).also { release() }
    }

    private fun brightness(color: Int) = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3

    private fun assertDuration(actualUs: Long, expectedUs: Long) {
        assertThat(abs(actualUs - expectedUs)).isAtMost(1_000_000L / TestVideoFactory.FPS + 1_000)
    }
}
