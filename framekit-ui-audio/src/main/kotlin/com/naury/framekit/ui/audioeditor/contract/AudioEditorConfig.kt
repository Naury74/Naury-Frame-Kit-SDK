package com.naury.framekit.ui.audioeditor.contract

import android.os.Parcelable
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import kotlinx.parcelize.Parcelize

/** 녹음 방식. */
public enum class RecordMode {
    /** 눌러서 시작하고 다시 눌러 마친다. 잠시 멈춤·이어 녹음이 된다. */
    TAP,

    /** 누르고 있는 동안만 녹음한다. 위로 밀면 손을 떼도 계속 녹음하고(고정), 왼쪽으로 밀면 취소한다. */
    HOLD,

    /** 두 방식을 모두 보여 주고 사용자가 고른다. */
    BOTH,
}

/** 음성 편집기의 도구. */
public enum class AudioTool {
    /** 재생 위치에 녹음을 끼워 넣거나 겹쳐 넣기. */
    RECORD,

    /** 기기의 소리 파일을 더하기. */
    IMPORT,

    /** 재생 위치에서 조각 나누기. */
    SPLIT,

    /** 구간을 골라 잘라 내거나 그 구간만 남기기. */
    RANGE,

    /** 조각 볼륨과 페이드인·아웃. */
    VOLUME,
}

/**
 * 음성 편집기 설정.
 *
 * @property recordMode 녹음 방식.
 * @property maxRecordingMs 한 번 녹음할 수 있는 최대 길이(밀리초, 1초..60분). 닿으면 저절로 멈춘다.
 * @property editAfterRecording `Capture`로 녹음을 시작했을 때 녹음이 끝나면 편집 화면으로 넘어갈지. `false`면 녹음 파일을 바로 돌려준다(녹음기로만 쓰기).
 * @property enabledTools 보여 줄 도구.
 * @property maxSourceCount `Pick`·`Multiple`로 한 번에 열 수 있는 최대 소리 파일 수(1..20).
 * @property allowUndo 실행 취소 버튼을 보여 준다.
 * @property allowRedo 다시 실행 버튼을 보여 준다.
 */
@Parcelize
public data class AudioEditorConfig(
    val recordMode: RecordMode = RecordMode.BOTH,
    val maxRecordingMs: Long = 10 * 60_000L,
    val editAfterRecording: Boolean = true,
    val enabledTools: Set<AudioTool> = AudioTool.entries.toSet(),
    val maxSourceCount: Int = 10,
    val allowUndo: Boolean = true,
    val allowRedo: Boolean = true,
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (maxRecordingMs !in 1_000L..3_600_000L) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "config.maxRecordingMs", "Expected 1000..3600000")
        if (maxSourceCount !in 1..MAX_SOURCES) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "config.maxSourceCount", "Expected 1..$MAX_SOURCES")
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_SOURCES: Int = 20
    }
}

/**
 * 음성 저장 설정. 결과는 AAC(M4A)다.
 *
 * @property bitrate AAC 비트레이트(bps, 32 000..320 000).
 */
@Parcelize
public data class AudioExportConfig(val bitrate: Int = 128_000) : Parcelable {
    public fun validate(): ValidationResult =
        if (bitrate in 32_000..320_000) ValidationResult.Valid else ValidationResult.of(listOf(ValidationIssue(ValidationCode.OUT_OF_RANGE, "export.bitrate", "Expected 32000..320000")))
}
