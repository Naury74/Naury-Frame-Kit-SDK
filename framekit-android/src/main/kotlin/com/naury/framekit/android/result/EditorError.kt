package com.naury.framekit.android.result

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.UUID

/** Stable error codes. Hosts localize these; the SDK never returns a translated message. */
public enum class EditorErrorCode(
    public val defaultRecoverable: Boolean,
    public val defaultAction: SuggestedAction,
) {
    /** MIME type, media kind or metadata does not match what the editor can open. */
    INVALID_SOURCE(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** The source could not be opened because no read grant was available. */
    PERMISSION_DENIED(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** The source was deleted, moved or its provider is offline. */
    SOURCE_UNAVAILABLE(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** The container, codec or image format is not supported. */
    UNSUPPORTED_FORMAT(true, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** The device cannot perform the requested edit or output. */
    UNSUPPORTED_OPERATION(true, SuggestedAction.CHANGE_OUTPUT_SETTINGS),

    /** The host passed a config outside its documented range. */
    INVALID_CONFIGURATION(false, SuggestedAction.FIX_CONFIGURATION),

    /** The project contains values outside their documented range. */
    INVALID_PROJECT(false, SuggestedAction.FIX_CONFIGURATION),

    /** The source is damaged or could not be decoded. */
    DECODE_FAILED(false, SuggestedAction.CHOOSE_ANOTHER_SOURCE),

    /** The encoder failed. A lower resolution may succeed. */
    ENCODE_FAILED(true, SuggestedAction.LOWER_RESOLUTION),

    /** The image does not fit the memory budget of this device. */
    INSUFFICIENT_MEMORY(true, SuggestedAction.LOWER_RESOLUTION),

    /** There is not enough free space to write the output. */
    INSUFFICIENT_STORAGE(true, SuggestedAction.FREE_STORAGE),

    /** Writing or publishing the output file failed. */
    OUTPUT_WRITE_FAILED(true, SuggestedAction.RETRY),

    /** The editor returned without a readable result, for example after process death. */
    RESULT_UNAVAILABLE(true, SuggestedAction.RELAUNCH),

    /** An engine failure that does not fit any other code. */
    UNKNOWN(true, SuggestedAction.RETRY),
}

/** What the user can do next. */
public enum class SuggestedAction {
    CHOOSE_ANOTHER_SOURCE,
    CHANGE_OUTPUT_SETTINGS,
    FIX_CONFIGURATION,
    LOWER_RESOLUTION,
    FREE_STORAGE,
    RETRY,
    RELAUNCH,
}

/**
 * Error delivered to the host.
 *
 * The underlying exception is not included. Its type is written to the device log together with
 * [diagnosticId] so that a report from a user can be matched to the log without exposing file names
 * or Uris in the result.
 */
@Parcelize
public data class EditorError(
    val code: EditorErrorCode,
    val recoverable: Boolean = code.defaultRecoverable,
    val suggestedAction: SuggestedAction = code.defaultAction,
    val diagnosticId: String = newDiagnosticId(),
) : Parcelable {
    public companion object {
        internal fun newDiagnosticId(): String = UUID.randomUUID().toString().substring(0, 8)
    }
}
