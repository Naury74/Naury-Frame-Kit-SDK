package com.naury.framekit.segmentation

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.core.content.edit
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.image.cutout.BackgroundRemover
import com.naury.framekit.image.isolation.IsolatedResult
import com.naury.framekit.image.isolation.IsolatedServiceClient
import com.naury.framekit.image.isolation.PixelFiles
import com.naury.framekit.image.isolation.ProcessCrashGuard
import com.naury.framekit.image.ml.OnDeviceModelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * 기기에서 동작하는 ML Kit subject segmentation 기반 [BackgroundRemover]다. 사진을 서버로 보내지 않는다.
 *
 * 모델은 Google Play 서비스가 내려준다. 아직 없으면 [modelState]가 [OnDeviceModelState.NeedsDownload]를 돌려주고, 에디터는
 * 사용자에게 데이터 사용을 알린 뒤 [downloadModel]로 받는다. 받는 중에 앱이 끝나도 Play 서비스는 계속 받으며, 다음에
 * [modelState]를 부르면 [OnDeviceModelState.Downloading]으로 알려 진행 상황을 다시 따라갈 수 있다.
 * Play 서비스가 없는 기기에서는 이 모듈을 사용할 수 없다.
 *
 * 모델은 앱과 다른 프로세스(`:framekit_segmentation`)에서 돈다. 모델의 네이티브 코드가 기기 CPU에서 프로세스를 강제
 * 종료시키면(일부 에뮬레이터·기기의 SIGILL) 앱은 그대로 두고 `UNSUPPORTED_OPERATION`으로 실패하며, 그 뒤로는
 * [isAvailable]이 `false`가 되어 에디터가 배경 제거 도구를 숨긴다. 앱이나 OS가 업데이트되면 다시 시도한다.
 * 분리 프로세스도 호스트 `Application.onCreate`를 거치므로, 호스트는 그곳에서 무거운 초기화를 기본 프로세스로 한정하는 것이 좋다.
 */
public class MlKitBackgroundRemover(context: Context) : BackgroundRemover {

    private val appContext = context.applicationContext
    private val client = IsolatedServiceClient(appContext, SegmentationService::class.java)
    private val guard = ProcessCrashGuard(appContext, CRASH_FEATURE)
    private val prefs by lazy { appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    override val isAvailable: Boolean get() = !guard.isBlocked

    override suspend fun modelState(): OnDeviceModelState {
        if (!isAvailable) return OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.DEVICE)
        return when (val result = client.call(op(SegmentationContract.OP_CHECK))) {
            is IsolatedResult.Success -> when (result.code) {
                SegmentationContract.RESULT_OK -> {
                    downloadRequested = false
                    OnDeviceModelState.Ready
                }
                // 사용자가 이미 받기로 했는데 끝나지 않았으면(앱이 중간에 끝난 경우 등) 이어받는 중으로 알린다.
                SegmentationContract.RESULT_NEEDS_DOWNLOAD -> if (downloadRequested) OnDeviceModelState.Downloading() else OnDeviceModelState.NeedsDownload
                SegmentationContract.RESULT_NO_PLAY_SERVICES -> OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.NO_PLAY_SERVICES)
                else -> OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.OTHER)
            }
            IsolatedResult.Crashed -> crashed()
            else -> OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.OTHER)
        }
    }

    override fun downloadModel(): Flow<OnDeviceModelState> = channelFlow {
        if (!isAvailable) {
            send(OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.DEVICE))
            return@channelFlow
        }
        if (!hasNetwork()) {
            send(OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.NO_NETWORK))
            return@channelFlow
        }
        // 받는 중에 앱이 끝나도 다음에 이어서 따라갈 수 있게 먼저 기록한다.
        downloadRequested = true
        send(OnDeviceModelState.Downloading())
        val result = client.call(op(SegmentationContract.OP_INSTALL)) { progress ->
            trySend(
                OnDeviceModelState.Downloading(
                    bytesDownloaded = progress.getLong(SegmentationContract.KEY_BYTES),
                    totalBytes = progress.getLong(SegmentationContract.KEY_TOTAL),
                    installing = progress.getBoolean(SegmentationContract.KEY_INSTALLING),
                ),
            )
        }
        val final = when (result) {
            is IsolatedResult.Success -> when (result.code) {
                SegmentationContract.RESULT_OK -> OnDeviceModelState.Ready
                SegmentationContract.RESULT_NO_PLAY_SERVICES -> OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.NO_PLAY_SERVICES)
                else -> OnDeviceModelState.DownloadFailed(if (hasNetwork()) OnDeviceModelState.Failure.OTHER else OnDeviceModelState.Failure.NO_NETWORK)
            }
            IsolatedResult.Crashed -> crashed()
            else -> OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.OTHER)
        }
        downloadRequested = false
        send(final)
    }

    override suspend fun subjectMask(image: Bitmap): Bitmap {
        if (!isAvailable) throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Subject segmentation is not supported on this device")
        val width = image.width
        val height = image.height
        val directory = File(appContext.cacheDir, WORK_DIRECTORY)
        val name = UUID.randomUUID().toString()
        val input = File(directory, "$name.in")
        val output = File(directory, "$name.out")
        try {
            withContext(Dispatchers.IO) {
                directory.mkdirs()
                PixelFiles.writePixels(image, input)
            }
            val request = op(SegmentationContract.OP_SEGMENT).apply {
                putString(SegmentationContract.KEY_INPUT, input.absolutePath)
                putString(SegmentationContract.KEY_OUTPUT, output.absolutePath)
                putInt(SegmentationContract.KEY_WIDTH, width)
                putInt(SegmentationContract.KEY_HEIGHT, height)
            }
            when (val result = client.call(request)) {
                is IsolatedResult.Success -> when (result.code) {
                    SegmentationContract.RESULT_OK -> Unit
                    SegmentationContract.RESULT_NEEDS_DOWNLOAD, SegmentationContract.RESULT_NO_PLAY_SERVICES ->
                        throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Segmentation model is not available yet")
                    else -> throw FrameKitException(EditorErrorCode.UNKNOWN, "Subject segmentation failed")
                }
                IsolatedResult.Crashed -> {
                    guard.block()
                    throw FrameKitException(EditorErrorCode.UNSUPPORTED_OPERATION, "Segmentation process stopped; disabled on this device")
                }
                else -> throw FrameKitException(EditorErrorCode.UNKNOWN, "Subject segmentation failed")
            }
            return withContext(Dispatchers.IO) { PixelFiles.readAlphaMask(output, width, height) }
        } finally {
            // 취소돼도 임시 파일은 지운다.
            withContext(NonCancellable + Dispatchers.IO) {
                input.delete()
                output.delete()
            }
        }
    }

    private fun crashed(): OnDeviceModelState {
        guard.block()
        downloadRequested = false
        return OnDeviceModelState.Unavailable(OnDeviceModelState.Unsupported.DEVICE)
    }

    private var downloadRequested: Boolean
        get() = prefs.getBoolean(KEY_DOWNLOAD_REQUESTED, false)
        set(value) = prefs.edit { putBoolean(KEY_DOWNLOAD_REQUESTED, value) }

    // 권한이 없어 확인할 수 없으면 연결된 것으로 보고 Play 서비스에 맡긴다.
    private fun hasNetwork(): Boolean = try {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        manager?.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ?: (manager == null)
    } catch (_: SecurityException) {
        true
    }

    private val IsolatedResult.Success.code: Int get() = data.getInt(SegmentationContract.KEY_RESULT, SegmentationContract.RESULT_FAILED)

    private fun op(name: String) = Bundle().apply { putString(SegmentationContract.KEY_OP, name) }

    private companion object {
        const val WORK_DIRECTORY = "framekit_segmentation"
        const val CRASH_FEATURE = "segmentation"
        const val PREFS = "framekit_segmentation"
        const val KEY_DOWNLOAD_REQUESTED = "download_requested"
    }
}
