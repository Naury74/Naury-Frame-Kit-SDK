package com.naury.framekit

import android.app.Application
import android.webkit.MimeTypeMap
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.image.contract.ImageEditorConfig
import com.naury.framekit.ui.video.contract.VideoEditorConfig
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class FrameKitRoutingTest {

    private val resolver = ApplicationProvider.getApplicationContext<Application>().contentResolver

    @Test
    fun `mime types decide between the photo and the video editor`() {
        assertThat(MediaKindResolver.fromMime("image/heic")).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaKindResolver.fromMime("video/mp4")).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaKindResolver.fromMime("audio/mpeg")).isNull()
        assertThat(MediaKindResolver.fromMime(null)).isNull()
    }

    @Test
    fun `files fall back to their extension and picks keep their kind`() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("mp4", "video/mp4")
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
        }

        assertThat(MediaKindResolver.resolve(EditorInput.FileSource("/data/clip.MP4"), resolver)).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaKindResolver.resolve(EditorInput.FileSource("/data/photo.jpg"), resolver)).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaKindResolver.resolve(EditorInput.FileSource("/data/notes.txt"), resolver)).isNull()
        assertThat(MediaKindResolver.resolve(EditorInput.Pick(MediaKind.VIDEO), resolver)).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaKindResolver.resolve(EditorInput.Pick(MediaKind.ANY), resolver)).isNull()
    }

    @Test
    fun `each editor receives its own configuration and the shared ui`() {
        val request = FrameKitRequest(
            EditorInput.Pick(MediaKind.ANY),
            image = ImageEditorConfig(allowRedo = false),
            video = VideoEditorConfig(maxTimelineDurationUs = 15_000_000),
        )
        val input = EditorInput.FileSource("/data/clip.mp4")

        assertThat(request.forVideo(input).config.maxTimelineDurationUs).isEqualTo(15_000_000)
        assertThat(request.forImage(input).config.allowRedo).isFalse()
        assertThat(request.forVideo(input).ui).isEqualTo(request.ui)
        assertThat(request.validate()).isEqualTo(ValidationResult.Valid)
        assertThat(request.copy(video = VideoEditorConfig(allowUndo = false)).validate()).isInstanceOf(ValidationResult.Invalid::class.java)
    }

    @Test
    fun `pick counts are clamped to each editor and mixed lists do not resolve`() {
        shadowOf(MimeTypeMap.getSingleton()).apply {
            addExtensionMimeTypeMapping("mp4", "video/mp4")
            addExtensionMimeTypeMapping("jpg", "image/jpeg")
        }
        val request = FrameKitRequest(EditorInput.Pick(MediaKind.ANY, maxItems = 50), image = ImageEditorConfig(maxImageCount = 5))

        assertThat(request.forImage(request.input).input).isEqualTo(EditorInput.Pick(MediaKind.IMAGE, 5))
        assertThat(request.forVideo(request.input).input).isEqualTo(EditorInput.Pick(MediaKind.VIDEO, 10))
        val mixed = EditorInput.Multiple(listOf(EditorInput.FileSource("/a.jpg"), EditorInput.FileSource("/b.mp4")))
        val photos = EditorInput.Multiple(listOf(EditorInput.FileSource("/a.jpg"), EditorInput.FileSource("/c.jpg")))
        assertThat(MediaKindResolver.resolve(mixed, resolver)).isNull()
        assertThat(MediaKindResolver.resolve(photos, resolver)).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaKindResolver.resolve(EditorInput.Capture(MediaKind.VIDEO), resolver)).isEqualTo(MediaKind.VIDEO)
        assertThat(FrameKitRequest(EditorInput.Capture(MediaKind.ANY)).validate()).isInstanceOf(ValidationResult.Invalid::class.java)
    }
}
