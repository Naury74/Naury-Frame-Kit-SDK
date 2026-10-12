// Media3의 오디오 프로세서 API는 @UnstableApi다. 이 모듈 안에서만 쓴다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 오디오 조각의 한 재생 구간에 볼륨과 페이드인·아웃을 적용한다. 16비트 PCM만 처리하고 다른 형식이면 그대로 둔다.
 *
 * @param segmentStartUs 이 구간이 조각 안에서 시작하는 위치(µs). 페이드 계산의 기준이다.
 * @param clipDurationUs 조각 전체 출력 길이(µs).
 */
internal class FadeAudioProcessor(
    private val segmentStartUs: Long,
    private val clipDurationUs: Long,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val volume: Float,
) : BaseAudioProcessor() {

    private var framesSinceFlush = 0L
    private var flushOffsetUs = 0L

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) return AudioProcessor.AudioFormat.NOT_SET
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val rate = inputAudioFormat.sampleRate
        val remaining = inputBuffer.remaining()
        val output = replaceOutputBuffer(remaining).order(ByteOrder.nativeOrder())
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val frameBytes = 2 * channels
        while (input.remaining() >= frameBytes) {
            val timeUs = segmentStartUs + flushOffsetUs + framesSinceFlush * 1_000_000L / rate
            val gain = gainAt(timeUs)
            repeat(channels) {
                val sample = input.short * gain
                output.putShort(sample.toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
            }
            framesSinceFlush++
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        framesSinceFlush = 0
        // 미리보기에서 구간 중간으로 옮기면 그 위치부터 센다. 알 수 없으면 처음부터 센다.
        flushOffsetUs = streamMetadata.positionOffsetUs.takeIf { it in 0..clipDurationUs } ?: 0L
    }

    override fun onReset() {
        framesSinceFlush = 0
        flushOffsetUs = 0
    }

    private fun gainAt(timeUs: Long): Float {
        var gain = volume
        if (fadeInUs > 0 && timeUs < fadeInUs) gain *= (timeUs.toFloat() / fadeInUs).coerceIn(0f, 1f)
        val untilEnd = clipDurationUs - timeUs
        if (fadeOutUs > 0 && untilEnd < fadeOutUs) gain *= (untilEnd.toFloat() / fadeOutUs).coerceIn(0f, 1f)
        return gain
    }
}
