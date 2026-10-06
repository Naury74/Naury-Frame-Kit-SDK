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
 * 한 편집 세션에 등록된 원본 목록.
 *
 * 레지스트리는 호스트가 제공한 권한을 가져가거나 해제하지 않는다. 어떤 참조가 어떤 [SourceId]에
 * 해당하는지만 기억한다.
 */
public class SessionSourceRegistry(context: Context) : SourceResolver {

    private val contentResolver: ContentResolver = context.applicationContext.contentResolver
    private val sources = ConcurrentHashMap<SourceId, RegisteredSource>()

    /**
     * 원본을 등록하고 열 수 있는지 확인한다.
     *
     * @throws FrameKitException 원본을 읽을 수 없거나 [input]이 picker·촬영·목록 요청일 때.
     */
    public fun register(input: EditorInput): SourceId {
        val source = when (input) {
            is EditorInput.UriSource -> RegisteredSource.Content(input.uri)
            is EditorInput.FileSource -> RegisteredSource.LocalFile(File(input.absolutePath))
            is EditorInput.Pick, is EditorInput.Capture, is EditorInput.Multiple ->
                throw FrameKitException(EditorErrorCode.INVALID_CONFIGURATION, "Pick, capture and lists must be resolved before registration")
        }
        val id = SourceId(UUID.randomUUID().toString())
        open(source).close()
        sources[id] = source
        return id
    }

    /** 등록된 원본의 Uri 형태. 재생성 후 세션을 복원할 때 사용한다. */
    public fun describe(id: SourceId): EditorInput = when (val source = requireSource(id)) {
        is RegisteredSource.Content -> EditorInput.UriSource(source.uri)
        is RegisteredSource.LocalFile -> EditorInput.FileSource(source.file.absolutePath)
    }

    override fun openInputStream(id: SourceId): InputStream = open(requireSource(id))

    override fun reportedMimeType(id: SourceId): String? = when (val source = requireSource(id)) {
        is RegisteredSource.Content -> runCatching { contentResolver.getType(source.uri) }.getOrNull()
        is RegisteredSource.LocalFile -> null
    }

    override fun location(id: SourceId): SourceLocation = when (val source = requireSource(id)) {
        is RegisteredSource.Content -> SourceLocation.Content(source.uri)
        is RegisteredSource.LocalFile -> SourceLocation.LocalFile(source.file)
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
