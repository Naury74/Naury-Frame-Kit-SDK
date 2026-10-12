package com.naury.framekit.ui.image.editor

import com.naury.framekit.core.overlay.ImageOverlay
import com.naury.framekit.ui.tool.PrivacySettings
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.core.effect.AdjustmentKind
import com.naury.framekit.core.overlay.BrushKind
import com.naury.framekit.core.overlay.EmojiCatalog
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.document.DocumentQuad
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.core.geometry.CropAspectRatio
import com.naury.framekit.core.history.HistoryTransaction
import com.naury.framekit.core.model.ImageProject
import com.naury.framekit.image.decode.DecodedImage
import com.naury.framekit.image.decode.ImageSourceInfo
import com.naury.framekit.ui.component.ExportStageUi
import com.naury.framekit.ui.image.contract.ImageTool

internal sealed interface ImageEditorUiState {
    /** 시스템 picker나 카메라 앱을 기다리는 중. */
    data class AwaitingSource(val mode: SourceMode) : ImageEditorUiState

    data object Loading : ImageEditorUiState

    data class LoadFailed(val code: EditorErrorCode, val canChooseAnother: Boolean) : ImageEditorUiState

    data class Ready(
        val preview: DecodedImage,
        val source: ImageSourceInfo,
        val transaction: HistoryTransaction<ImageProject>,
        val activeTool: ImageTool? = null,
        val cropAspect: CropAspectRatio = CropAspectRatio.Free,
        val export: ExportUiState? = null,
        val showDiscardDialog: Boolean = false,
        val showDraftDialog: Boolean = false,
        val showModelDownloadDialog: Boolean = false,
        /** 문서 보정 도구에서 맞추는 네 모서리. 도구가 열려 있지 않으면 `null`. */
        val documentQuad: DocumentQuad? = null,
        /** 문서를 펴는 중이면 `true`. */
        val documentBusy: Boolean = false,
        /** 지금 쪽이 문서 보정으로 만든 이미지라 원본으로 되돌릴 수 있으면 `true`. */
        val documentRectified: Boolean = false,
        /** 사진을 열 때 문서로 판별해 찾은 모서리. 문서가 아니면 `null`이고 문서 보정 도구도 숨긴다. */
        val documentSuggestion: DocumentQuad? = null,
        /** 문서 감지 제안을 사용자가 닫았으면 `true`. */
        val documentSuggestionDismissed: Boolean = false,
        /** 문서 보정에서 고른 스캔 방식. */
        val scanMode: ScanMode = ScanMode.COLOR,
        /** 문서 보정 뒤 인식한 글자. 인식 모듈이 없거나 글자가 없으면 `null`. */
        val documentText: String? = null,
        /** 인식한 글자 보기 창을 열었으면 `true`. */
        val showDocumentText: Boolean = false,
        val showApplyHint: Boolean = false,
        val showingOriginal: Boolean = false,
        val draggingCrop: Boolean = false,
        val notice: SessionNotice? = null,
        val adjustKind: AdjustmentKind = AdjustmentKind.BRIGHTNESS,
        val selectedOverlayId: String? = null,
        val editingTextId: String? = null,
        val brush: BrushSettings = BrushSettings(),
        val stickerCategory: EmojiCatalog.Category? = EmojiCatalog.Category.SMILEYS,
        val privacy: PrivacySettings = PrivacySettings(),
        val cutoutStatus: CutoutStatus? = null,
        val pageIndex: Int = 0,
        val pageCount: Int = 1,
    ) : ImageEditorUiState {
        val displayed: ImageProject get() = transaction.displayed
        val isDirty: Boolean get() = transaction.history.isDirty
        // 새로 연 텍스트를 비워 둔 채면 적용해도 빠지므로 바뀐 것으로 보지 않는다.
        val hasDraftChanges: Boolean
            get() = transaction.draft?.let { draft ->
                val blank = draft.overlays.firstOrNull { it.id == editingTextId }?.let { it is ImageOverlay.Text && it.text.isBlank() } == true
                val compared = if (blank && transaction.history.current.overlays.none { it.id == editingTextId }) draft.copy(overlays = draft.overlays.filterNot { it.id == editingTextId }) else draft
                // 문구가 빈 워터마크는 없는 것과 같다(워터마크 도구를 열기만 한 경우).
                val settled = compared.watermark?.takeIf { it.text.isBlank() }?.let { compared.copy(watermark = null) } ?: compared
                !settled.sameContentAs(transaction.history.current)
            } == true
    }
}

/** 원본을 받아 올 방법. */
internal sealed interface SourceMode {
    /** 사진 picker. [maxItems]가 2 이상이면 여러 장을 고른다. */
    data class Pick(val maxItems: Int) : SourceMode

    /** 카메라 앱으로 촬영. */
    data object Capture : SourceMode
}

/** 현재 그리기 도구 설정. UI 상태이며 획을 그리기 전까지는 프로젝트에 포함되지 않는다. */
internal data class BrushSettings(
    val kind: BrushKind = BrushKind.PEN,
    val colorArgb: Int = 0xFFFFFFFF.toInt(),
    val widthShortEdgeRatio: Double = 0.012,
    val opacity: Double = 1.0,
)

/** 배경 제거 요청의 진행 상태. */
internal sealed interface CutoutStatus {
    data object Processing : CutoutStatus
    data class Failed(val code: EditorErrorCode) : CutoutStatus

    /** 모델이 이 기기에서 동작하지 못했다. 다시 시도해도 소용없으므로 버튼을 숨기고, 도구 목록에서도 뺀다. */
    data object DeviceUnsupported : CutoutStatus

    /** 모델이 준비됐는지 확인하는 중. */
    data object ModelChecking : CutoutStatus

    /** 모델을 내려받아야 한다. 사용자가 데이터 사용 안내를 보고 받기로 해야 받는다. */
    data object ModelNeedsDownload : CutoutStatus

    /** 모델을 내려받는 중. 크기를 아직 모르면 [total]이 `0`. */
    data class ModelDownloading(val bytes: Long, val total: Long, val installing: Boolean) : CutoutStatus {
        val fraction: Float? get() = if (total > 0) (bytes.toFloat() / total).coerceIn(0f, 1f) else null
    }

    /** 내려받지 못했다. 다시 시도할 수 있다. */
    data class ModelDownloadFailed(val noNetwork: Boolean) : CutoutStatus

    /** Google Play 서비스가 없어 쓸 수 없다. */
    data object NoPlayServices : CutoutStatus
}

/** 배경 제거를 바로 실행할 수 없게 막는 상태(모델 확인·내려받기·사용 불가). */
internal val CutoutStatus?.blocksRemoval: Boolean
    get() = this is CutoutStatus.ModelChecking || this is CutoutStatus.ModelNeedsDownload || this is CutoutStatus.ModelDownloading ||
        this is CutoutStatus.ModelDownloadFailed || this is CutoutStatus.NoPlayServices || this is CutoutStatus.DeviceUnsupported

/** 디스크에서 세션을 복원한 뒤 한 번 보여주는 메시지. */
internal enum class SessionNotice {
    /** 프로세스 종료 후 커밋된 편집이 복원되었다. 실행 취소 히스토리는 비어 있는 상태로 시작한다. */
    RESTORED,

    /** 저장 중 프로세스가 종료되었다. 파일이 만들어지지 않았으므로 다시 저장해야 한다. */
    EXPORT_INTERRUPTED,

    /** 더 넣으면 최대 사진 수를 넘어 일부만 더했다. */
    PAGE_LIMIT,

    /** 문서를 반듯하게 폈다. 위치가 바뀌는 편집(자르기·텍스트·스티커·그리기·가리기·배경 제거)은 초기화했다. */
    DOCUMENT_APPLIED,

    /** 문서를 펴지 못했다(메모리·저장 공간 부족 등). 편집은 그대로다. */
    DOCUMENT_FAILED,

    /** 배경 제거 도구를 닫은 사이에 모델을 다 받았다. */
    CUTOUT_MODEL_READY,
}

internal sealed interface ExportUiState {
    /** @property progress 여러 장을 저장할 때 전체 진행률 `0..1`. */
    data class Running(val stage: ExportStageUi, val progress: Float? = null) : ExportUiState
    data class Failed(val code: EditorErrorCode) : ExportUiState
}
