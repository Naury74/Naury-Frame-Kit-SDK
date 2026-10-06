package com.naury.framekit.android.source

import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.model.SourceId
import java.io.InputStream

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
}
