package com.naury.framekit.motion.codec

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 비트맵 프레임을 H.264 MP4로 쓴다. 프레임마다 시각을 정확히 지정할 수 있어 길이가 다른 프레임도 그대로 담는다.
 *
 * 인코더 입력 Surface에 OpenGL로 비트맵을 그린다. 만들기·[addFrame]·[close]는 모두 같은 스레드에서 불러야 한다
 * (EGL 컨텍스트가 스레드에 묶인다).
 *
 * @param width 짝수여야 한다.
 * @param height 짝수여야 한다.
 * @param frameRate 비트레이트 계산과 키프레임 간격에 쓰는 대표 프레임 수(초당).
 * @throws IOException 이 크기로 인코더를 만들 수 없을 때.
 */
public class Mp4FrameEncoder(file: File, public val width: Int, public val height: Int, frameRate: Int) : Closeable {

    private val codec: MediaCodec
    private val muxer: MediaMuxer
    private val inputSurface: Surface
    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    // 클라이언트 쪽 정점 배열은 그릴 때마다 읽히므로 인코더가 살아 있는 동안 붙잡아 둔다.
    private var quadBuffer: ByteBuffer? = null
    private var texture = 0
    private var track = -1
    private var muxerStarted = false
    private var lastPtsUs = -1L
    private var closed = false
    private val info = MediaCodec.BufferInfo()

    init {
        require(width > 0 && height > 0 && width % 2 == 0 && height % 2 == 0) { "MP4 size must be positive and even" }
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * frameRate.coerceIn(1, 60) * BITS_PER_PIXEL).toInt().coerceIn(MIN_BIT_RATE, MAX_BIT_RATE))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate.coerceIn(1, 60))
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec = try {
            MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE) }
        } catch (error: Exception) {
            throw IOException("H.264 encoder is not available for ${width}x$height", error)
        }
        inputSurface = codec.createInputSurface()
        codec.start()
        muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            setUpEgl()
            setUpGl()
        } catch (error: RuntimeException) {
            releaseAll()
            throw IOException("OpenGL setup failed", error)
        }
    }

    /**
     * @param bitmap 프레임. 크기가 달라도 화면 전체에 늘려 그린다.
     * @param presentationTimeUs 프레임 시각(마이크로초). 앞 프레임보다 커야 한다.
     */
    public fun addFrame(bitmap: Bitmap, presentationTimeUs: Long) {
        check(!closed) { "Encoder is closed" }
        val pts = if (presentationTimeUs <= lastPtsUs) lastPtsUs + 1 else presentationTimeUs
        lastPtsUs = pts
        // 색 보정 렌더러 등 다른 GL 컨텍스트가 같은 스레드에서 쓰였을 수 있어 매번 다시 붙인다.
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        GLES20.glViewport(0, 0, width, height)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, pts * 1000)
        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        drain(endOfStream = false)
    }

    /** 마지막 프레임을 [lastDurationUs]만큼 보여 준 뒤 파일을 마무리한다. */
    public fun finish(lastDurationUs: Long) {
        if (closed) return
        // 마지막 프레임의 길이를 담으려고 같은 그림을 끝 시각에 한 번 더 넣는다.
        if (lastPtsUs >= 0 && lastDurationUs > 0) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(eglDisplay, eglSurface, (lastPtsUs + lastDurationUs) * 1000)
            EGL14.eglSwapBuffers(eglDisplay, eglSurface)
        }
        codec.signalEndOfInputStream()
        drain(endOfStream = true)
        close()
    }

    override fun close() {
        if (closed) return
        closed = true
        releaseAll()
    }

    private fun drain(endOfStream: Boolean) {
        // 끝 표시가 오지 않는 인코더에서 멈추지 않도록 마무리 대기에 한도를 둔다.
        val deadline = System.nanoTime() + EOS_TIMEOUT_NS
        while (true) {
            if (endOfStream && System.nanoTime() > deadline) return
            val index = codec.dequeueOutputBuffer(info, if (endOfStream) DRAIN_TIMEOUT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!endOfStream) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    muxerStarted = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && buffer != null && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun setUpEgl() {
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) { "eglInitialize failed" }
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No EGL config" }
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        eglSurface = EGL14.eglCreateWindowSurface(eglDisplay, configs[0], inputSurface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglContext != EGL14.EGL_NO_CONTEXT && eglSurface != EGL14.EGL_NO_SURFACE) { "EGL surface failed" }
        check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) { "eglMakeCurrent failed" }
    }

    private fun setUpGl() {
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, shader(GLES20.GL_VERTEX_SHADER, VERTEX))
        GLES20.glAttachShader(program, shader(GLES20.GL_FRAGMENT_SHADER, FRAGMENT))
        GLES20.glLinkProgram(program)
        GLES20.glUseProgram(program)
        // 화면 전체 사각형. 비트맵은 위가 0이라 텍스처 y를 뒤집는다.
        val quad = floatArrayOf(-1f, -1f, 0f, 1f, 1f, -1f, 1f, 1f, -1f, 1f, 0f, 0f, 1f, 1f, 1f, 0f)
        val buffer: ByteBuffer = ByteBuffer.allocateDirect(quad.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(quad)
        quadBuffer = buffer
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aCoord")
        buffer.position(0)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(position)
        buffer.position(8)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 16, buffer)
        GLES20.glEnableVertexAttribArray(coordinate)
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        texture = textures[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
    }

    private fun shader(type: Int, source: String): Int = GLES20.glCreateShader(type).also {
        GLES20.glShaderSource(it, source)
        GLES20.glCompileShader(it)
    }

    private fun releaseAll() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglReleaseThread()
        }
        eglDisplay = EGL14.EGL_NO_DISPLAY
        inputSurface.release()
    }

    private companion object {
        const val EGL_RECORDABLE_ANDROID = 0x3142
        const val BITS_PER_PIXEL = 0.12
        const val MIN_BIT_RATE = 1_000_000
        const val MAX_BIT_RATE = 20_000_000
        const val DRAIN_TIMEOUT_US = 10_000L
        const val EOS_TIMEOUT_NS = 5_000_000_000L
        const val VERTEX = "attribute vec4 aPosition; attribute vec2 aCoord; varying vec2 vCoord; void main() { gl_Position = aPosition; vCoord = aCoord; }"
        const val FRAGMENT = "precision mediump float; varying vec2 vCoord; uniform sampler2D uTexture; void main() { gl_FragColor = texture2D(uTexture, vCoord); }"
    }
}
