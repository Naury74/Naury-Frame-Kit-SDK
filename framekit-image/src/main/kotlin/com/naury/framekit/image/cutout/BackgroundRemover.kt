package com.naury.framekit.image.cutout

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import androidx.core.graphics.createBitmap
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.android.session.ProjectAssetStore
import com.naury.framekit.image.ml.OnDeviceModelState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.IOException

/**
 * 배경 제거를 위해 사진의 피사체를 찾는다.
 *
 * FrameKit 자체는 모델을 포함하지 않는다. 선택 artifact인 `framekit-segmentation`을 추가하면 manifest
 * meta-data로 구현이 등록되며, 없으면 [BackgroundRemovers.find]가 `null`을 반환하고 에디터는
 * 도구를 숨긴다.
 */
public interface BackgroundRemover {

    /**
     * 이 기기에서 배경 제거를 쓸 수 있으면 `true`. 이전에 모델이 이 기기에서 동작하지 못한 것을 알게 되면 `false`가 되고,
     * 에디터는 배경 제거 도구를 숨긴다. 기본값은 `true`.
     */
    public val isAvailable: Boolean get() = true

    /**
     * 모델 준비 상태. 기본 구현은 [isAvailable]만 본다. 모델을 내려받아야 하는 구현은
     * [OnDeviceModelState.NeedsDownload]나, 이전에 시작한 내려받기가 끝나지 않았으면 [OnDeviceModelState.Downloading]을 돌려준다.
     */
    public suspend fun modelState(): OnDeviceModelState =
        if (isAvailable) OnDeviceModelState.Ready else OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.DEVICE)

    /**
     * 모델을 내려받고 진행 상황을 내보낸다. 마지막 값은 [OnDeviceModelState.Ready], [OnDeviceModelState.DownloadFailed],
     * [OnDeviceModelState.Unavailable] 중 하나다. 이미 받는 중이면 그 진행을 이어서 따라간다. 수집을 취소해도
     * 시스템(Play 서비스)의 내려받기는 계속될 수 있다. 데이터를 쓰므로 사용자에게 알린 뒤 부른다.
     */
    public fun downloadModel(): Flow<OnDeviceModelState> = flow { emit(modelState()) }

    /**
     * @param image 정방향 원본. 보통 미리보기 크기로 디코딩한 이미지다.
     * @return 같은 크기의 mask. 피사체의 alpha는 255, 배경은 0이다.
     * @throws FrameKitException 모델을 아직 쓸 수 없으면(예: 다운로드 중) `UNSUPPORTED_OPERATION`,
     *   그 밖의 실패는 다른 코드.
     */
    public suspend fun subjectMask(image: Bitmap): Bitmap
}

/** 선택 모듈이 등록한 [BackgroundRemover]를 찾는다. */
public object BackgroundRemovers {

    /** 값이 구현 클래스 이름인 application meta-data 키. */
    public const val META_DATA_KEY: String = "com.naury.framekit.BACKGROUND_REMOVER"

    /**
     * 등록된 remover를 `(Context)` 생성자로 인스턴스화한다. 모듈이 설치되지 않았거나 생성할 수 없거나,
     * 이 기기에서 쓸 수 없다고 알려진 경우([BackgroundRemover.isAvailable]가 `false`) `null`을 반환한다.
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
        }.getOrNull()?.takeIf { it.isAvailable }
    }
}

/** 피사체 mask를 프로젝트 asset으로 저장하고 렌더링을 위해 다시 불러온다. */
public object CutoutMasks {

    /**
     * [mask]를 alpha에 mask를 담은 PNG로 저장하고 asset id를 반환한다.
     * mask는 ALPHA_8 또는 ARGB_8888일 수 있다.
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

    /** 저장된 mask를 불러온다. asset이 없거나 읽을 수 없으면 `null`. */
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
