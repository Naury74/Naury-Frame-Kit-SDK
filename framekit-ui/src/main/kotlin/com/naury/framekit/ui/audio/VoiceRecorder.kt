package com.naury.framekit.ui.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * 마이크로 음성을 AAC(m4a) 파일에 녹음한다. 한 번에 한 녹음만 한다.
 *
 * 녹음 파일은 앱 파일 폴더(`files/framekit/recordings`)에 둔다. 편집 세션이 프로세스 종료 뒤에도 원본으로 다시 열 수 있게 캐시가 아닌 곳에 둔다.
 */
public class VoiceRecorder(private val context: Context) {

    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var startedAt = 0L

    /** 녹음 중이면 `true`. */
    public val isRecording: Boolean get() = recorder != null

    /** 녹음을 시작한 뒤 지난 시간(밀리초). 녹음 중이 아니면 `0`. */
    public val elapsedMs: Long get() = if (recorder != null) System.currentTimeMillis() - startedAt else 0

    /** 지금 소리 크기(`0..1`). 녹음 중이 아니면 `0`. 녹음 화면의 막대 표시에 쓴다. */
    public fun level(): Float = runCatching { (recorder?.maxAmplitude ?: 0) / 32_767f }.getOrDefault(0f).coerceIn(0f, 1f)

    /**
     * 녹음을 시작한다.
     *
     * @throws IOException 마이크를 쓸 수 없을 때(다른 앱이 쓰는 중, 권한 없음 등).
     */
    public fun start() {
        check(recorder == null) { "Already recording" }
        val directory = directory(context).apply { mkdirs() }
        val target = File(directory, "voice_${UUID.randomUUID()}.m4a")
        val next = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        try {
            next.setAudioSource(MediaRecorder.AudioSource.MIC)
            next.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            next.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            next.setAudioEncodingBitRate(BIT_RATE)
            next.setAudioSamplingRate(SAMPLE_RATE)
            next.setOutputFile(target.absolutePath)
            next.prepare()
            next.start()
        } catch (error: Exception) {
            next.release()
            target.delete()
            throw IOException("Microphone is not available", error)
        }
        recorder = next
        file = target
        startedAt = System.currentTimeMillis()
    }

    /**
     * 녹음을 마친다.
     *
     * @return 녹음 파일. 너무 짧아(0.5초 미만) 쓸 수 없으면 `null`이며 파일은 지운다.
     */
    public fun stop(): File? {
        val current = recorder ?: return null
        val target = file
        val long = elapsedMs >= MIN_MS
        recorder = null
        file = null
        val ok = runCatching { current.stop() }.isSuccess
        current.release()
        if (!ok || !long || target == null || !target.exists() || target.length() == 0L) {
            target?.delete()
            return null
        }
        return target
    }

    /** 녹음을 버린다. */
    public fun cancel() {
        val current = recorder ?: return
        recorder = null
        runCatching { current.stop() }
        current.release()
        file?.delete()
        file = null
    }

    public companion object {
        private const val BIT_RATE = 128_000
        private const val SAMPLE_RATE = 44_100
        private const val MIN_MS = 500L

        /** 녹음 파일을 두는 폴더. */
        public fun directory(context: Context): File = File(context.filesDir, "framekit/recordings")

        /**
         * 호스트 앱이 manifest에 `RECORD_AUDIO`를 선언했으면 `true`. SDK는 권한을 직접 추가하지 않으므로 선언하지 않은 앱에서는 녹음 버튼을
         * 숨기고 파일로만 음성을 넣게 한다.
         */
        public fun isDeclared(context: Context): Boolean = try {
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            }
            info.requestedPermissions?.contains(Manifest.permission.RECORD_AUDIO) == true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }

        /** 마이크가 있는 기기면 `true`. */
        public fun hasMicrophone(context: Context): Boolean = context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
    }
}
