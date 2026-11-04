package com.naury.framekit.ui.audioeditor.contract

import android.app.Application
import android.os.Parcel
import androidx.core.content.IntentCompat
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.naury.framekit.android.input.EditorInput
import com.naury.framekit.android.input.MediaKind
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.audioeditor.editor.AudioEditorActivity
import com.naury.framekit.ui.audioeditor.editor.AudioRecorderSheetActivity
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AudioEditorContractTest {

    private val context = ApplicationProvider.getApplicationContext<Application>()
    private val sheet = AudioEditorConfig(editAfterRecording = false, recorderPresentation = RecorderPresentation.SHEET)

    private fun activityFor(request: AudioEditorRequest): String? = AudioEditorContract().createIntent(context, request).component?.className

    @Test
    fun `record-only capture with the sheet option opens the sheet activity`() {
        assertThat(activityFor(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO), sheet))).isEqualTo(AudioRecorderSheetActivity::class.java.name)
    }

    @Test
    fun `sheet option is ignored outside record-only capture`() {
        val editing = sheet.copy(editAfterRecording = true)
        assertThat(activityFor(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO), editing))).isEqualTo(AudioEditorActivity::class.java.name)
        assertThat(activityFor(AudioEditorRequest(EditorInput.Pick(MediaKind.AUDIO), sheet))).isEqualTo(AudioEditorActivity::class.java.name)
        assertThat(activityFor(AudioEditorRequest(EditorInput.FileSource("/data/a.m4a"), sheet))).isEqualTo(AudioEditorActivity::class.java.name)
        assertThat(activityFor(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO)))).isEqualTo(AudioEditorActivity::class.java.name)
        // 무시할 뿐 검증 오류는 아니다.
        assertThat(AudioEditorRequest(EditorInput.Pick(MediaKind.AUDIO), sheet).validate()).isEqualTo(ValidationResult.Valid)
        assertThat(AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO), editing).validate()).isEqualTo(ValidationResult.Valid)
    }

    @Test
    fun `presentation defaults to full screen and survives parceling`() {
        assertThat(AudioEditorConfig().recorderPresentation).isEqualTo(RecorderPresentation.FULL_SCREEN)
        val request = AudioEditorRequest(EditorInput.Capture(MediaKind.AUDIO), sheet)
        val intent = AudioEditorContract().createIntent(context, request)
        val parcel = Parcel.obtain()
        try {
            intent.writeToParcel(parcel, 0)
            parcel.setDataPosition(0)
            val restored = android.content.Intent.CREATOR.createFromParcel(parcel)
            restored.setExtrasClassLoader(AudioEditorRequest::class.java.classLoader)
            val read = IntentCompat.getParcelableExtra(restored, AudioEditorContract.EXTRA_REQUEST, AudioEditorRequest::class.java)
            assertThat(read?.config?.recorderPresentation).isEqualTo(RecorderPresentation.SHEET)
            assertThat(read).isEqualTo(request)
        } finally {
            parcel.recycle()
        }
    }
}
