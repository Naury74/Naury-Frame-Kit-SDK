package com.naury.framekit

import android.content.ContentResolver
import android.webkit.MimeTypeMap
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import java.io.File
import java.util.Locale

/** 소스가 사진인지 영상인지 MIME 타입으로 판별하고, 알 수 없으면 확장자로 대신 판별한다. */
internal object MediaKindResolver {

    /** @return [MediaKind.IMAGE], [MediaKind.VIDEO], 둘 다 아니면 `null`. */
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
