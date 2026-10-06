package com.naury.framekit.android.input

import android.net.Uri
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Media kinds that the built-in picker can offer. */
public enum class MediaKind {
    IMAGE,
    VIDEO,
    ANY,
}

/**
 * Where the editor gets its source.
 *
 * Only references travel through the Activity request. Bitmaps, drawables and callbacks are never
 * accepted here because they cannot survive process recreation and may exceed the Binder limit.
 */
public sealed interface EditorInput : Parcelable {

    /**
     * A `content://` or `file://` Uri the host can already read.
     *
     * For a Uri from another app, the host must pass a read grant along with the launch, for
     * example by keeping the grant from its own picker result. The editor checks access before it
     * opens and reports `PERMISSION_DENIED` instead of assuming that a scheme implies permission.
     */
    @Parcelize
    public data class UriSource(val uri: Uri) : EditorInput

    /**
     * A file inside the host app's private storage, given as an absolute path.
     *
     * The editor runs in the host process, so it reads the file directly without exposing it
     * through a provider.
     */
    @Parcelize
    public data class FileSource(val absolutePath: String) : EditorInput

    /** Opens the system Photo Picker first. Dismissing the picker returns `Cancelled`. */
    @Parcelize
    public data class Pick(val kind: MediaKind = MediaKind.IMAGE) : EditorInput
}
