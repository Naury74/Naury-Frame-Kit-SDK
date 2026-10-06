package com.naury.framekit.image.effect

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES30
import android.opengl.GLUtils
import com.naury.framekit.core.effect.ColorEffectSpec
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

/**
 * [ColorEffectSpec]용 OpenGL ES 3.0 renderer다.
 *
 * 전용 스레드 하나가 EGL context를 소유하고 자원 삭제를 포함한 모든 GL 호출이 그 스레드에서 실행되므로,
 * 미리보기와 내보내기가 동시에 텍스처를 건드리지 않는다. 출력 크기의 텍스처는 동시에 최대 3장만 존재하며,
 * 결과는 띠 단위로 읽어 호출자의 bitmap에 담는다.
 *
 * @throws GlUnavailableException 기기가 ES 3.0 context를 만들 수 없으면 생성자에서
 *   던진다.
 */
public class GlColorEffectRenderer : ColorEffectRenderer {

    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "framekit-gl").apply { isDaemon = true }
    }
    private lateinit var display: EGLDisplay
    private lateinit var context: EGLContext
    private lateinit var surface: EGLSurface
    private var colorProgram = 0
    private var blurProgram = 0
    private var finishProgram = 0
    private var framebuffer = 0

    /** 이 기기가 렌더링할 수 있는 최대 너비 또는 높이. */
    public val maxSize: Int

    @Volatile
    private var released = false

    init {
        maxSize = try {
            onGlThread { initialize() }
        } catch (error: Throwable) {
            executor.shutdownNow()
            throw GlUnavailableException("OpenGL ES 3.0 is not available", error)
        }
    }

    /** 이 크기의 bitmap이 GPU 한도 안에 들면 `true`. */
    public fun supports(width: Int, height: Int): Boolean = width <= maxSize && height <= maxSize

    override fun apply(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean) {
        if (spec.isIdentity) return
        check(!released) { "Renderer was released" }
        require(bitmap.isMutable && bitmap.config == Bitmap.Config.ARGB_8888) { "Bitmap must be mutable ARGB_8888" }
        require(supports(bitmap.width, bitmap.height)) { "Bitmap exceeds GPU limit $maxSize" }
        onGlThread { render(bitmap, spec, includeCanvasEffects) }
    }

    // GPU 텍스처는 시스템 메모리를 공유하는 기기가 많아 최악의 경우 텍스처 3장과 readback 띠를 잡는다.
    override fun workingBytes(width: Int, height: Int, spec: ColorEffectSpec): Long =
        if (spec.isIdentity) 0L else width.toLong() * height * 4 * (if (spec.sharpenAmount != 0f) 3 else 2)

    override fun release() {
        if (released) return
        released = true
        runCatching {
            onGlThread {
                GLES30.glDeleteProgram(colorProgram)
                GLES30.glDeleteProgram(blurProgram)
                GLES30.glDeleteProgram(finishProgram)
                GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, surface)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
        }
        executor.shutdown()
    }

    private fun initialize(): Int {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1)) { "eglInitialize failed" }
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val attributes = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_NONE,
        )
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0) && count[0] > 0) { "No ES 3.0 config" }
        val config = checkNotNull(configs[0])
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "eglCreateContext failed" }
        surface = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        check(EGL14.eglMakeCurrent(display, surface, surface, context)) { "eglMakeCurrent failed" }

        colorProgram = linkProgram(ColorEffectShaders.COLOR)
        blurProgram = linkProgram(ColorEffectShaders.BLUR)
        finishProgram = linkProgram(ColorEffectShaders.FINISH)
        val ids = IntArray(1)
        GLES30.glGenFramebuffers(1, ids, 0)
        framebuffer = ids[0]
        GLES30.glBindVertexArray(createVertexArray())

        val textureLimit = IntArray(1)
        GLES30.glGetIntegerv(GLES30.GL_MAX_TEXTURE_SIZE, textureLimit, 0)
        val viewportLimit = IntArray(2)
        GLES30.glGetIntegerv(GLES30.GL_MAX_VIEWPORT_DIMS, viewportLimit, 0)
        return min(textureLimit[0], min(viewportLimit[0], viewportLimit[1]))
    }

    private fun render(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean) {
        val width = bitmap.width
        val height = bitmap.height
        GLES30.glViewport(0, 0, width, height)
        GLES30.glDisable(GLES30.GL_BLEND)
        val input = uploadTexture(bitmap)
        val base = emptyTexture(width, height)
        val textures = mutableListOf(input, base)
        try {
            drawInto(base) {
                GLES30.glUseProgram(colorProgram)
                bindTexture(colorProgram, "uInput", input, 0)
                ColorEffectUniforms.setColor(colorProgram, spec)
            }
            GLES30.glDeleteTextures(1, intArrayOf(input), 0)
            textures.remove(input)

            var blurred = 0
            val scratch = emptyTexture(width, height).also(textures::add)
            if (spec.sharpenAmount != 0f) {
                val weights = spec.sharpenWeights(min(width, height))
                blurred = emptyTexture(width, height).also(textures::add)
                blurPass(base, scratch, weights, width, height, horizontal = true)
                blurPass(scratch, blurred, weights, width, height, horizontal = false)
            }
            drawInto(scratch) {
                GLES30.glUseProgram(finishProgram)
                bindTexture(finishProgram, "uBase", base, 0)
                bindTexture(finishProgram, "uBlur", if (blurred != 0) blurred else base, 1)
                uniform1i(finishProgram, "uHasBlur", if (blurred != 0) 1 else 0)
                ColorEffectUniforms.setFinish(finishProgram, spec, width, height, includeCanvasEffects)
            }
            // 원본 bitmap을 덮어쓰기 전에 오류를 확인해야 실패 시 CPU 경로가 손상되지 않은 원본으로 다시 그린다.
            checkGlError("render")
            readBack(scratch, bitmap)
            checkGlError("readback")
        } finally {
            GLES30.glDeleteTextures(textures.size, textures.toIntArray(), 0)
        }
    }

    private fun blurPass(source: Int, target: Int, weights: FloatArray, width: Int, height: Int, horizontal: Boolean) {
        drawInto(target) {
            GLES30.glUseProgram(blurProgram)
            bindTexture(blurProgram, "uSource", source, 0)
            val padded = FloatArray(MAX_WEIGHTS).also { weights.copyInto(it) }
            GLES30.glUniform1fv(location(blurProgram, "uWeights"), MAX_WEIGHTS, padded, 0)
            uniform1i(blurProgram, "uRadius", weights.size - 1)
            GLES30.glUniform2i(location(blurProgram, "uDirection"), if (horizontal) 1 else 0, if (horizontal) 0 else 1)
            GLES30.glUniform2i(location(blurProgram, "uSize"), width, height)
        }
    }

    private inline fun drawInto(texture: Int, setup: () -> Unit) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        check(GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) { "Framebuffer incomplete" }
        setup()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
    }

    // 결과는 premultiply하지 않은 RGBA라 setPixels로 넘기면 Bitmap이 직접 premultiply한다.
    // 출력 크기만큼의 버퍼를 한 번에 잡지 않도록 띠 단위로 읽는다.
    private fun readBack(texture: Int, bitmap: Bitmap) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, texture, 0)
        val width = bitmap.width
        val rows = min(bitmap.height, maxOf(1, READBACK_BYTES / (width * 4)))
        val buffer = ByteBuffer.allocateDirect(width * rows * 4).order(ByteOrder.LITTLE_ENDIAN)
        val pixels = IntArray(width * rows)
        var top = 0
        while (top < bitmap.height) {
            val count = min(rows, bitmap.height - top)
            buffer.clear()
            GLES30.glReadPixels(0, top, width, count, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            buffer.asIntBuffer().get(pixels, 0, width * count)
            // little-endian으로 읽은 RGBA 바이트는 0xAABBGGRR이므로 R과 B를 바꿔 ARGB로 만든다.
            for (i in 0 until width * count) {
                val p = pixels[i]
                pixels[i] = (p and 0xFF00FF00.toInt()) or ((p and 0xFF) shl 16) or ((p ushr 16) and 0xFF)
            }
            bitmap.setPixels(pixels, 0, width, 0, top, width, count)
            top += count
        }
    }

    private fun uploadTexture(bitmap: Bitmap): Int {
        val texture = createTexture()
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bitmap, 0)
        return texture
    }

    private fun emptyTexture(width: Int, height: Int): Int {
        val texture = createTexture()
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        return texture
    }

    private fun createTexture(): Int {
        val ids = IntArray(1)
        GLES30.glGenTextures(1, ids, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, ids[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return ids[0]
    }

    private fun bindTexture(program: Int, name: String, texture: Int, unit: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        uniform1i(program, name, unit)
    }

    private fun createVertexArray(): Int {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0)
        return ids[0]
    }

    private fun linkProgram(fragment: String): Int {
        val program = GLES30.glCreateProgram()
        val vertexShader = compile(GLES30.GL_VERTEX_SHADER, ColorEffectShaders.VERTEX)
        val fragmentShader = compile(GLES30.GL_FRAGMENT_SHADER, fragment)
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertexShader)
        GLES30.glDeleteShader(fragmentShader)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Program link failed: ${GLES30.glGetProgramInfoLog(program)}" }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES30.GL_TRUE) { "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}" }
        return shader
    }

    private fun location(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)


    private fun uniform1i(program: Int, name: String, value: Int) = GLES30.glUniform1i(location(program, name), value)

    private fun checkGlError(stage: String) {
        val error = GLES30.glGetError()
        check(error == GLES30.GL_NO_ERROR) { "GL error 0x${Integer.toHexString(error)} in $stage" }
    }

    private fun <T> onGlThread(block: () -> T): T = try {
        executor.submit(block).get()
    } catch (error: ExecutionException) {
        throw error.cause ?: error
    }

    private companion object {
        const val EGL_OPENGL_ES3_BIT = 0x40
        const val MAX_WEIGHTS = ColorEffectSpec.MAX_BLUR_RADIUS + 1
        const val READBACK_BYTES = 4 * 1024 * 1024
    }
}

/** 기기가 OpenGL ES 3.0 context를 만들 수 없다. */
public class GlUnavailableException(message: String, cause: Throwable?) : Exception(message, cause)

/**
 * 가능하면 GPU를 쓰고, context를 만들 수 없거나, bitmap이 GPU 크기 한도를 넘거나, 실행 중 GL 오류가 나면
 * [CpuColorEffectRenderer]로 대체한다. 두 경로는 같은 명세를 구현한다.
 */
public class DefaultColorEffectRenderer : ColorEffectRenderer {

    private val glDelegate = lazy {
        try {
            GlColorEffectRenderer()
        } catch (_: GlUnavailableException) {
            null
        }
    }
    private val gl: GlColorEffectRenderer? get() = if (glFailed) null else glDelegate.value

    // 드라이버 오류나 GPU 메모리 부족으로 실행 중 GL이 실패하면 이후 요청은 CPU로만 처리한다.
    @Volatile
    private var glFailed = false

    /** GPU 경로를 사용 중이면 `true`. */
    public val usesGpu: Boolean get() = gl != null

    override fun apply(bitmap: Bitmap, spec: ColorEffectSpec, includeCanvasEffects: Boolean) {
        val gpu = gl?.takeIf { it.supports(bitmap.width, bitmap.height) }
        if (gpu != null) {
            try {
                gpu.apply(bitmap, spec, includeCanvasEffects)
                return
            } catch (_: IllegalStateException) {
                glFailed = true
            }
        }
        CpuColorEffectRenderer.apply(bitmap, spec, includeCanvasEffects)
    }

    override fun workingBytes(width: Int, height: Int, spec: ColorEffectSpec): Long {
        val renderer = gl?.takeIf { it.supports(width, height) } ?: CpuColorEffectRenderer
        return renderer.workingBytes(width, height, spec)
    }

    override fun release() {
        if (glDelegate.isInitialized()) glDelegate.value?.release()
    }
}
