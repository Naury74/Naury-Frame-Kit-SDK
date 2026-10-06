package com.naury.framekit.image.effect

/**
 * GLSL ES 3.00 sources. Every constant and step mirrors `ColorEffectProcessor`; change both together
 * and bump `ColorEffectSpec.VERSION`.
 */
internal object GlShaders {

    const val VERTEX = """#version 300 es
const vec2 POSITIONS[3] = vec2[3](vec2(-1.0, -1.0), vec2(3.0, -1.0), vec2(-1.0, 3.0));
void main() {
    gl_Position = vec4(POSITIONS[gl_VertexID], 0.0, 1.0);
}
"""

    private const val COMMON = """
float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }
float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
"""

    /** Pass 1: point operations. Input is premultiplied; output is non-premultiplied RGB with alpha. */
    const val COLOR = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uInput;
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
out vec4 outColor;
""" + COMMON + """
float toLinear(float c) { return c <= 0.04045 ? c / 12.92 : pow((c + 0.055) / 1.055, 2.4); }
float toSrgb(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }
void main() {
    vec4 p = texelFetch(uInput, ivec2(gl_FragCoord.xy), 0);
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
    outColor = vec4(clamp(c, 0.0, 1.0), p.a);
}
"""

    /** Separable Gaussian pass over 8-bit non-premultiplied RGB with edge clamping. */
    const val BLUR = """#version 300 es
precision highp float;
precision highp int;
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

    /** Last pass: sharpening, fade, vignette and grain; output stays non-premultiplied for readback. */
    const val FINISH = """#version 300 es
precision highp float;
precision highp int;
uniform sampler2D uBase;
uniform sampler2D uBlur;
uniform int uHasBlur;
uniform float uSharpen;
uniform float uFade;
uniform int uCanvas;
uniform float uVignette;
uniform float uGrain;
uniform int uSeed;
uniform float uCells;
uniform ivec2 uSize;
out vec4 outColor;
""" + COMMON + """
uint grainHash(int x, int y, int seed) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u + uint(seed) * 1442695041u;
    h = (h ^ (h >> 13u)) * 1274126177u;
    return h ^ (h >> 16u);
}
void main() {
    ivec2 pos = ivec2(gl_FragCoord.xy);
    vec4 base = texelFetch(uBase, pos, 0);
    vec3 c = base.rgb;
    if (uHasBlur == 1) c = c + uSharpen * (c - texelFetch(uBlur, pos, 0).rgb);
    if (uFade != 0.0) c = c * (1.0 - uFade) + uFade;
    if (uCanvas == 1) {
        float u = (float(pos.x) + 0.5) / float(uSize.x);
        float v = (float(pos.y) + 0.5) / float(uSize.y);
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
    outColor = vec4(clamp(c, 0.0, 1.0), base.a);
}
"""
}
