package com.naury.framekit.image.ocr

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import com.naury.framekit.android.result.FrameKitException

/**
 * 인식한 글자 한 줄. 좌표는 인식에 넘긴 이미지의 픽셀 단위이며 왼쪽 위가 원점이다.
 *
 * @property text 줄의 글자.
 * @property left 줄을 감싸는 상자의 왼쪽(px).
 * @property top 상자의 위쪽(px).
 * @property right 상자의 오른쪽(px).
 * @property bottom 상자의 아래쪽(px).
 */
public data class RecognizedLine(val text: String, val left: Float, val top: Float, val right: Float, val bottom: Float)

/**
 * 사진 속 글자를 인식한다. PDF로 저장할 때 보이지 않는 글자 레이어를 만들어 검색·복사가 되게 하는 데 쓴다.
 *
 * FrameKit 자체는 모델을 포함하지 않는다. 선택 artifact인 `framekit-ocr`을 추가하면 manifest meta-data로 구현이
 * 등록되며, 없으면 [TextRecognizers.find]가 `null`을 반환하고 PDF에는 이미지만 들어간다.
 */
public interface TextRecognizer {

    /**
     * @param image 인식할 이미지. 호출이 끝날 때까지 recycle하지 않는다.
     * @return 위에서 아래 순서의 줄 목록. 글자가 없으면 빈 목록.
     * @throws FrameKitException 모델을 아직 쓸 수 없으면(예: 다운로드 중) `UNSUPPORTED_OPERATION`.
     */
    public suspend fun recognize(image: Bitmap): List<RecognizedLine>
}

/** 선택 모듈이 등록한 [TextRecognizer]를 찾는다. */
public object TextRecognizers {

    /** 값이 구현 클래스 이름인 application meta-data 키. */
    public const val META_DATA_KEY: String = "com.naury.framekit.TEXT_RECOGNIZER"

    /** 등록된 구현을 `(Context)` 생성자로 만든다. 모듈이 없거나 만들 수 없으면 `null`. */
    public fun find(context: Context): TextRecognizer? {
        val appContext = context.applicationContext
        val className = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                appContext.packageManager.getApplicationInfo(appContext.packageName, PackageManager.ApplicationInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            } else {
                @Suppress("DEPRECATION")
                appContext.packageManager.getApplicationInfo(appContext.packageName, PackageManager.GET_META_DATA)
            }
            info.metaData?.getString(META_DATA_KEY)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } ?: return null
        return runCatching {
            Class.forName(className).getConstructor(Context::class.java).newInstance(appContext) as TextRecognizer
        }.getOrNull()
    }
}
