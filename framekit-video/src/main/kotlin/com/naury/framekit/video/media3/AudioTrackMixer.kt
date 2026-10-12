// Media3의 composition·Transformer API는 @UnstableApi다. 이 모듈 안에서만 쓰고 공개 API에는 Media3 타입을 드러내지 않는다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.naury.framekit.android.result.EditorErrorCode
import com.naury.framekit.android.result.FrameKitException
import com.naury.framekit.core.video.AudioClip
import com.naury.framekit.core.video.AudioPlacement
import com.naury.framekit.core.video.AudioSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 오디오 트랙 하나. [clip]의 배치(시작 위치·원본 구간·볼륨·반복)를 영상 시간에 맞춰 놓는다.
 *
 * @property uri 읽을 수 있는 `content://`나 `file://` Uri.
 * @property sourceDurationUs 원본 소리 길이(µs). 모르면 `null`.
 */
public data class AudioMixTrack(val uri: Uri, val clip: AudioClip, val sourceDurationUs: Long?)

/**
 * 소리 없는 MP4 영상에 오디오 트랙(배경 음악·음성)을 섞어 새 MP4로 쓴다. 영상은 다시 인코딩하지 않는다.
 *
 * 움직이는 이미지처럼 FrameKit이 직접 만든 영상에 소리를 붙일 때 쓴다. 결과 길이는 영상 길이와 같고, 소리가 더 길면 자른다.
 */
public object AudioTrackMixer {

    /**
     * @param video 소리 없는 MP4.
     * @param videoDurationUs 영상 길이(µs).
     * @param output 결과 MP4. 이미 있으면 덮어쓴다.
     * @param onProgress `0..1` 진행률. 메인 스레드에서 불린다.
     * @throws FrameKitException 섞지 못하면 `ENCODE_FAILED`.
     */
    public suspend fun mix(
        context: Context,
        video: File,
        videoDurationUs: Long,
        tracks: List<AudioMixTrack>,
        output: File,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.Main) {
        val sequences = tracks.mapNotNull { sequence(it, videoDurationUs) }
        val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(video))).build()
        // 영상에는 소리가 없으므로 무음 트랙을 만들어 섞을 자리를 마련한다.
        val videoSequence = EditedMediaItemSequence.Builder(listOf(videoItem)).experimentalSetForceAudioTrack(true).build()
        val composition = Composition.Builder(listOf(videoSequence) + sequences).build()
        run(context.applicationContext, composition, output, onProgress)
    }

    /**
     * 영상 없이 소리 트랙만 섞어 AAC(M4A) 파일로 쓴다. 음성 편집기의 저장에 쓴다.
     *
     * @param durationUs 결과 길이(µs). 이보다 긴 소리는 자른다.
     * @param bitrate AAC 비트레이트(bps).
     * @throws FrameKitException 쓰지 못하면 `ENCODE_FAILED`.
     */
    public suspend fun mixAudioOnly(
        context: Context,
        durationUs: Long,
        tracks: List<AudioMixTrack>,
        output: File,
        bitrate: Int = DEFAULT_BITRATE,
        onProgress: (Float) -> Unit = {},
    ): Unit = withContext(Dispatchers.Main) {
        val sequences = tracks.mapNotNull { sequence(it, durationUs) }
        if (sequences.isEmpty()) throw FrameKitException(EditorErrorCode.INVALID_PROJECT, "Nothing to export")
        val composition = Composition.Builder(sequences).build()
        run(context.applicationContext, composition, output, onProgress, bitrate)
    }

    private fun sequence(track: AudioMixTrack, videoDurationUs: Long): EditedMediaItemSequence? {
        val segments = AudioPlacement.segments(track.clip, videoDurationUs)
        if (segments.none { it is AudioSegment.Play }) return null
        val builder = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        val volume = track.clip.volume.toFloat()
        val clipDuration = track.clip.outputRange(videoDurationUs).durationUs
        val fades = track.clip.fadeInUs > 0 || track.clip.fadeOutUs > 0
        var played = 0L
        segments.forEach { segment ->
            when (segment) {
                is AudioSegment.Silence -> builder.addGap(segment.durationUs)
                is AudioSegment.Play -> {
                    val segmentStart = played
                    played += segment.sourceRange.durationUs
                    val item = MediaItem.Builder()
                        .setUri(track.uri)
                        .setClippingConfiguration(
                            MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionUs(segment.sourceRange.startUs)
                                .setEndPositionUs(segment.sourceRange.endExclusiveUs)
                                .build(),
                        )
                        .build()
                    val audio = when {
                        fades -> listOf<AudioProcessor>(FadeAudioProcessor(segmentStart, clipDuration, track.clip.fadeInUs, track.clip.fadeOutUs, volume))
                        volume != 1f -> listOf<AudioProcessor>(AudioEffects.volume(volume))
                        else -> emptyList()
                    }
                    builder.addItem(
                        EditedMediaItem.Builder(item)
                            .setRemoveVideo(true)
                            .apply { track.sourceDurationUs?.let(::setDurationUs) }
                            .setEffects(Effects(audio, emptyList()))
                            .build(),
                    )
                }
            }
        }
        return builder.build()
    }

    private suspend fun run(context: Context, composition: Composition, output: File, onProgress: (Float) -> Unit, bitrate: Int? = null) = coroutineScope {
        val holder = arrayOfNulls<Transformer>(1)
        val poller = launch {
            val progress = ProgressHolder()
            while (isActive) {
                val transformer = holder[0]
                if (transformer != null && transformer.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress / 100f)
                delay(POLL_MS)
            }
        }
        try {
            suspendCancellableCoroutine<Unit> { continuation ->
                val transformer = Transformer.Builder(context)
                    .setAudioMimeType(MimeTypes.AUDIO_AAC)
                    .apply {
                        if (bitrate != null) {
                            setEncoderFactory(
                                DefaultEncoderFactory.Builder(context)
                                    .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(bitrate).build())
                                    .build(),
                            )
                        }
                    }
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            if (continuation.isActive) continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            if (continuation.isActive) continuation.resumeWithException(FrameKitException(EditorErrorCode.ENCODE_FAILED, "Audio mixing failed", exportException))
                        }
                    })
                    .build()
                holder[0] = transformer
                transformer.start(composition, output.absolutePath)
            }
        } catch (cancelled: CancellationException) {
            withContext(NonCancellable) { holder[0]?.cancel() }
            throw cancelled
        } finally {
            poller.cancel()
        }
    }

    private const val POLL_MS = 200L

    /** 음성 저장 기본 비트레이트(bps). */
    public const val DEFAULT_BITRATE: Int = 128_000
}
