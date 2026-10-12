package com.naury.framekit.ocr

import android.content.Context
import android.graphics.Bitmap
import android.os.Bundle
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.image.isolation.IsolatedResult
import com.naury.framekit.image.isolation.IsolatedServiceClient
import com.naury.framekit.image.isolation.PixelFiles
import com.naury.framekit.image.isolation.ProcessCrashGuard
import com.naury.framekit.image.ocr.RecognizedLine
import com.naury.framekit.image.ocr.TextRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 기기에서 동작하는 ML Kit 글자 인식 기반 [TextRecognizer]다. 한국어 모델은 영어·숫자도 함께 인식한다.
 *
 * 모델은 Google Play 서비스가 내려준다. 다운로드가 끝나기 전에는 `UNSUPPORTED_OPERATION`으로 실패하며,
 * 첫 사용 전에 모델을 받아 두려면 호스트 manifest에 `com.google.mlkit.vision.DEPENDENCIES` meta-data를 넣는다
 * (integration 문서 참고). 사진을 서버로 보내지 않는다.
 * Play 서비스가 없는 기기에서는 이 모듈을 사용할 수 없다.
 *
 * 인식은 앱과 다른 프로세스(`:framekit_ocr`)에서 돈다. 모델이 이 기기에서 프로세스를 강제 종료시키면 앱은 그대로 두고
 * `UNSUPPORTED_OPERATION`으로 실패하며, 그 뒤로는 [isAvailable]이 `false`가 되어 인식을 건너뛴다(PDF는 이미지만 담고
 * `TEXT_RECOGNITION_SKIPPED` 경고). 앱이나 OS가 업데이트되면 다시 시도한다.
 */
public class MlKitTextRecognizer(context: Context) : TextRecognizer {

    private val appContext = context.applicationContext
    private val client = IsolatedServiceClient(appContext, TextRecognitionService::class.java)
    private val guard = ProcessCrashGuard(appContext, CRASH_FEATURE)

    override val isAvailable: Boolean get() = !guard.isBlocked

    override suspend fun recognize(image: Bitmap): List<RecognizedLine> {
        if (!isAvailable) throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Text recognition is not supported on this device")
        val directory = File(appContext.cacheDir, WORK_DIRECTORY)
        val input = File(directory, "${UUID.randomUUID()}.in")
        try {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                PixelFiles.writePixels(image, input)
            }
            val request = Bundle().apply {
                putString(TextRecognitionContract.KEY_INPUT, input.absolutePath)
                putInt(TextRecognitionContract.KEY_WIDTH, image.width)
                putInt(TextRecognitionContract.KEY_HEIGHT, image.height)
            }
            val data = when (val result = client.call(request)) {
                is IsolatedResult.Success -> result.data
                IsolatedResult.Crashed -> {
                    guard.block()
                    throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Text recognition process stopped; disabled on this device")
                }
                else -> throw FrameKitException(EditorErrorCode.UNKNOWN, "Text recognition failed")
            }
            when (data.getInt(TextRecognitionContract.KEY_RESULT, TextRecognitionContract.RESULT_FAILED)) {
                TextRecognitionContract.RESULT_OK -> Unit
                TextRecognitionContract.RESULT_UNAVAILABLE -> throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Text recognition model is not available yet")
                else -> throw FrameKitException(EditorErrorCode.UNKNOWN, "Text recognition failed")
            }
            val texts = data.getStringArray(TextRecognitionContract.KEY_TEXTS).orEmpty()
            val boxes = data.getFloatArray(TextRecognitionContract.KEY_BOXES) ?: FloatArray(0)
            return texts.indices.filter { it * 4 + 3 < boxes.size }.map { index ->
                RecognizedLine(texts[index], boxes[index * 4], boxes[index * 4 + 1], boxes[index * 4 + 2], boxes[index * 4 + 3])
            }.sortedWith(compareBy({ it.top }, { it.left }))
        } finally {
            // 취소돼도 임시 파일은 지운다.
            withContext(NonCancellable + Dispatchers.IO) { input.delete() }
        }
    }

    private companion object {
        const val WORK_DIRECTORY = "framekit_ocr"
        const val CRASH_FEATURE = "ocr"
    }
}
