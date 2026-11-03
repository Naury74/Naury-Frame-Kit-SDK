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
    fun `mime types decide between the photo, video and audio editor`() {
        assertThat(MediaKindResolver.fromMime("image/heic")).isEqualTo(MediaKind.IMAGE)
        assertThat(MediaKindResolver.fromMime("video/mp4")).isEqualTo(MediaKind.VIDEO)
        assertThat(MediaKindResolver.fromMime("audio/mpeg")).isEqualTo(MediaKind.AUDIO)
        assertThat(MediaKindResolver.fromMime("application/pdf")).isNull()
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

    @Test
    fun `mixed photos and videos are edited one by one unless rejected`() {
        val request = FrameKitRequest(EditorInput.Pick(MediaKind.ANY, maxItems = 10))
        assertThat(request.mixedSelection).isEqualTo(MixedSelection.EDIT)
        assertThat(EditorAction.EDIT_EACH in request.chooserActions).isTrue()
        val video = request.forVideo(EditorInput.FileSource("/b.mp4"))
        assertThat(video.copy(config = video.config.copy(maxClipCount = 1)).validate()).isInstanceOf(ValidationResult.Valid::class.java)
    }

    @Test
    fun `automatic picks offer only formats the target editor opens`() {
        val auto = FrameKitRequest(EditorInput.Pick(MediaKind.IMAGE))
        assertThat(routerPickMimeTypes(auto, MediaKind.IMAGE, 1)).containsExactly("image/jpeg", "image/png", "image/webp", "image/gif")
        assertThat(routerPickMimeTypes(auto, MediaKind.IMAGE, 5)).doesNotContain("image/gif")
        assertThat(routerPickMimeTypes(auto, MediaKind.ANY, 5)).contains("video/*")
        assertThat(routerPickMimeTypes(auto, MediaKind.VIDEO, 1)).isEmpty()
        assertThat(routerPickMimeTypes(auto.copy(chooser = EditorChooser.ASK), MediaKind.IMAGE, 1)).isEmpty()
        assertThat(routerPickMimeTypes(auto.copy(openAnimatedInMotionEditor = false), MediaKind.IMAGE, 1)).doesNotContain("image/gif")
    }

    @Test
    fun `a pick where everything was skipped is told apart from closing`() {
        val contract = RouterPickerContract()
        val skipped = android.content.Intent()
            .putParcelableArrayListExtra(com.naury.framekit.ui.picker.MediaPickerContract.EXTRA_RESULT, ArrayList<com.naury.framekit.ui.picker.PickedMedia>())
            .putExtra(com.naury.framekit.ui.picker.MediaPickerContract.EXTRA_SKIPPED, 2)
        assertThat(contract.parseResult(android.app.Activity.RESULT_OK, skipped)).isNull()
        assertThat(contract.parseResult(android.app.Activity.RESULT_CANCELED, null)).isEqualTo(com.naury.framekit.ui.picker.MediaPickerResult.Cancelled)
    }
}
