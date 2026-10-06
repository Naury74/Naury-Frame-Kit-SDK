package com.naury.framekit.image.cutout

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.session.ProjectAssetStore
import java.io.IOException

/**
 * Finds the subject of a photo for background removal.
 *
 * FrameKit ships no model itself. Adding the optional `framekit-segmentation` artifact registers an
 * implementation through manifest meta-data; without it [BackgroundRemovers.find] returns `null` and
 * the editor hides the tool.
 */
public interface BackgroundRemover {

    /**
     * @param image upright source, usually the preview-sized decode.
     * @return mask of the same size whose alpha is 255 on the subject and 0 on the background.
     * @throws FrameKitException with `UNSUPPORTED_OPERATION` when the model is not available yet
     *   (for example still downloading) or another code for other failures.
     */
    public suspend fun subjectMask(image: Bitmap): Bitmap
}

/** Discovery of the [BackgroundRemover] registered by an optional module. */
public object BackgroundRemovers {

    /** Application meta-data key whose value is the implementation class name. */
    public const val META_DATA_KEY: String = "com.naury.framekit.BACKGROUND_REMOVER"

    /**
     * Instantiates the registered remover through its `(Context)` constructor, or returns `null` when
     * no module is installed or it cannot be created.
     */
    public fun find(context: Context): BackgroundRemover? {
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
            Class.forName(className).getConstructor(Context::class.java).newInstance(appContext) as BackgroundRemover
        }.getOrNull()
    }
}

/** Stores subject masks as project assets and loads them back for rendering. */
public object CutoutMasks {

    /**
     * Saves [mask] as a PNG whose alpha carries the mask and returns the asset id.
     * The mask may be ALPHA_8 or ARGB_8888.
     */
    public fun save(mask: Bitmap, assets: ProjectAssetStore): String {
        val argb = if (mask.config == Bitmap.Config.ARGB_8888) mask else toArgb(mask)
        try {
            return assets.write("png") { stream ->
                if (!argb.compress(Bitmap.CompressFormat.PNG, 100, stream)) throw IOException("Mask could not be encoded")
            }
        } finally {
            if (argb !== mask) argb.recycle()
        }
    }

    /** Loads a saved mask, or `null` when the asset is missing or unreadable. */
    public fun load(assets: ProjectAssetStore, id: String): Bitmap? {
        val file = assets.file(id) ?: return null
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    // ALPHA_8은 PNG 인코딩 결과가 기기마다 달라 흰색 RGB에 alpha만 담은 ARGB로 저장한다.
    private fun toArgb(mask: Bitmap): Bitmap {
        val result = createBitmap(mask.width, mask.height)
        val pixels = IntArray(mask.width)
        val alphaRow = IntArray(mask.width)
        for (y in 0 until mask.height) {
            mask.getPixels(alphaRow, 0, mask.width, 0, y, mask.width, 1)
            for (x in 0 until mask.width) pixels[x] = (alphaRow[x] and 0xFF000000.toInt()) or 0x00FFFFFF
            result.setPixels(pixels, 0, mask.width, 0, y, mask.width, 1)
        }
        return result
    }
}
