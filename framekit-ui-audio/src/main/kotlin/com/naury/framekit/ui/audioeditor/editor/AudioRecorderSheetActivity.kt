package com.naury.framekit.ui.audioeditor.editor

/**
 * 녹음만 하는 요청을 호스트 화면 위 시트로 띄운다([com.naury.framekit.ui.audioeditor.contract.RecorderPresentation.SHEET]).
 * 투명한 창이라 뒤 화면이 보이며, 결과·권한·다시 시도·프로세스 재시작 처리는 [AudioEditorActivity]와 같다.
 */
internal class AudioRecorderSheetActivity : AudioEditorActivity() {
    override val presentsSheet: Boolean get() = true
}
