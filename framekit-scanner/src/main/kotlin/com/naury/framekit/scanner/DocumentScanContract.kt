package com.naury.framekit.scanner

import android.content.Context
import android.content.Intent
import android.os.Parcelable
import androidx.activity.result.contract.ActivityResultContract
import com.naury.framekit.android.result.FrameKitResult
import com.naury.framekit.android.result.FrameKitResultCodec
import com.naury.framekit.core.document.ScanMode
import com.naury.framekit.core.validation.ValidationCode
import com.naury.framekit.core.validation.ValidationIssue
import com.naury.framekit.core.validation.ValidationResult
import com.naury.framekit.ui.config.EditorUiConfig
import kotlinx.parcelize.Parcelize

/**
 * 카메라 문서 스캔 요청. [DocumentScanContract]로 연다.
 *
 * @property maxPages 한 번에 찍을 수 있는 최대 쪽 수, `1..50`.
 * @property autoCapture 문서 테두리가 잠시 흔들리지 않으면 자동으로 찍는다. 사용자가 화면에서 끌 수 있다.
 * @property scanMode 찍은 쪽에 적용할 스캔 보정.
 * @property ui 화면의 색·언어.
 */
@Parcelize
public data class DocumentScanRequest(
    val maxPages: Int = 20,
    val autoCapture: Boolean = true,
    val scanMode: ScanMode = ScanMode.COLOR,
    val ui: EditorUiConfig = EditorUiConfig(),
) : Parcelable {

    public fun validate(): ValidationResult {
        val issues = mutableListOf<ValidationIssue>()
        if (maxPages !in 1..MAX_PAGES) issues += ValidationIssue(ValidationCode.OUT_OF_RANGE, "scan.maxPages", "Expected 1..$MAX_PAGES")
        (ui.validate() as? ValidationResult.Invalid)?.let { issues += it.issues }
        return ValidationResult.of(issues)
    }

    public companion object {
        public const val MAX_PAGES: Int = 50
    }
}

/**
 * 카메라로 문서를 찍는다. 화면에 문서 테두리를 실시간으로 표시하고, 테두리가 잠시 멈추면 자동으로 찍는다(수동 촬영도 된다).
 * 찍은 쪽은 반듯하게 펴고 [DocumentScanRequest.scanMode]를 적용해 JPEG로 돌려준다.
 *
 * 결과는 [FrameKitResult]다. 성공하면 [FrameKitResult.Success.outputs]에 찍은 순서대로 쪽이 담기며(`mediaType = IMAGE`),
 * 파일은 다른 결과와 같이 앱 내부 저장소에 있고 필요 없으면 `FrameKitOutputs.deleteOutput`으로 지운다.
 *
 * 앱 안 카메라는 호스트가 `CAMERA` 권한을 선언한 경우에만 쓴다. 선언하지 않았거나 사용자가 거부하면 기기 카메라 앱으로 한 장을
 * 찍어 같은 방식으로 펴서 돌려준다.
 */
public class DocumentScanContract : ActivityResultContract<DocumentScanRequest, FrameKitResult>() {

    override fun createIntent(context: Context, input: DocumentScanRequest): Intent =
        Intent(context, DocumentScannerActivity::class.java).putExtra(EXTRA_REQUEST, input)

    override fun parseResult(resultCode: Int, intent: Intent?): FrameKitResult = FrameKitResultCodec.decode(resultCode, intent)

    internal companion object {
        const val EXTRA_REQUEST = "com.naury.framekit.scanner.REQUEST"
    }
}
