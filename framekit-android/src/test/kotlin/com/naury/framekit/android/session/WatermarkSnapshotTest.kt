package com.naury.framekit.android.session

import com.google.common.truth.Truth.assertThat
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.core.model.ProjectId
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.core.overlay.Watermark
import com.naury.framekit.core.overlay.WatermarkPattern
import com.naury.framekit.core.overlay.WatermarkPosition
import com.naury.framekit.core.video.TimeRangeUs
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.core.video.VideoClip
import com.naury.framekit.core.video.VideoProject
import kotlinx.serialization.json.Json
import org.junit.Test

class WatermarkSnapshotTest {

    private val watermark = Watermark(
        text = "© Naury",
        pattern = WatermarkPattern.BAND,
        position = WatermarkPosition.TOP_START,
        sizeRatio = 0.05,
        opacity = 0.4,
        colorArgb = 0xFF112233.toInt(),
        rotationDegrees = 15.0,
        density = 1.5,
        shadow = false,
        background = true,
    )

    @Test
    fun `image watermark survives a json round trip`() {
        val project = ImageProject(ProjectId("p"), SourceId("s"), watermark = watermark)

        val json = Json.encodeToString(ImageProjectSnapshot.serializer(), ImageProjectSnapshot.of(project))
        val restored = Json.decodeFromString(ImageProjectSnapshot.serializer(), json).toProject(SourceId("s"))

        assertThat(restored.watermark).isEqualTo(watermark)
    }

    @Test
    fun `video watermark survives a json round trip`() {
        val clip = VideoClip("c", SourceId("v"), TimeRangeUs(0, 1_000_000))
        val project = VideoProject(ProjectId("p"), Timeline(listOf(clip), watermark = watermark))

        val json = Json.encodeToString(VideoProjectSnapshot.serializer(), VideoProjectSnapshot.of(project, listOf(SourceId("v"))))
        val restored = Json.decodeFromString(VideoProjectSnapshot.serializer(), json).toProject(listOf(SourceId("v")))

        assertThat(restored.timeline.watermark).isEqualTo(watermark)
    }

    @Test
    fun `old sessions without a watermark and unknown names still read`() {
        val snapshot = WatermarkSnapshot(text = "x", pattern = "SPIRAL", position = "NOWHERE")

        assertThat(snapshot.toModel().pattern).isEqualTo(WatermarkPattern.SINGLE)
        assertThat(snapshot.toModel().position).isEqualTo(WatermarkPosition.BOTTOM_END)
    }
}
