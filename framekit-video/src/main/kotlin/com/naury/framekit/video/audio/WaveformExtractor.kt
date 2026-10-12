package com.naury.framekit.video.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * 소리 크기 요약. [peaks]의 칸 하나가 원본 [bucketUs] 동안의 가장 큰 소리(`0..1`)다.
 */
public class Waveform(public val peaks: FloatArray, public val bucketUs: Long) {

    /** 원본 시간 [timeUs]의 소리 크기(`0..1`). 범위를 벗어나면 `0`. */
    public fun peakAt(timeUs: Long): Float {
        val index = (timeUs / bucketUs).toInt()
        return if (index in peaks.indices) peaks[index] else 0f
    }
}

/**
 * 소리 파일이나 영상의 소리 트랙을 풀어 파형 요약을 만든다. 오래 걸릴 수 있으므로 메인 스레드에서 부르지 않는다.
 * 타임라인에서 말소리·음악이 어디 있는지 보이게 하는 데 쓴다.
 */
public object WaveformExtractor {

    /**
     * @param bucketUs 칸 하나의 길이(µs).
     * @param maxDurationUs 이 길이까지만 읽는다(아주 긴 파일에서 시간이 오래 걸리지 않게).
     * @return 소리 트랙이 없거나 풀 수 없으면 `null`.
     */
    public fun extract(context: Context, uri: Uri, bucketUs: Long = DEFAULT_BUCKET_US, maxDurationUs: Long = MAX_DURATION_US): Waveform? {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, uri, null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val duration = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else maxDurationUs
            val buckets = ((duration.coerceAtMost(maxDurationUs) / bucketUs) + 1).toInt().coerceIn(1, MAX_BUCKETS)
            val peaks = FloatArray(buckets)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var channels = format.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var sampleRate = format.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, 44_100)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val deadline = System.nanoTime() + TIME_LIMIT_NS
            while (System.nanoTime() < deadline) {
                if (!inputDone) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = checkNotNull(decoder.getInputBuffer(inIndex))
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0 || extractor.sampleTime > maxDurationUs) {
                            decoder.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val out = decoder.outputFormat
                        channels = out.getIntegerOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        sampleRate = out.getIntegerOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        encoding = out.getIntegerOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    }
                    outIndex >= 0 -> {
                        val buffer = decoder.getOutputBuffer(outIndex)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                            val frameBytes = bytesPerSample * channels.coerceAtLeast(1)
                            var frame = 0L
                            while (buffer.remaining() >= frameBytes) {
                                var peak = 0f
                                repeat(channels.coerceAtLeast(1)) {
                                    val value = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) abs(buffer.float) else abs(buffer.short.toInt()) / 32_768f
                                    if (value > peak) peak = value
                                }
                                val timeUs = info.presentationTimeUs + frame * 1_000_000L / sampleRate
                                val index = (timeUs / bucketUs).toInt()
                                if (index in peaks.indices && peak > peaks[index]) peaks[index] = peak
                                frame++
                            }
                        }
                        decoder.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
            return Waveform(normalize(peaks), bucketUs)
        } catch (_: Exception) {
            return null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }

    // 조용한 녹음도 모양이 보이도록 가장 큰 소리를 1에 맞춘다(너무 작게 키우지는 않는다).
    private fun normalize(peaks: FloatArray): FloatArray {
        val max = peaks.maxOrNull() ?: return peaks
        if (max <= 0f) return peaks
        val scale = 1f / max.coerceAtLeast(MIN_REFERENCE)
        return FloatArray(peaks.size) { (peaks[it] * scale).coerceIn(0f, 1f) }
    }

    private fun MediaFormat.getIntegerOr(key: String, fallback: Int): Int = if (containsKey(key)) getInteger(key) else fallback

    public const val DEFAULT_BUCKET_US: Long = 20_000
    private const val MAX_DURATION_US = 20 * 60 * 1_000_000L
    private const val MAX_BUCKETS = 60_000
    private const val TIMEOUT_US = 5_000L
    private const val TIME_LIMIT_NS = 20_000_000_000L
    private const val MIN_REFERENCE = 0.1f
}
