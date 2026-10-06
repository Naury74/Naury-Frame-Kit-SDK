package com.naury.framekit.image.testing

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.exifinterface.media.ExifInterface
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.source.SourceLocation
import com.naury.framekit.android.source.SourceResolver
import com.naury.framekit.core.geometry.ExifOrientation
import com.naury.framekit.core.model.SourceId
import com.naury.framekit.image.render.toAndroidMatrix
import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/** 표식이 있는 테스트 이미지를 만든다. 정방향 그림의 사분면마다 고유한 색을 가진다. */
internal object TestImages {
    const val TOP_LEFT = Color.RED
    const val TOP_RIGHT = Color.GREEN
    const val BOTTOM_LEFT = Color.BLUE
    const val BOTTOM_RIGHT = Color.WHITE

    fun quadrants(width: Int, height: Int, hasAlpha: Boolean = false): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint()
        fun fill(color: Int, left: Int, top: Int, right: Int, bottom: Int) {
            paint.color = color
            canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
        }
        fill(TOP_LEFT, 0, 0, width / 2, height / 2)
        fill(TOP_RIGHT, width / 2, 0, width, height / 2)
        fill(BOTTOM_LEFT, 0, height / 2, width / 2, height)
        fill(if (hasAlpha) Color.TRANSPARENT else BOTTOM_RIGHT, width / 2, height / 2, width, height)
        return bitmap
    }

    /** 저장된 픽셀에 [orientation]을 적용해야 [upright]처럼 보이는 JPEG를 쓴다. */
    fun writeOrientedJpeg(upright: Bitmap, orientation: ExifOrientation, file: File) {
        val toEncoded = orientation.encodedToUpright(
            if (orientation.swapsDimensions) upright.height.toDouble() else upright.width.toDouble(),
            if (orientation.swapsDimensions) upright.width.toDouble() else upright.height.toDouble(),
        ).inverted()
        val encodedWidth = if (orientation.swapsDimensions) upright.height else upright.width
        val encodedHeight = if (orientation.swapsDimensions) upright.width else upright.height
        val encoded = Bitmap.createBitmap(encodedWidth, encodedHeight, Bitmap.Config.ARGB_8888)
        Canvas(encoded).drawBitmap(upright, toEncoded.toAndroidMatrix(), null)
        file.outputStream().use { encoded.compress(Bitmap.CompressFormat.JPEG, 100, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.exifValue.toString())
            saveAttributes()
        }
    }
}

/**
 * [SourceId] 값을 키로 로컬 파일을 찾는 resolver다.
 *
 * @param exposeLocation `false`면 파일 위치를 숨겨 decoder가 스트림을 쓰도록 강제한다.
 */
internal class FileSourceResolver(
    private val files: Map<String, File>,
    private val exposeLocation: Boolean = true,
) : SourceResolver {
    constructor(vararg entries: Pair<String, File>) : this(entries.toMap())

    override fun location(id: SourceId): SourceLocation? =
        if (exposeLocation) files[id.value]?.let(SourceLocation::LocalFile) else null

    override fun openInputStream(id: SourceId): InputStream =
        files[id.value]?.let(::FileInputStream) ?: throw FrameKitException(EditorErrorCode.SOURCE_UNAVAILABLE)

    override fun reportedMimeType(id: SourceId): String? = null
}

/** 픽셀이 모든 채널에서 [expected]에 가까운지 확인한다. JPEG는 손실 압축이기 때문이다. */
internal fun Bitmap.colorNear(x: Int, y: Int, expected: Int, tolerance: Int = 40): Boolean {
    val actual = getPixel(x, y)
    return Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
        Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
        Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance &&
        Math.abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance
}
