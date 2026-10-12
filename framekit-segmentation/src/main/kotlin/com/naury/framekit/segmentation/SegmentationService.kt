package com.naury.framekit.segmentation

import android.os.Bundle
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.MlKitException
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.segmentation.subject.SubjectSegmentation
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenter
import com.google.mlkit.vision.segmentation.subject.SubjectSegmenterOptions
import com.naury.framekit.image.isolation.IsolatedWorkerService
import com.naury.framekit.image.isolation.PixelFiles
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * ML Kit 피사체 분리를 앱과 다른 프로세스(`:framekit_segmentation`)에서 돌리는 서비스.
 *
 * 일부 기기·에뮬레이터에서는 Play 서비스가 내려준 모델의 네이티브 코드가 CPU가 지원하지 않는 명령으로 프로세스를
 * 강제 종료시킨다(SIGILL). 이런 종료는 try/catch로 잡을 수 없으므로 따로 떼어 돌리고, 죽으면 앱은 살아남아 기능을 끈다.
 * 모델 확인·내려받기도 같은 프로세스에서 해, 앱 프로세스에는 모델 코드가 한 번도 올라오지 않게 한다.
 */
internal class SegmentationService : IsolatedWorkerService() {

    override fun onCreate() {
        super.onCreate()
        // ML Kit 초기화 provider는 앱의 기본 프로세스에서만 돈다. 이 프로세스에서는 직접 초기화한다.
        runCatching { MlKit.initialize(this) }
    }

    override fun handle(request: Bundle, progress: (Bundle) -> Unit): Bundle = when (request.getString(SegmentationContract.KEY_OP)) {
        SegmentationContract.OP_CHECK -> withSegmenter { check(it) }
        SegmentationContract.OP_INSTALL -> withSegmenter { install(it, progress) }
        SegmentationContract.OP_SEGMENT -> withSegmenter {
            segment(it, request.getString(SegmentationContract.KEY_INPUT), request.getString(SegmentationContract.KEY_OUTPUT), request.getInt(SegmentationContract.KEY_WIDTH), request.getInt(SegmentationContract.KEY_HEIGHT))
        }
        else -> result(SegmentationContract.RESULT_FAILED)
    }

    private inline fun withSegmenter(block: (SubjectSegmenter) -> Bundle): Bundle {
        if (GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) != ConnectionResult.SUCCESS) return result(SegmentationContract.RESULT_NO_PLAY_SERVICES)
        val segmenter = SubjectSegmentation.getClient(SubjectSegmenterOptions.Builder().enableForegroundConfidenceMask().build())
        return try {
            block(segmenter)
        } finally {
            segmenter.close()
        }
    }

    private fun check(segmenter: SubjectSegmenter): Bundle {
        val available = Tasks.await(ModuleInstall.getClient(this).areModulesAvailable(segmenter), GMS_TIMEOUT_SECONDS, TimeUnit.SECONDS).areModulesAvailable()
        return result(if (available) SegmentationContract.RESULT_OK else SegmentationContract.RESULT_NEEDS_DOWNLOAD)
    }

    // Play 서비스에 모델 설치를 요청하고 끝날 때까지 진행 상황을 보낸다. 이 프로세스가 중간에 끝나도 Play 서비스는 계속 받는다.
    private fun install(segmenter: SubjectSegmenter, progress: (Bundle) -> Unit): Bundle {
        val client = ModuleInstall.getClient(this)
        val done = CountDownLatch(1)
        var outcome = SegmentationContract.RESULT_FAILED
        val listener = InstallStatusListener { update ->
            when (update.installState) {
                InstallState.STATE_PENDING, InstallState.STATE_DOWNLOADING, InstallState.STATE_DOWNLOAD_PAUSED, InstallState.STATE_INSTALLING -> {
                    val info = update.progressInfo
                    progress(
                        Bundle().apply {
                            putLong(SegmentationContract.KEY_BYTES, info?.bytesDownloaded ?: 0L)
                            putLong(SegmentationContract.KEY_TOTAL, info?.totalBytesToDownload ?: 0L)
                            putBoolean(SegmentationContract.KEY_INSTALLING, update.installState == InstallState.STATE_INSTALLING)
                        },
                    )
                }
                InstallState.STATE_COMPLETED -> {
                    outcome = SegmentationContract.RESULT_OK
                    done.countDown()
                }
                InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> done.countDown()
            }
        }
        val callbacks = Executors.newSingleThreadExecutor()
        try {
            val request = ModuleInstallRequest.newBuilder().addApi(segmenter).setListener(listener, callbacks).build()
            if (Tasks.await(client.installModules(request), GMS_TIMEOUT_SECONDS, TimeUnit.SECONDS).areModulesAlreadyInstalled()) return result(SegmentationContract.RESULT_OK)
            if (!done.await(INSTALL_TIMEOUT_MINUTES, TimeUnit.MINUTES)) return result(SegmentationContract.RESULT_FAILED)
            return result(outcome)
        } finally {
            runCatching { client.unregisterListener(listener) }
            callbacks.shutdown()
        }
    }

    private fun segment(segmenter: SubjectSegmenter, inputPath: String?, outputPath: String?, width: Int, height: Int): Bundle {
        if (inputPath == null || outputPath == null || width <= 0 || height <= 0) return result(SegmentationContract.RESULT_FAILED)
        return try {
            val image = PixelFiles.readPixels(File(inputPath), width, height)
            val segmented = Tasks.await(segmenter.process(InputImage.fromBitmap(image, 0)))
            image.recycle()
            val confidence = segmented.foregroundConfidenceMask ?: return result(SegmentationContract.RESULT_FAILED)
            confidence.rewind()
            RandomAccessFile(File(outputPath), "rw").use { raf ->
                val out = raf.channel.map(FileChannel.MapMode.READ_WRITE, 0, width.toLong() * height)
                repeat(width * height) {
                    val c = if (confidence.hasRemaining()) confidence.get() else 0f
                    // 확신도 0.3~0.7 사이를 부드럽게 이어 가장자리가 계단처럼 보이지 않게 한다.
                    val t = ((c - EDGE_LOW) / (EDGE_HIGH - EDGE_LOW)).coerceIn(0f, 1f)
                    out.put((t * t * (3f - 2f * t) * 255f).toInt().toByte())
                }
            }
            result(SegmentationContract.RESULT_OK)
        } catch (error: ExecutionException) {
            val cause = error.cause
            result(if (cause is MlKitException && cause.errorCode == MlKitException.UNAVAILABLE) SegmentationContract.RESULT_NEEDS_DOWNLOAD else SegmentationContract.RESULT_FAILED)
        } catch (_: OutOfMemoryError) {
            result(SegmentationContract.RESULT_FAILED)
        }
    }

    private fun result(code: Int) = Bundle().apply { putInt(SegmentationContract.KEY_RESULT, code) }

    private companion object {
        const val EDGE_LOW = 0.3f
        const val EDGE_HIGH = 0.7f
        const val GMS_TIMEOUT_SECONDS = 30L
        const val INSTALL_TIMEOUT_MINUTES = 15L
    }
}

/** 앱 프로세스와 [SegmentationService] 사이에 주고받는 키와 값. */
internal object SegmentationContract {
    const val KEY_OP = "op"
    const val OP_CHECK = "check"
    const val OP_INSTALL = "install"
    const val OP_SEGMENT = "segment"

    const val KEY_INPUT = "input"
    const val KEY_OUTPUT = "output"
    const val KEY_WIDTH = "width"
    const val KEY_HEIGHT = "height"

    const val KEY_BYTES = "bytes"
    const val KEY_TOTAL = "total"
    const val KEY_INSTALLING = "installing"

    const val KEY_RESULT = "result"
    const val RESULT_OK = 0
    const val RESULT_NEEDS_DOWNLOAD = 1
    const val RESULT_NO_PLAY_SERVICES = 2
    const val RESULT_FAILED = 3
}
