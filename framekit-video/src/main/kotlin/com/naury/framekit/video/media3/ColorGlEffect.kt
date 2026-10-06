// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import android.content.Context
import android.opengl.GLES30
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.naury.framekit.core.effect.ColorEffectSpec
import com.naury.framekit.image.effect.ColorEffectShaders
import com.naury.framekit.image.effect.ColorEffectUniforms

/**
 * Applies a [ColorEffectSpec] to video frames with the same GLSL as the image renderer. Sharpening
 * needs an extra blur pass and is not applied to video.
 */
internal class ColorGlEffect(private val spec: ColorEffectSpec) : GlEffect {

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Color effects are SDR only")
        return ColorShaderProgram(spec)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = spec.copy(sharpenAmount = 0f).isIdentity
}

private class ColorShaderProgram(private val spec: ColorEffectSpec) : BaseGlShaderProgram(false, 1) {

    private val program = GlPrograms.link(ColorEffectShaders.VERTEX, ColorEffectShaders.VIDEO)
    private val vertexArray = GlPrograms.vertexArray()
    private var width = 0
    private var height = 0

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        width = inputWidth
        height = inputHeight
        return Size(inputWidth, inputHeight)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, inputTexId)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uInput"), 0)
        ColorEffectUniforms.setColor(program, spec)
        ColorEffectUniforms.setFinish(program, spec, width, height, includeCanvasEffects = true)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // Media3의 다른 셰이더는 기본 VAO(0)에 client 정점 배열을 쓴다. 바인딩을 남기면 그쪽이 실패한다.
        GLES30.glBindVertexArray(0)
        GlPrograms.check("color")
    }

    override fun release() {
        super.release()
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
    }
}

/** Small GL helpers for FrameKit's Media3 shader programs; called on Media3's GL thread. */
internal object GlPrograms {

    fun link(vertex: String, fragment: String): Int {
        val program = GLES30.glCreateProgram()
        val shaders = listOf(compile(GLES30.GL_VERTEX_SHADER, vertex), compile(GLES30.GL_FRAGMENT_SHADER, fragment))
        shaders.forEach { GLES30.glAttachShader(program, it) }
        GLES30.glLinkProgram(program)
        shaders.forEach(GLES30::glDeleteShader)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) throw VideoFrameProcessingException("Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
        return program
    }

    fun vertexArray(): Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

    fun check(stage: String) {
        val error = GLES30.glGetError()
        if (error != GLES30.GL_NO_ERROR) throw VideoFrameProcessingException("GL error 0x${Integer.toHexString(error)} in $stage")
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) throw VideoFrameProcessingException("Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
        return shader
    }
}
