package com.naury.framekit.ui.component

import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.ui.layout.FoldPosture
import kotlinx.coroutines.flow.StateFlow

/**
 * 에디터 화면이 혼합 편집 화면 안에 들어 있다. `true`면 에디터는 도구가 없을 때 상단 바를 그리지 않고, 저장 버튼과
 * 도구 밖의 시스템 뒤로 가기를 감싼 화면에 맡긴다.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public val LocalEditorEmbedded: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/**
 * 혼합 편집 화면에 들어간 에디터 하나의 상태.
 *
 * @property ready 원본을 열어 편집할 수 있다.
 * @property failed 원본을 열지 못했다.
 * @property exportFailure 마지막 저장이 실패한 이유. 실패하지 않았으면 `null`.
 */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public data class EmbeddedEditorState(
    val ready: Boolean = false,
    val failed: Boolean = false,
    val dirty: Boolean = false,
    val toolOpen: Boolean = false,
    val exporting: Boolean = false,
    val exportFailure: EditorErrorCode? = null,
)

/** 혼합 편집 화면이 다루는 에디터 하나. 저장은 [save]를 부른 뒤 [result]가 채워지는 것으로 끝난다. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public interface EmbeddedEditor {
    public val state: StateFlow<EmbeddedEditorState>
    public val result: StateFlow<FrameKitResult?>

    public fun save()

    public fun cancelExport()

    public fun dismissExportError()

    /** 편집을 버리고 세션을 끝낸다. */
    public fun discard()

    /** 화면이 가려졌다. 재생을 멈춘다. */
    public fun onStop()

    @Composable
    public fun Content(posture: FoldPosture)
}
