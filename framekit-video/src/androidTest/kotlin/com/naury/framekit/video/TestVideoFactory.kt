package com.naury.framekit.video

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.sin

/**
 * Writes a deterministic test video: quadrants red (top left), green (top right), blue (bottom left)
 * and white (bottom right), H.264 at 30 fps, with an optional 440 Hz AAC tone. Generated on the
 * device so the repository needs no binary fixtures.
 */
internal object TestVideoFactory {

    const val WIDTH = 640
    const val HEIGHT = 360
    const val FPS = 30

    fun create(file: File, durationUs: Long = 5_000_000L, withAudio: Boolean = true) {
        val video = encodeVideo(durationUs)
        val audio = if (withAudio) encodeAudio(durationUs) else null
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val videoTrack = muxer.addTrack(video.format)
        val audioTrack = audio?.let { muxer.addTrack(it.format) }
        muxer.start()
        video.samples.forEach { muxer.writeSampleData(videoTrack, ByteBuffer.wrap(it.data), it.info) }
        if (audio != null && audioTrack != null) audio.samples.forEach { muxer.writeSampleData(audioTrack, ByteBuffer.wrap(it.data), it.info) }
        muxer.stop()
        muxer.release()
    }

    private class Sample(val data: ByteArray, val info: MediaCodec.BufferInfo)
    private class Track(val format: MediaFormat, val samples: List<Sample>)

    private fun encodeVideo(durationUs: Long): Track {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val frames = (durationUs * FPS / 1_000_000L).toInt()
        var frame = 0
        val samples = mutableListOf<Sample>()
        var outputFormat: MediaFormat? = null
        var done = false
        val info = MediaCodec.BufferInfo()
        while (!done) {
            if (frame <= frames) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    if (frame == frames) {
                        codec.queueInputBuffer(index, 0, 0, frame * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    } else {
                        val image = checkNotNull(codec.getInputImage(index))
                        fillQuadrants(image)
                        codec.queueInputBuffer(index, 0, WIDTH * HEIGHT * 3 / 2, frame * 1_000_000L / FPS, 0)
                    }
                    frame++
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                out >= 0 -> {
                    val buffer = checkNotNull(codec.getOutputBuffer(out))
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                        samples += Sample(data, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        codec.stop()
        codec.release()
        return Track(checkNotNull(outputFormat), samples)
    }

    private fun fillQuadrants(image: Image) {
        val colors = arrayOf(intArrayOf(255, 0, 0), intArrayOf(0, 255, 0), intArrayOf(0, 0, 255), intArrayOf(255, 255, 255))
        fun quadrant(x: Int, y: Int) = (if (y < HEIGHT / 2) 0 else 2) + (if (x < WIDTH / 2) 0 else 1)
        val (yPlane, uPlane, vPlane) = image.planes.let { Triple(it[0], it[1], it[2]) }
        for (y in 0 until HEIGHT) for (x in 0 until WIDTH) {
            val c = colors[quadrant(x, y)]
            val luma = (16 + 0.257 * c[0] + 0.504 * c[1] + 0.098 * c[2]).toInt()
            yPlane.buffer.put(y * yPlane.rowStride + x * yPlane.pixelStride, luma.toByte())
        }
        for (y in 0 until HEIGHT / 2) for (x in 0 until WIDTH / 2) {
            val c = colors[quadrant(x * 2, y * 2)]
            val u = (128 - 0.148 * c[0] - 0.291 * c[1] + 0.439 * c[2]).toInt()
            val v = (128 + 0.439 * c[0] - 0.368 * c[1] - 0.071 * c[2]).toInt()
            uPlane.buffer.put(y * uPlane.rowStride + x * uPlane.pixelStride, u.toByte())
            vPlane.buffer.put(y * vPlane.rowStride + x * vPlane.pixelStride, v.toByte())
        }
    }

    private fun encodeAudio(durationUs: Long): Track {
        val sampleRate = 44_100
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 96_000)
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val totalSamples = durationUs * sampleRate / 1_000_000L
        var written = 0L
        var inputDone = false
        var done = false
        val samples = mutableListOf<Sample>()
        var outputFormat: MediaFormat? = null
        val info = MediaCodec.BufferInfo()
        while (!done) {
            if (!inputDone) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val buffer = checkNotNull(codec.getInputBuffer(index))
                    val count = minOf((buffer.capacity() / 2).toLong(), totalSamples - written).toInt()
                    val timeUs = written * 1_000_000L / sampleRate
                    if (count <= 0) {
                        codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        for (i in 0 until count) {
                            val value = (sin(2 * PI * 440 * (written + i) / sampleRate) * 8000).toInt()
                            buffer.put((value and 0xFF).toByte())
                            buffer.put(((value shr 8) and 0xFF).toByte())
                        }
                        codec.queueInputBuffer(index, 0, count * 2, timeUs, 0)
                        written += count
                    }
                }
            }
            val out = codec.dequeueOutputBuffer(info, 10_000)
            when {
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                out >= 0 -> {
                    val buffer = checkNotNull(codec.getOutputBuffer(out))
                    if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        val data = ByteArray(info.size).also { buffer.position(info.offset); buffer.get(it) }
                        samples += Sample(data, MediaCodec.BufferInfo().apply { set(0, info.size, info.presentationTimeUs, info.flags) })
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) done = true
                }
            }
        }
        codec.stop()
        codec.release()
        return Track(checkNotNull(outputFormat), samples)
    }
}
