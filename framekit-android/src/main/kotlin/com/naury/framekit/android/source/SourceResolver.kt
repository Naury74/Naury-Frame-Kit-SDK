package com.naury.framekit.android.source

import android.net.Uri
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.model.SourceId
import java.io.File
import java.io.InputStream

/** Where a source lives, for decoders that read directly from a file or Uri instead of a stream. */
public sealed interface SourceLocation {
    public data class Content(val uri: Uri) : SourceLocation
    public data class LocalFile(val file: File) : SourceLocation
}

/**
 * Opens the bytes behind a [SourceId].
 *
 * Every call returns a new stream positioned at the start. Callers close it; implementations must not
 * assume a stream can be reset and reused for metadata and decoding.
 */
public interface SourceResolver {

    /**
     * @throws FrameKitException with `PERMISSION_DENIED`, `SOURCE_UNAVAILABLE` or `INVALID_SOURCE`
     *   when the source cannot be opened.
     */
    public fun openInputStream(id: SourceId): InputStream

    /** MIME type reported by the provider, or `null` when it is unknown. */
    public fun reportedMimeType(id: SourceId): String?

    /**
     * Direct location of the source, or `null` when only streams are available. Decoders use it to
     * avoid loading a large file into memory.
     */
    public fun location(id: SourceId): SourceLocation? = null
}
