package com.naury.framekit.android.result

import android.app.Activity
import android.content.Intent
import androidx.core.content.IntentCompat

/**
 * 편집기 Activity 결과를 Intent로 주고받는 규칙. 모든 편집기 계약이 같은 규칙을 쓰므로 통합 계약은 하위
 * 편집기 결과를 그대로 전달할 수 있다. FrameKit 내부용 API다.
 */
public object FrameKitResultCodec {

    /** 실패 결과의 result code. */
    public const val RESULT_FAILURE: Int = Activity.RESULT_FIRST_USER + 1

    private const val EXTRA_OUTPUT = "com.naury.framekit.extra.OUTPUT"
    private const val EXTRA_OUTPUTS = "com.naury.framekit.extra.OUTPUTS"
    private const val EXTRA_ERROR = "com.naury.framekit.extra.ERROR"

    /** [result]를 `setResult`에 넘길 (result code, Intent)로 바꾼다. */
    public fun encode(result: FrameKitResult): Pair<Int, Intent?> = when (result) {
        is FrameKitResult.Success -> Activity.RESULT_OK to Intent()
            .putExtra(EXTRA_OUTPUT, result.output)
            .putParcelableArrayListExtra(EXTRA_OUTPUTS, ArrayList(result.outputs))
        is FrameKitResult.Failure -> RESULT_FAILURE to Intent().putExtra(EXTRA_ERROR, result.error)
        FrameKitResult.Cancelled -> Activity.RESULT_CANCELED to null
    }

    /**
     * Activity 결과를 [FrameKitResult]로 바꾼다. 결과가 성공인데 내용을 읽을 수 없으면(프로세스 종료 등)
     * `RESULT_UNAVAILABLE` 실패다.
     */
    public fun decode(resultCode: Int, intent: Intent?): FrameKitResult = when (resultCode) {
        Activity.RESULT_OK -> {
            val output = intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_OUTPUT, EditedMedia::class.java) }
            val outputs = intent?.let { IntentCompat.getParcelableArrayListExtra(it, EXTRA_OUTPUTS, EditedMedia::class.java) }
            when {
                output == null -> FrameKitResult.Failure(EditorError(EditorErrorCode.RESULT_UNAVAILABLE))
                outputs.isNullOrEmpty() || outputs.first() != output -> FrameKitResult.Success(output)
                else -> FrameKitResult.Success(output, outputs.toList())
            }
        }
        RESULT_FAILURE -> intent?.let { IntentCompat.getParcelableExtra(it, EXTRA_ERROR, EditorError::class.java) }
            ?.let(FrameKitResult::Failure)
            ?: FrameKitResult.Failure(EditorError(EditorErrorCode.RESULT_UNAVAILABLE))
        else -> FrameKitResult.Cancelled
    }
}
