// Media3의 composition·effect·Transformer API는 @UnstableApi다. FrameKit은 이 모듈 안의 어댑터에서만 쓰고
// 버전을 고정(1.11.1)해 API 변경을 빌드 단계에서 확인한다.
@file:OptIn(UnstableApi::class)

package com.naury.framekit.video.media3

import android.content.Context
import android.opengl.GLES30
import androidx.annotation.OptIn
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import com.naury.framekit.core.overlay.MaskShape
import com.naury.framekit.core.overlay.PrivacyEffect
import com.naury.framekit.core.video.TimedPrivacyMask
import com.naury.framekit.core.video.Timeline
import com.naury.framekit.image.effect.ColorEffectShaders
import kotlin.math.max
import kotlin.math.min

/**
 * Mosaic and blur masks on the output canvas of a video, each active during its own output time
 * range. Rectangles and ellipses are supported; brush masks are image-only.
 *
 * Mosaic blocks are anchored at the canvas top-left and sized relative to the short edge, as on
 * photos. Blur averages a disk of samples, which hides detail but, like any blur, is not guaranteed
 * to be irreversible.
 */
internal class PrivacyGlEffect(private val masks: List<TimedPrivacyMask>) : GlEffect {

    init {
        require(masks.size <= MAX_MASKS) { "At most $MAX_MASKS video masks" }
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram {
        if (useHdr) throw VideoFrameProcessingException("Privacy masks are SDR only")
        return PrivacyShaderProgram(masks)
    }

    override fun isNoOp(inputWidth: Int, inputHeight: Int): Boolean = masks.none { it.mask.shape !is MaskShape.Brush }

    companion object {
        const val MAX_MASKS = Timeline.MAX_PRIVACY_MASKS
    }
}

private class PrivacyShaderProgram(masks: List<TimedPrivacyMask>) : BaseGlShaderProgram(false, 1) {

    private val active = masks.filter { it.mask.shape !is MaskShape.Brush }
    private val program = GlPrograms.link(ColorEffectShaders.VERTEX, FRAGMENT)
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
        GLES30.glUniform1i(location("uInput"), 0)
        GLES30.glUniform2i(location("uSize"), width, height)
        val shortEdge = min(width, height).toFloat()
        val visible = active.filter { presentationTimeUs in it.range }
        GLES30.glUniform1i(location("uCount"), visible.size)
        val rects = FloatArray(MAX * 4)
        val kinds = IntArray(MAX)
        val sizes = FloatArray(MAX)
        visible.forEachIndexed { i, timed ->
            val rect = when (val shape = timed.mask.shape) {
                is MaskShape.Rectangle -> shape.rect
                is MaskShape.Ellipse -> shape.rect
                is MaskShape.Brush -> return@forEachIndexed
            }
            rects[i * 4] = min(rect.left, rect.right).toFloat()
            rects[i * 4 + 1] = min(rect.top, rect.bottom).toFloat()
            rects[i * 4 + 2] = max(rect.left, rect.right).toFloat()
            rects[i * 4 + 3] = max(rect.top, rect.bottom).toFloat()
            val ellipse = timed.mask.shape is MaskShape.Ellipse
            when (val effect = timed.mask.effect) {
                is PrivacyEffect.Mosaic -> {
                    kinds[i] = if (ellipse) KIND_MOSAIC_ELLIPSE else KIND_MOSAIC_RECT
                    sizes[i] = max(1f, (effect.blockShortEdgeRatio * shortEdge).toFloat())
                }
                is PrivacyEffect.Blur -> {
                    kinds[i] = if (ellipse) KIND_BLUR_ELLIPSE else KIND_BLUR_RECT
                    sizes[i] = max(1f, (effect.radiusShortEdgeRatio * shortEdge).toFloat())
                }
            }
        }
        GLES30.glUniform4fv(location("uRects"), MAX, rects, 0)
        GLES30.glUniform1iv(location("uKinds"), MAX, kinds, 0)
        GLES30.glUniform1fv(location("uSizes"), MAX, sizes, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GlPrograms.check("privacy")
    }

    override fun release() {
        super.release()
        GLES30.glDeleteProgram(program)
        GLES30.glDeleteVertexArrays(1, intArrayOf(vertexArray), 0)
    }

    private fun location(name: String) = GLES30.glGetUniformLocation(program, name)

    private companion object {
        const val MAX = PrivacyGlEffect.MAX_MASKS
        const val KIND_MOSAIC_RECT = 1
        const val KIND_MOSAIC_ELLIPSE = 2
        const val KIND_BLUR_RECT = 3
        const val KIND_BLUR_ELLIPSE = 4

        // 영상 텍스처는 아래쪽 행부터 저장되므로 캔버스 좌표(위쪽 원점)로 바꿔 판정·격자를 계산한다.
        const val FRAGMENT = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uInput;
uniform ivec2 uSize;
uniform int uCount;
uniform vec4 uRects[8];
uniform int uKinds[8];
uniform float uSizes[8];
out vec4 outColor;
vec4 fetchCanvas(vec2 canvasPx) {
    ivec2 p = ivec2(clamp(canvasPx, vec2(0.0), vec2(uSize) - 1.0));
    return texelFetch(uInput, ivec2(p.x, uSize.y - 1 - p.y), 0);
}
bool inside(int i, vec2 uv) {
    vec4 r = uRects[i];
    if (uKinds[i] == 2 || uKinds[i] == 4) {
        vec2 c = (r.xy + r.zw) * 0.5;
        vec2 h = max((r.zw - r.xy) * 0.5, vec2(1e-5));
        vec2 d = (uv - c) / h;
        return dot(d, d) <= 1.0;
    }
    return uv.x >= r.x && uv.x <= r.z && uv.y >= r.y && uv.y <= r.w;
}
void main() {
    ivec2 gl = ivec2(gl_FragCoord.xy);
    vec2 canvasPx = vec2(float(gl.x) + 0.5, float(uSize.y - 1 - gl.y) + 0.5);
    vec2 uv = canvasPx / vec2(uSize);
    vec4 color = texelFetch(uInput, gl, 0);
    for (int i = 0; i < uCount; i++) {
        if (!inside(i, uv)) continue;
        float s = uSizes[i];
        if (uKinds[i] <= 2) {
            vec2 origin = floor(canvasPx / s) * s;
            vec4 acc = vec4(0.0);
            for (int y = 0; y < 4; y++) for (int x = 0; x < 4; x++) {
                acc += fetchCanvas(origin + (vec2(float(x), float(y)) + 0.5) * s / 4.0);
            }
            color = acc / 16.0;
        } else {
            vec4 acc = vec4(0.0);
            float total = 0.0;
            for (int y = -4; y <= 4; y++) for (int x = -4; x <= 4; x++) {
                vec2 o = vec2(float(x), float(y)) / 4.0;
                if (dot(o, o) > 1.0) continue;
                acc += fetchCanvas(canvasPx + o * s * 2.0);
                total += 1.0;
            }
            color = acc / total;
        }
        break;
    }
    outColor = color;
}
"""
    }
}
