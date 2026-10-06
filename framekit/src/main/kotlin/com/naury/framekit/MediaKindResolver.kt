package com.naury.framekit

import android.content.ContentResolver
import android.webkit.MimeTypeMap
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import java.io.File
import java.util.Locale

/** Decides whether a source is a photo or a video from its MIME type, falling back to the extension. */
internal object MediaKindResolver {

    /** @return [MediaKind.IMAGE], [MediaKind.VIDEO], or `null` when neither fits. */
    fun resolve(input: EditorInput, resolver: ContentResolver): MediaKind? = when (input) {
        is EditorInput.Pick -> input.kind.takeIf { it != MediaKind.ANY }
        is EditorInput.UriSource -> fromMime(runCatching { resolver.getType(input.uri) }.getOrNull())
            ?: fromMime(mimeOfExtension(input.uri.lastPathSegment))
        is EditorInput.FileSource -> fromMime(mimeOfExtension(File(input.absolutePath).name))
    }

    fun fromMime(mime: String?): MediaKind? = when {
        mime == null -> null
        mime.startsWith("image/") -> MediaKind.IMAGE
        mime.startsWith("video/") -> MediaKind.VIDEO
        else -> null
    }

    private fun mimeOfExtension(name: String?): String? {
        val extension = name?.substringAfterLast('.', "")?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return null
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
    }

}
