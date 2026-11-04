package com.naury.framekit.ui.audioeditor.contract

import android.content.Context
import android.content.Intent
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.ui.audioeditor.editor.AudioEditorActivity
import com.naury.framekit.ui.audioeditor.editor.AudioRecorderSheetActivity

/**
 * 음성 녹음·편집기를 열고 정확히 하나의 [FrameKitResult]를 돌려준다. 결과는 AAC(M4A, `MediaType.AUDIO`)다.
 *
 * ```
 * val launcher = registerForActivityResult(AudioEditorContract()) { result -> ... }
 * launcher.launch(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO)))              // 녹음하고 편집
 * launcher.launch(AudioEditorRequest(EditorInput.Pick(MediaKind.AUDIO)))                 // 파일을 골라 편집
 * launcher.launch(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO),
 *     AudioEditorConfig(recordMode = RecordMode.HOLD, editAfterRecording = false)))      // 누르고 말하기 녹음기
 * launcher.launch(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO),
 *     AudioEditorConfig(editAfterRecording = false, recorderPresentation = RecorderPresentation.SHEET))) // 호스트 화면 위 녹음 시트
 * ```
 */
public class AudioEditorContract : ActivityResultContract<AudioEditorRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: AudioEditorRequest): Intent =
        Intent(context, if (input.usesRecorderSheet) AudioRecorderSheetActivity::class.java else AudioEditorActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.extra.AUDIO_REQUEST"
    }
}
