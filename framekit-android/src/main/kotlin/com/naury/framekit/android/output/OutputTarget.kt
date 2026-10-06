package com.naury.framekit.android.output

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Destination of an exported file. */
public sealed interface OutputTarget : Parcelable {

    /**
     * Final file in `files/framekit/exports/` of the host app, returned as a `content://` Uri from
     * the SDK's FileProvider.
     *
     * The host owns the file. The SDK never deletes a successful result on its own; call
     * [FrameKitOutputs.deleteOutput] or move the file when it is no longer needed.
     */
    @Parcelize
    public data object AppFile : OutputTarget
}
