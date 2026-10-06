package com.naury.framekit.android.result

/**
 * 편집기 실행의 최종 결과. 실행 한 번당 정확히 한 번 전달된다.
 *
 * 진행 중인 export를 취소하면 편집기로 돌아갈 뿐 [Cancelled]를 만들지 않는다.
 * [Cancelled]는 편집기를 닫거나 picker를 닫았을 때만 전달된다.
 */
public sealed interface FrameKitResult {
    public data class Success(val output: EditedMedia) : FrameKitResult
    public data object Cancelled : FrameKitResult
    public data class Failure(val error: EditorError) : FrameKitResult
}
