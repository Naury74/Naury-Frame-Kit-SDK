package com.naury.framekit.ui.video.editor

import androidx.activity.ComponentActivity
import androidx.annotation.RestrictTo
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.ui.component.EmbeddedEditor
import com.naury.framekit.ui.component.EmbeddedEditorState
import com.naury.framekit.ui.layout.FoldPosture
import com.naury.framekit.ui.video.contract.VideoEditorRequest
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** 혼합 편집 화면에 영상 에디터를 넣는다. */
@RestrictTo(RestrictTo.Scope.LIBRARY_GROUP)
public object VideoEditorEmbedding {
    /** [owner]에 [key]로 묶인 에디터. 이미 있으면(화면 회전 등) 같은 편집 상태를 돌려준다. */
    public fun create(owner: ComponentActivity, request: VideoEditorRequest, key: String): EmbeddedEditor {
        val provider = ViewModelProvider(owner.viewModelStore, videoEditorFactory(request), owner.defaultViewModelCreationExtras)
        return Handle(provider[key, VideoEditorViewModel::class.java])
    }

    private class Handle(private val viewModel: VideoEditorViewModel) : EmbeddedEditor {
        override val state: StateFlow<EmbeddedEditorState> =
            viewModel.state.map { it.embedded() }.stateIn(viewModel.viewModelScope, SharingStarted.Eagerly, viewModel.state.value.embedded())
        override val result: StateFlow<FrameKitResult?> get() = viewModel.result

        override fun save() = viewModel.save()

        override fun cancelExport() = viewModel.cancelExport()

        override fun dismissExportError() = viewModel.dismissExportError()

        override fun discard() = viewModel.confirmDiscard()

        override fun onStop() = viewModel.onStop()

        @Composable
        override fun Content(posture: FoldPosture) = VideoEditorScreen(viewModel, posture)
    }
}

private fun VideoEditorUiState.embedded(): EmbeddedEditorState = when (this) {
    is VideoEditorUiState.Ready -> EmbeddedEditorState(
        ready = true,
        dirty = isDirty,
        toolOpen = activeTool != null,
        exporting = export is ExportUiState.Running,
        exportFailure = (export as? ExportUiState.Failed)?.code,
    )
    is VideoEditorUiState.LoadFailed -> EmbeddedEditorState(failed = true)
    else -> EmbeddedEditorState()
}
