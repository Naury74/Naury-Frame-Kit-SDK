package com.naury.framekit.android.source

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.model.SourceId
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sources registered for one editing session.
 *
 * The registry never takes or releases grants that the host provided. It only remembers which
 * reference belongs to which [SourceId].
 */
public class SessionSourceRegistry(context: Context) : SourceResolver {

    private val contentResolver: ContentResolver = context.applicationContext.contentResolver
    private val sources = ConcurrentHashMap<SourceId, RegisteredSource>()

    /**
     * Registers a source and verifies that it can be opened.
     *
     * @throws FrameKitException when the source is not readable or [input] is a picker request.
     */
    public fun register(input: EditorInput): SourceId {
        val source = when (input) {
            is EditorInput.UriSource -> RegisteredSource.Content(input.uri)
            is EditorInput.FileSource -> RegisteredSource.LocalFile(File(input.absolutePath))
            is EditorInput.Pick -> throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Pick must be resolved before registration")
        }
        val id = SourceId(UUID.randomUUID().toString())
        open(source).close()
        sources[id] = source
        return id
    }

    /** Uri form of a registered source, used for restoring the session after recreation. */
    public fun describe(id: SourceId): EditorInput = when (val source = requireSource(id)) {
        is RegisteredSource.Content -> EditorInput.UriSource(source.uri)
        is RegisteredSource.LocalFile -> EditorInput.FileSource(source.file.absolutePath)
    }

    override fun openInputStream(id: SourceId): InputStream = open(requireSource(id))

    override fun reportedMimeType(id: SourceId): String? = when (val source = requireSource(id)) {
        is RegisteredSource.Content -> runCatching { contentResolver.getType(source.uri) }.getOrNull()
        is RegisteredSource.LocalFile -> null
    }

    private fun requireSource(id: SourceId): RegisteredSource =
        sources[id] ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Unknown source")

    private fun open(source: RegisteredSource): InputStream = try {
        when (source) {
            is RegisteredSource.Content -> contentResolver.openInputStream(source.uri)
                ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Provider returned no stream")
            is RegisteredSource.LocalFile -> FileInputStream(source.file)
        }
    } catch (error: SecurityException) {
        throw FrameKitException(EditorErrorCode.PERMISSION_DENIED, "No read access", error)
    } catch (error: FileNotFoundException) {
        throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source not found", error)
    } catch (error: IOException) {
        throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE, "Source could not be opened", error)
    } catch (error: IllegalArgumentException) {
        // 지원하지 않는 scheme이나 잘못된 Uri는 ContentResolver가 IllegalArgumentException으로 알린다.
        throw FrameKitException(EditorErrorCode.INVALID_SOURCE, "Unsupported source reference", error)
    }

    private sealed interface RegisteredSource {
        data class Content(val uri: Uri) : RegisteredSource
        data class LocalFile(val file: File) : RegisteredSource
    }
}
