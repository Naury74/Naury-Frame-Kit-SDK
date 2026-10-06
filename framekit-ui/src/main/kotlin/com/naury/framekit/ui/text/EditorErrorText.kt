package com.naury.framekit.ui.text

import androidx.annotation.StringRes
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.ui.R

/** Localized message that tells the user what happened and what to do next. */
@StringRes
public fun EditorErrorCode.messageRes(): Int = when (this) {
    EditorErrorCode.INVALID_SOURCE -> R.string.framekit_error_invalid_source
    EditorErrorCode.PERMISSION_DENIED -> R.string.framekit_error_permission_denied
    EditorErrorCode.SOURCE_UNAVAILABLE -> R.string.framekit_error_source_unavailable
    EditorErrorCode.SOURCE_TOO_LARGE -> R.string.framekit_error_source_too_large
    EditorErrorCode.UNSUPPORTED_FORMAT -> R.string.framekit_error_unsupported_format
    EditorErrorCode.UNSUPPORTED_OPERATION -> R.string.framekit_error_unsupported_operation
    EditorErrorCode.INVALID_CONFIGURATION -> R.string.framekit_error_invalid_configuration
    EditorErrorCode.INVALID_PROJECT -> R.string.framekit_error_invalid_project
    EditorErrorCode.DECODE_FAILED -> R.string.framekit_error_decode_failed
    EditorErrorCode.ENCODE_FAILED -> R.string.framekit_error_encode_failed
    EditorErrorCode.INSUFFICIENT_MEMORY -> R.string.framekit_error_insufficient_memory
    EditorErrorCode.INSUFFICIENT_STORAGE -> R.string.framekit_error_insufficient_storage
    EditorErrorCode.OUTPUT_WRITE_FAILED -> R.string.framekit_error_output_write_failed
    EditorErrorCode.RESULT_UNAVAILABLE -> R.string.framekit_error_result_unavailable
    EditorErrorCode.UNKNOWN -> R.string.framekit_error_unknown
}
