package com.naury.framekit.ocr

import android.os.Bundle
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.naury.framekit.image.isolation.IsolatedWorkerService
import com.naury.framekit.image.isolation.PixelFiles
import java.io.File
import java.util.concurrent.ExecutionException

/**
 * ML Kit 글자 인식을 앱과 다른 프로세스(`:framekit_ocr`)에서 돌리는 서비스.
 *
 * 모델의 네이티브 코드가 일부 기기·에뮬레이터에서 프로세스를 강제 종료시키므로(SIGILL) 앱 프로세스와 떼어 둔다.
 * 문서 판별처럼 사진을 열 때 자동으로 도는 인식도 있어, 앱 프로세스에서 돌리면 사진을 여는 것만으로 앱이 종료될 수 있다.
 */
internal class TextRecognitionService : IsolatedWorkerService() {

    override fun onCreate() {
        super.onCreate()
        // ML Kit 초기화 provider는 앱의 기본 프로세스에서만 돈다. 이 프로세스에서는 직접 초기화한다.
        runCatching { MlKit.initialize(this) }
    }

    override fun handle(request: Bundle, progress: (Bundle) -> Unit): Bundle {
        val path = request.getString(TextRecognitionContract.KEY_INPUT) ?: return result(TextRecognitionContract.RESULT_FAILED)
        val width = request.getInt(TextRecognitionContract.KEY_WIDTH)
        val height = request.getInt(TextRecognitionContract.KEY_HEIGHT)
        if (width <= 0 || height <= 0) return result(TextRecognitionContract.RESULT_FAILED)
        val client = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try {
            val image = PixelFiles.readPixels(File(path), width, height)
            val text = Tasks.await(client.process(InputImage.fromBitmap(image, 0)))
            image.recycle()
            // 블록 안의 줄 단위로 넘긴다. 줄이 PDF에서 선택·검색하기에 가장 자연스러운 단위다.
            val lines = text.textBlocks.flatMap { block -> block.lines }.filter { it.boundingBox != null }
            val boxes = FloatArray(lines.size * 4)
            lines.forEachIndexed { index, line ->
                val box = checkNotNull(line.boundingBox)
                boxes[index * 4] = box.left.toFloat()
                boxes[index * 4 + 1] = box.top.toFloat()
                boxes[index * 4 + 2] = box.right.toFloat()
                boxes[index * 4 + 3] = box.bottom.toFloat()
            }
            result(TextRecognitionContract.RESULT_OK).apply {
                putStringArray(TextRecognitionContract.KEY_TEXTS, lines.map { it.text }.toTypedArray())
                putFloatArray(TextRecognitionContract.KEY_BOXES, boxes)
            }
        } catch (error: ExecutionException) {
            val cause = error.cause
            result(if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) TextRecognitionContract.RESULT_UNAVAILABLE else TextRecognitionContract.RESULT_FAILED)
        } catch (_: OutOfMemoryError) {
            result(TextRecognitionContract.RESULT_FAILED)
        } finally {
            client.close()
        }
    }

    private fun result(code: Int) = Bundle().apply { putInt(TextRecognitionContract.KEY_RESULT, code) }
}

/** 앱 프로세스와 [TextRecognitionService] 사이에 주고받는 키와 값. */
internal object TextRecognitionContract {
    const val KEY_INPUT = "input"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"
    const val KEY_TEXTS = "texts"

    /** 줄마다 left, top, right, bottom(px) 네 값. */
    const val KEY_BOXES = "boxes"

    const val KEY_RESULT = "result"
    const val RESULT_OK = 0
    const val RESULT_UNAVAILABLE = 1
    const val RESULT_FAILED = 2
}
