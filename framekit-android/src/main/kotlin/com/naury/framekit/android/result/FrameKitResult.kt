package com.naury.framekit.android.result

/**
 * 편집기 실행의 최종 결과. 실행 한 번당 정확히 한 번 전달된다.
 *
 * 진행 중인 export를 취소하면 편집기로 돌아갈 뿐 [Cancelled]를 만들지 않는다.
 * [Cancelled]는 편집기를 닫거나 picker를 닫았을 때만 전달된다.
 */
public sealed interface FrameKitResult {
    /**
     * @property output 첫 번째 결과. 결과가 하나뿐인 대부분의 경우 이것만 쓰면 된다.
     * @property outputs 모든 결과를 편집 순서대로. 여러 장 사진을 각각 저장하면 여러 개이고, 하나의 PDF로
     *   묶거나 단일 편집이면 [output] 하나다.
     */
    public data class Success(val output: EditedMedia, val outputs: List<EditedMedia> = listOf(output)) : FrameKitResult {
        init {
            require(outputs.isNotEmpty() && outputs.first() == output) { "outputs must start with output" }
        }
    }
    public data object Cancelled : FrameKitResult
    public data class Failure(val error: EditorError) : FrameKitResult
}
