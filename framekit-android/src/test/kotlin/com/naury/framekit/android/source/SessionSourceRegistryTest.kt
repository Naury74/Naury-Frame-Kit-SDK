package com.naury.framekit.android.source

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SessionSourceRegistryTest {

    private val context = ApplicationProvider.getApplicationContext<android.app.Application>()
    private val registry = SessionSourceRegistry(context)

    @Test
    fun `readable file source can be reopened`() {
        val file = File(context.filesDir, "source.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }

        val id = registry.register(EditorInput.FileSource(file.absolutePath))

        assertThat(registry.openInputStream(id).use { it.readBytes() }).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(registry.openInputStream(id).use { it.readBytes() }).hasLength(3)
        assertThat(registry.describe(id)).isEqualTo(EditorInput.FileSource(file.absolutePath))
    }

    @Test
    fun `missing file is reported as source unavailable`() {
        val error = assertThrows(FrameKitException::class.java) {
            registry.register(EditorInput.FileSource(File(context.filesDir, "missing.jpg").absolutePath))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.SOURCE_UNAVAILABLE)
    }

    @Test
    fun `Q02 Uri without read grant is reported as permission denied`() {
        Robolectric.setupContentProvider(DenyingProvider::class.java, DENYING_AUTHORITY)

        val error = assertThrows(FrameKitException::class.java) {
            registry.register(EditorInput.UriSource(Uri.parse("content://$DENYING_AUTHORITY/image/1")))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.PERMISSION_DENIED)
    }

    @Test
    fun `picker request cannot be registered directly`() {
        val error = assertThrows(FrameKitException::class.java) {
            registry.register(EditorInput.Pick(MediaKind.IMAGE))
        }

        assertThat(error.code).isEqualTo(EditorErrorCode.INVALID_CONFIGURATION)
    }

    class DenyingProvider : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor = throw SecurityException("denied")
        override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
        override fun getType(uri: Uri): String? = null
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
        override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
    }

    private companion object {
        const val DENYING_AUTHORITY = "com.naury.framekit.test.denying"
    }
}
