package com.naury.framekit.image.effect

import android.opengl.GLES30
import com.naury.framekit.core.effect.ColorEffectSpec
import kotlin.math.min

/**
 * 색 파이프라인의 GLSL ES 3.00 구성 요소다. 이미지 renderer와 영상 effect가 공유해 사진과 영상이
 * 같은 색을 얻는다.
 *
 * 모든 상수와 단계는 `ColorEffectProcessor`와 대응한다. 둘을 함께 바꾸고 `ColorEffectSpec.VERSION`을
 * 올려야 한다. 이 object는 FrameKit 내부 API이며 호환성 보장 대상이 아니다.
 */
public object ColorEffectShaders {

    public const val VERTEX: String = """#version 300 es
const vec2 POSITIONS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
    gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0);
}
"""

    private const val HEADER = """#version 300 es
precision highp float;
precision highp int;
"""

    private const val COMMON = """
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
"""

    private const val COLOR_UNIFORMS = """
uniform float uExposure;
uniform vec3 uWhiteBalance;
uniform float uShadows;
uniform float uHighlights;
uniform float uBrightness;
uniform float uContrast;
uniform float uSaturation;
uniform int uHasGrade;
uniform float uIntensity;
uniform vec3 uGradeGains;
uniform float uGradeBrightness;
uniform float uGradeContrast;
uniform float uGradeSaturation;
uniform vec3 uShadowTint;
uniform vec3 uHighlightTint;
uniform float uGradeFade;
"""

    /** `vec3 applyColor(vec4 premultiplied)`: 1·2단계를 수행하고 clamp된 표시용 RGB를 반환한다. */
    private const val COLOR_FUNCTION = """
float toLinear(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
float toSrgb(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
vec3 applyColor(vec4 p) {
    // CPU 기준은 premultiply를 푼 8비트 값을 읽으므로 같은 양자화를 거친다.
    vec3 c = p.a > 0.0 ? floor(clamp(p.rgb / p.a, 0.0, 1.0) * 255.0 + 0.5) / 255.0 : vec3(0.0);
    c = vec3(
        toSrgb(clamp(toLinear(c.r) * uExposure * uWhiteBalance.r, 0.0, 1.0)),
        toSrgb(clamp(toLinear(c.g) * uExposure * uWhiteBalance.g, 0.0, 1.0)),
        toSrgb(clamp(toLinear(c.b) * uExposure * uWhiteBalance.b, 0.0, 1.0)));
    if (uShadows != 0.0 || uHighlights != 0.0) {
        float y = luma(c);
        c += 0.35 * (uShadows * (1.0 - sstep(0.0, 0.55, y)) + uHighlights * sstep(0.45, 1.0, y));
    }
    c = (c + uBrightness - 0.5) * uContrast + 0.5;
    if (uSaturation != 1.0) {
        float y = luma(c);
        c = y + (c - y) * uSaturation;
    }
    if (uHasGrade == 1 && uIntensity > 0.0) {
        vec3 g = c * uGradeGains;
        g = (g + uGradeBrightness - 0.5) * uGradeContrast + 0.5;
        if (uGradeSaturation != 1.0) {
            float y = luma(g);
            g = y + (g - y) * uGradeSaturation;
        }
        float y = luma(g);
        g += (1.0 - sstep(0.0, 0.55, y)) * uShadowTint + sstep(0.45, 1.0, y) * uHighlightTint;
        if (uGradeFade != 0.0) g = g * (1.0 - uGradeFade) + uGradeFade;
        c = c + (g - c) * uIntensity;
    }
    return clamp(c, 0.0, 1.0);
}
"""

    private const val FINISH_UNIFORMS = """
uniform float uSharpen;
uniform float uFade;
uniform int uCanvas;
uniform float uVignette;
uniform float uGrain;
uniform int uSeed;
uniform float uCells;
uniform ivec2 uSize;
"""

    /**
     * `vec3 applyFinish(vec3 base, vec3 blurred, bool hasBlur, ivec2 pos, bool flipY)`: 3단계.
     * [flipY]는 위아래가 뒤집혀 저장된 입력을 위해 GL 행(아래→위)을 canvas 행(위→아래)으로 매핑한다.
     */
    private const val FINISH_FUNCTION = """
uint grainHash(int x, int y, int seed) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u + uint(seed) * 1442695041u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    return h ^ (h >> 16u);
}
vec3 applyFinish(vec3 base, vec3 blurred, bool hasBlur, ivec2 pos, bool flipY) {
    vec3 c = base;
    if (hasBlur) c = c + uSharpen * (c - blurred);
    if (uFade != 0.0) c = c * (1.0 - uFade) + uFade;
    if (uCanvas == 1) {
        int row = flipY ? uSize.y - 1 - pos.y : pos.y;
        float u = (float(pos.x) + 0.5) / float(uSize.x);
        float v = (float(row) + 0.5) / float(uSize.y);
        if (uVignette != 0.0) {
            vec2 d2 = (vec2(u, v) - 0.5) * 2.0;
            float d = length(d2) / 1.4142135;
            c *= 1.0 - 0.75 * uVignette * sstep(0.3, 1.0, d);
        }
        if (uGrain != 0.0) {
            int cx = int(floor(u * float(uSize.x) * uCells));
            int cy = int(floor(v * float(uSize.y) * uCells));
            float noise = float(grainHash(cx, cy, uSeed) & 16777215u) / 16777215.0 - 0.5;
            c += 0.12 * uGrain * noise;
        }
    }
    return clamp(c, 0.0, 1.0);
}
"""

    /** 이미지 1차 pass: 점 연산. 출력은 premultiply하지 않은 RGB와 alpha다. */
    internal const val COLOR: String = HEADER + "uniform sampler2D uInput;\nout vec4 outColor;\n" + COLOR_UNIFORMS + COMMON + COLOR_FUNCTION + """
void main() {
    vec4 p = texelFetch(uInput, ivec2(gl_FragCoord.xy), 0);
    outColor = vec4(applyColor(p), p.a);
}
"""

    /** premultiply하지 않은 8비트 RGB에 대한 분리형 Gaussian pass. 가장자리는 clamp한다. */
    internal const val BLUR: String = HEADER + """
uniform sampler2D uSource;
uniform float uWeights[49];
uniform int uRadius;
uniform ivec2 uDirection;
uniform ivec2 uSize;
out vec4 outColor;
void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    vec3 acc = vec3(0.0);
    for (int k = -uRadius; k <= uRadius; k++) {
        ivec2 q = clamp(pos + uDirection * k, ivec2(0), uSize - 1);
        acc += uWeights[abs(k)] * texelFetch(uSource, q, 0).rgb;
    }
    outColor = vec4(acc, texelFetch(uSource, pos, 0).a);
}
"""

    /** 이미지 마지막 pass: 선명도·페이드·비네트·그레인. 출력은 premultiply하지 않은 상태로 유지한다. */
    internal const val FINISH: String = HEADER + "uniform sampler2D uBase;\nuniform sampler2D uBlur;\nuniform int uHasBlur;\nout vec4 outColor;\n" +
        FINISH_UNIFORMS + COMMON + FINISH_FUNCTION + """
void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    vec4 base = texelFetch(uBase, pos, 0);
    vec3 blurred = texelFetch(uBlur, pos, 0).rgb;
    outColor = vec4(applyFinish(base.rgb, blurred, uHasBlur == 1, pos, false), base.a);
}
"""

    /**
     * 영상 프레임용 단일 pass: 점 연산·페이드·비네트·그레인. 선명도는 별도 blur pass가 필요해
     * 포함하지 않는다. 영상 텍스처는 아래에서 위로 저장되므로 캔버스 효과는 행을 뒤집는다.
     */
    public const val VIDEO: String = HEADER + "uniform sampler2D uInput;\nout vec4 outColor;\n" + COLOR_UNIFORMS + FINISH_UNIFORMS +
        COMMON + COLOR_FUNCTION + FINISH_FUNCTION + """
void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    vec4 p = texelFetch(uInput, pos, 0);
    vec3 c = applyColor(vec4(p.rgb, 1.0));
    outColor = vec4(applyFinish(c, c, false, pos, true), 1.0);
}
"""
}

/** 현재 program에 [ColorEffectShaders]가 선언한 uniform을 설정한다. FrameKit 내부 API다. */
public object ColorEffectUniforms {

    public fun setColor(program: Int, spec: ColorEffectSpec) {
        GLES30.glUniform1f(location(program, "uExposure"), spec.exposureGain)
        GLES30.glUniform3f(location(program, "uWhiteBalance"), spec.whiteBalance.red, spec.whiteBalance.green, spec.whiteBalance.blue)
        GLES30.glUniform1f(location(program, "uShadows"), spec.shadows)
        GLES30.glUniform1f(location(program, "uHighlights"), spec.highlights)
        GLES30.glUniform1f(location(program, "uBrightness"), spec.brightnessOffset)
        GLES30.glUniform1f(location(program, "uContrast"), spec.contrastFactor)
        GLES30.glUniform1f(location(program, "uSaturation"), spec.saturationFactor)
        val grade = spec.grade
        GLES30.glUniform1i(location(program, "uHasGrade"), if (grade != null) 1 else 0)
        GLES30.glUniform1f(location(program, "uIntensity"), spec.filterIntensity)
        if (grade != null) {
            GLES30.glUniform3f(location(program, "uGradeGains"), grade.gains.red, grade.gains.green, grade.gains.blue)
            GLES30.glUniform1f(location(program, "uGradeBrightness"), grade.brightnessOffset)
            GLES30.glUniform1f(location(program, "uGradeContrast"), grade.contrastFactor)
            GLES30.glUniform1f(location(program, "uGradeSaturation"), grade.saturationFactor)
            GLES30.glUniform3f(location(program, "uShadowTint"), grade.shadowTint.red, grade.shadowTint.green, grade.shadowTint.blue)
            GLES30.glUniform3f(location(program, "uHighlightTint"), grade.highlightTint.red, grade.highlightTint.green, grade.highlightTint.blue)
            GLES30.glUniform1f(location(program, "uGradeFade"), grade.fadeLift)
        }
    }

    public fun setFinish(program: Int, spec: ColorEffectSpec, width: Int, height: Int, includeCanvasEffects: Boolean) {
        GLES30.glUniform1f(location(program, "uSharpen"), spec.sharpenAmount)
        GLES30.glUniform1f(location(program, "uFade"), spec.fadeLift)
        GLES30.glUniform1i(location(program, "uCanvas"), if (includeCanvasEffects) 1 else 0)
        GLES30.glUniform1f(location(program, "uVignette"), spec.vignetteStrength)
        GLES30.glUniform1f(location(program, "uGrain"), spec.grainAmount)
        GLES30.glUniform1i(location(program, "uSeed"), spec.grainSeed)
        GLES30.glUniform1f(location(program, "uCells"), ColorEffectSpec.GRAIN_CELLS_SHORT_EDGE / min(width, height).toFloat())
        GLES30.glUniform2i(location(program, "uSize"), width, height)
    }

    private fun location(program: Int, name: String): Int = GLES30.glGetUniformLocation(program, name)
}
