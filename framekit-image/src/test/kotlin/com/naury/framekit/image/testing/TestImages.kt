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

/** Builds labeled test images: each quadrant of the upright picture has its own color. */
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

    /** Writes a JPEG whose stored pixels need [orientation] to look like [upright]. */
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
 * Resolver over local files keyed by their [SourceId] value.
 *
 * @param exposeLocation `false` hides the file location so decoders must use streams.
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

/** Asserts that a pixel is close to [expected] on every channel; JPEG is lossy. */
internal fun Bitmap.colorNear(x: Int, y: Int, expected: Int, tolerance: Int = 40): Boolean {
    val actual = getPixel(x, y)
    return Math.abs(Color.red(actual) - Color.red(expected)) <= tolerance &&
        Math.abs(Color.green(actual) - Color.green(expected)) <= tolerance &&
        Math.abs(Color.blue(actual) - Color.blue(expected)) <= tolerance &&
        Math.abs(Color.alpha(actual) - Color.alpha(expected)) <= tolerance
}
