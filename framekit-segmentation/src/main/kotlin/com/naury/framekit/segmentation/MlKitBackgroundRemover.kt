package com.naury.framekit.segmentation

import android.content.Context
import android.graphics.Bitmap
import androidx.core.graphics.createBitmap
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentationResult
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.image.cutout.BackgroundRemover
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 기기에서 동작하는 ML Kit subject segmentation 기반 [BackgroundRemover]다.
 *
 * 모델은 Google Play 서비스가 내려준다. 모델 다운로드가 끝나기 전에는 `UNSUPPORTED_OPERATION`으로
 * 실패하며, 인스턴스를 만들 때 Play 서비스에 모델 설치를 요청한다.
 * Play 서비스가 없는 기기에서는 이 모듈을 사용할 수 없다.
 */
public class MlKitBackgroundRemover(context: Context) : BackgroundRemover {

    init {
        // 첫 사용 때 기다리지 않도록 만들 때 Play 서비스에 모델 설치를 요청한다. 매니페스트 meta-data는 다른 ML Kit
        // 모듈과 값이 겹쳐 합쳐지지 않으므로 쓰지 않는다. 실패해도 첫 사용 때 다시 내려받는다.
        runCatching {
            val client = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
            ModuleInstall.getClient(context.applicationContext)
                .installModules(ModuleInstallRequest.newBuilder().addApi(client).build())
                .addOnCompleteListener { client.close() }
        }
    }

    override suspend fun subjectMask(image: Bitmap): Bitmap {
        val segmenter = SubjectSegmentation.getClient(
            SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build(),
        )
        try {
            val result = segmenter.process(InputImage.fromBitmap(image, 0)).await()
            return withContext(Dispatchers.Default) { toMask(result, image.width, image.height) }
        } finally {
            segmenter.close()
        }
    }

    // 확신도 0.3~0.7 사이를 부드럽게 이어 가장자리가 계단처럼 보이지 않게 한다.
    private fun toMask(result: SubjectSegmentationResult, width: Int, height: Int): Bitmap {
        val confidence = result.foregroundConfidenceMask
            ?: throw FrameKitException(EditorErrorCode.UNKNOWN, "Segmentation returned no mask")
        confidence.rewind()
        val pixels = IntArray(width * height)
        for (i in pixels.indices) {
            val c = if (confidence.hasRemaining()) confidence.get() else 0f
            val t = ((c - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
            val alpha = (t * t * (3f - 2f * t) * 255f).toInt()
            pixels[i] = (alpha shl 24) or 0x00FFFFFF
        }
        return createBitmap(width, height).apply { setPixels(pixels, 0, width, 0, 0, width, height) }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { error ->
            val code = if (error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE) {
                EditorErrorCode.UNSUPPORTED_OPERATION
            } else {
                EditorErrorCode.UNKNOWN
            }
            continuation.resumeWithException(FrameKitException(code, "Subject segmentation failed", error))
        }
        addOnCanceledListener { continuation.cancel() }
    }

    private companion object {
        const val EDGE_LOW = 0.3f
        const val EDGE_HIGH = 0.7f
    }
}
