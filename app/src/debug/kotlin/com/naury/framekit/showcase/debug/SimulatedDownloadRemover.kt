package com.naury.framekit.showcase.debug

import android.content.Context
import android.graphics.Bitmap
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.edit
import com.naury.framekit.image.cutout.BackgroundRemover
import com.naury.framekit.image.ml.OnDeviceModelState
import com.naury.framekit.segmentation.MlKitBackgroundRemover
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * debug 빌드 전용. 실제 ML Kit 배경 제거기를 감싸, 모델이 이미 있어도 "모델 없음 → 내려받기 → 완료" 흐름을 시험할 수 있게 한다.
 *
 * [ModelDebugReceiver]의 `simulate`로 켜고 `unsimulate`로 끈다. 켜져 있으면 [modelState]가 내려받기가 필요하다고 답하고, [downloadModel]은
 * 정해진 시간(기본 10초, `simulate`에 `--el ms <밀리초>`로 바꿈) 동안 진행률을 흉내 낸 뒤 실제 확인을 거쳐 끝난다. 시작 시각을 저장해 두므로 받는 중 앱을 끝냈다가 다시 열면 이어서 진행된다.
 * 배경 제거 자체는 실제 모델로 한다.
 */
class SimulatedDownloadRemover(context: Context) : BackgroundRemover {

    private val appContext = context.applicationContext
    private val real = MlKitBackgroundRemover(appContext)
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override val isAvailable: Boolean get() = real.isAvailable

    override suspend fun modelState(): OnDeviceModelState {
        if (!simulating) return real.modelState()
        return if (startedAt > 0) OnDeviceModelState.Downloading(downloadedBytes(), TOTAL_BYTES) else OnDeviceModelState.NeedsDownload
    }

    override fun downloadModel(): Flow<OnDeviceModelState> {
        if (!simulating) return real.downloadModel()
        return flow {
            if (!hasNetwork()) {
                emit(OnDeviceModelState.DownloadFailed(OnDeviceModelState.Failure.NO_NETWORK))
                return@flow
            }
            if (startedAt == 0L) prefs.edit { putLong(KEY_STARTED, System.currentTimeMillis()) }
            while (elapsedMs() < durationMs) {
                emit(OnDeviceModelState.Downloading(downloadedBytes(), TOTAL_BYTES))
                delay(TICK_MS)
            }
            emit(OnDeviceModelState.Downloading(TOTAL_BYTES, TOTAL_BYTES, installing = true))
            delay(INSTALL_MS)
            prefs.edit {
                remove(KEY_SIMULATE)
                remove(KEY_STARTED)
            }
            // 흉내가 끝나면 실제 Play 서비스 확인으로 마무리한다(모델이 있으므로 바로 준비됨).
            emitAll(real.downloadModel())
        }
    }

    override suspend fun subjectMask(image: Bitmap): Bitmap = real.subjectMask(image)

    private val simulating: Boolean get() = prefs.getBoolean(KEY_SIMULATE, false)

    private val startedAt: Long get() = prefs.getLong(KEY_STARTED, 0L)

    private fun elapsedMs(): Long = (System.currentTimeMillis() - startedAt).coerceAtLeast(0)

    private val durationMs: Long get() = prefs.getLong(KEY_DURATION, DEFAULT_SIMULATED_MS)

    private fun downloadedBytes(): Long = (TOTAL_BYTES * (elapsedMs().toDouble() / durationMs)).toLong().coerceIn(0, TOTAL_BYTES)

    private fun hasNetwork(): Boolean = try {
        val manager = appContext.getSystemService(ConnectivityManager::class.java)
        manager?.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    } catch (_: SecurityException) {
        true
    }

    companion object {
        const val PREFS = "framekit_debug_model"
        const val KEY_SIMULATE = "simulate_missing"
        const val KEY_STARTED = "simulated_started_at"
        private const val TOTAL_BYTES = 9_400_000L
        const val KEY_DURATION = "simulated_ms"
        private const val DEFAULT_SIMULATED_MS = 10_000L
        private const val INSTALL_MS = 1_200L
        private const val TICK_MS = 200L
    }
}
