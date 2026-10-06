package com.naury.framekit.android.output

import android.content.Context
import android.net.Uri

/** Host-facing helpers for files produced with [OutputTarget.AppFile]. */
public object FrameKitOutputs {

    /**
     * Deletes an exported file that the host no longer needs.
     *
     * @return `true` when the file existed and was deleted, `false` when [uri] was not produced by
     *   FrameKit in this app or was already removed.
     */
    public fun deleteOutput(context: Context, uri: Uri): Boolean = AppFileOutputStore(context).delete(uri)
}
