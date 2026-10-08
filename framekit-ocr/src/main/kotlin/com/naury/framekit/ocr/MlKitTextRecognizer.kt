package com.naury.framekit.ocr

import android.content.Context
import android.graphics.Bitmap
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.image.ocr.RecognizedLine
import com.naury.framekit.image.ocr.TextRecognizer
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 기기에서 동작하는 ML Kit 글자 인식 기반 [TextRecognizer]다. 한국어 모델은 영어·숫자도 함께 인식한다.
 *
 * 모델은 Google Play 서비스가 내려준다. 다운로드가 끝나기 전에는 `UNSUPPORTED_OPERATION`으로 실패하며,
 * 첫 사용 전에 모델을 받아 두려면 호스트 manifest에 `com.google.mlkit.vision.DEPENDENCIES` meta-data를 넣는다
 * (integration 문서 참고). 사진을 서버로 보내지 않는다.
 * Play 서비스가 없는 기기에서는 이 모듈을 사용할 수 없다.
 */
public class MlKitTextRecognizer(@Suppress("UNUSED_PARAMETER") context: Context) : TextRecognizer {

    override suspend fun recognize(image: Bitmap): List<RecognizedLine> {
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        try {
            val text = client.process(InputImage.fromBitmap(image, 0)).await()
            // 블록 안의 줄 단위로 넘긴다. 줄이 PDF에서 선택·검색하기에 가장 자연스러운 단위다.
            return text.textBlocks.flatMap { block -> block.lines }.mapNotNull { line ->
                val box = line.boundingBox ?: return@mapNotNull null
                RecognizedLine(line.text, box.left.toFloat(), box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat())
            }.sortedWith(compareBy({ it.top }, { it.left }))
        } finally {
            client.close()
        }
    }

    private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { continuation.resume(it) }
        addOnFailureListener { error ->
            val code = if (error is MlKitException && error.errorCode == MlKitException.UNAVAILABLE) {
                EditorErrorCode.UNSUPPORTED_OPERATION
            } else {
                EditorErrorCode.UNKNOWN
            }
            continuation.resumeWithException(FrameKitException(code, "Text recognition failed", error))
        }
        addOnCanceledListener { continuation.cancel() }
    }
}
