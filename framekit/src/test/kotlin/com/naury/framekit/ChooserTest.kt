package com.naury.framekit

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.chooser.ChooserRules
import com.naury.framekit.chooser.SourceInspector
import com.naury.framekit.chooser.SourceKind
import com.naury.framekit.core.model.MediaType
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChooserTest {

    private val all = EditorAction.entries.toSet()

    @Test
    fun `options follow the kind of sources and the allowed set`() {
        assertThat(ChooserRules.options(listOf(SourceKind.IMAGE, SourceKind.IMAGE), all).actions)
            .containsExactly(EditorAction.EDIT_PHOTO, EditorAction.MAKE_PDF, EditorAction.MOTION, EditorAction.SEND_ORIGINAL).inOrder()
        assertThat(ChooserRules.options(listOf(SourceKind.ANIMATED), all).actions)
            .containsExactly(EditorAction.MOTION, EditorAction.EDIT_PHOTO, EditorAction.SEND_ORIGINAL).inOrder()
        assertThat(ChooserRules.options(listOf(SourceKind.VIDEO), all).actions).containsExactly(EditorAction.EDIT_VIDEO, EditorAction.SEND_ORIGINAL).inOrder()
        val mixed = ChooserRules.options(listOf(SourceKind.IMAGE, SourceKind.VIDEO), all)
        assertThat(mixed.mixed).isTrue()
        assertThat(mixed.actions).containsExactly(EditorAction.SEND_ORIGINAL)
        assertThat(ChooserRules.options(listOf(SourceKind.IMAGE), setOf(EditorAction.MAKE_PDF)).actions).containsExactly(EditorAction.MAKE_PDF)
    }

    @Test
    fun `sending without editing describes the original file`() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val file = File(context.cacheDir, "photo.png")
        file.outputStream().use { Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val input = EditorInput.FileSource(file.absolutePath)
        val inspector = SourceInspector(context)
        assertThat(inspector.kind(input)).isEqualTo(SourceKind.IMAGE)
        val original = inspector.original(input, SourceKind.IMAGE)!!
        assertThat(original.isOriginal).isTrue()
        assertThat(original.mediaType).isEqualTo(MediaType.IMAGE)
        assertThat(original.width).isEqualTo(120)
        assertThat(original.height).isEqualTo(80)
        assertThat(original.fileSize).isEqualTo(file.length())
        assertThat(original.mimeType).isEqualTo("image/png")
    }

    @Test
    fun `an empty action set is rejected`() {
        assertThat(FrameKitRequest(EditorInput.Pick(), chooserActions = emptySet()).validate().isValid).isFalse()
    }
}
