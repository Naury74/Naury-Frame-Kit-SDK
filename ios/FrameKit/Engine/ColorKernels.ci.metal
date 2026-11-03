// Android GlShaders(GLSL)·core ColorEffectProcessor와 같은 식. 단계마다 8비트로 양자화한다.
#include <metal_stdlib>
#include <CoreImage/CoreImage.h>
using namespace metal;

namespace fk {
inline float luma(float3 c) { return dot(c, float3(0.2126, 0.7152, 0.0722)); }
inline float sstep(float e0, float e1, float x) {
    float t = clamp((x - e0) / (e1 - e0), 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t);
}
inline float toLinear(float c) { return c <= 0.04045 ? c / 12.92 : precise::pow((c + 0.055) / 1.055, 2.4); }
inline float toSrgb(float c) { return c <= 0.0031308 ? c * 12.92 : 1.055 * precise::pow(c, 1.0 / 2.4) - 0.055; }
inline float3 quantize(float3 c) { return floor(clamp(c, 0.0, 1.0) * 255.0 + 0.5) / 255.0; }
inline float3 unpremultiplied(float4 p) { return p.a > 0.0 ? quantize(p.rgb / p.a) : float3(0.0); }
}

extern "C" {
namespace coreimage {

// tone = (노출 배율, 그림자, 하이라이트, 밝기 오프셋), mix = (대비 배율, 채도 배율, 필터 강도, 필터 있음 1/0)
// gradeTone = (밝기 오프셋, 대비 배율, 채도 배율, 페이드)
float4 fkColor(sample_t s, float3 whiteBalance, float4 tone, float4 mix, float3 gradeGains, float4 gradeTone,
               float3 shadowTint, float3 highlightTint) {
    float3 c = fk::unpremultiplied(s);
    c = float3(fk::toSrgb(clamp(fk::toLinear(c.r) * tone.x * whiteBalance.r, 0.0, 1.0)),
               fk::toSrgb(clamp(fk::toLinear(c.g) * tone.x * whiteBalance.g, 0.0, 1.0)),
               fk::toSrgb(clamp(fk::toLinear(c.b) * tone.x * whiteBalance.b, 0.0, 1.0)));
    if (tone.y != 0.0 || tone.z != 0.0) {
        float y = fk::luma(c);
        c += 0.35 * (tone.y * (1.0 - fk::sstep(0.0, 0.55, y)) + tone.z * fk::sstep(0.45, 1.0, y));
    }
    c = (c + tone.w - 0.5) * mix.x + 0.5;
    if (mix.y != 1.0) {
        float y = fk::luma(c);
        c = y + (c - y) * mix.y;
    }
    if (mix.w > 0.5 && mix.z > 0.0) {
        float3 g = c * gradeGains;
        g = (g + gradeTone.x - 0.5) * gradeTone.y + 0.5;
        if (gradeTone.z != 1.0) {
            float y = fk::luma(g);
            g = y + (g - y) * gradeTone.z;
        }
        float y = fk::luma(g);
        g += (1.0 - fk::sstep(0.0, 0.55, y)) * shadowTint + fk::sstep(0.45, 1.0, y) * highlightTint;
        if (gradeTone.w != 0.0) g = g * (1.0 - gradeTone.w) + gradeTone.w;
        c = c + (g - c) * mix.z;
    }
    c = fk::quantize(c);
    return float4(c * s.a, s.a);
}

// 한 방향 가우스 흐림. 가장자리는 입력을 clampedToExtent로 넘겨 처리한다.
float4 fkBlur(sampler src, float2 direction, float sigma, float radius, destination dest) {
    int r = int(radius);
    float total = 1.0;
    for (int i = 1; i <= r; i++) total += 2.0 * exp(-float(i * i) / (2.0 * sigma * sigma));
    float2 dc = dest.coord();
    float3 acc = float3(0.0);
    for (int k = -r; k <= r; k++) {
        float w = exp(-float(k * k) / (2.0 * sigma * sigma)) / total;
        acc += w * fk::unpremultiplied(src.sample(src.transform(dc + direction * float(k))));
    }
    float a = src.sample(src.transform(dc)).a;
    return float4(fk::quantize(acc) * a, a);
}

// effects = (선명도, 페이드, 비네트, 그레인), canvas = (너비, 높이, 그레인 칸 배율, 흐린 이미지 있음 1/0)
// seed = (하위 16비트, 상위 16비트)
float4 fkFinish(sampler base, sampler blurred, float4 effects, float4 canvas, float2 seed, destination dest) {
    float2 dc = dest.coord();
    float4 p = base.sample(base.transform(dc));
    float3 c = fk::unpremultiplied(p);
    if (canvas.w > 0.5) {
        float3 b = fk::unpremultiplied(blurred.sample(blurred.transform(dc)));
        c = c + effects.x * (c - b);
    }
    if (effects.y != 0.0) c = c * (1.0 - effects.y) + effects.y;
    float x = floor(dc.x);
    float row = canvas.y - 1.0 - floor(dc.y);
    float u = (x + 0.5) / canvas.x;
    float v = (row + 0.5) / canvas.y;
    if (effects.z != 0.0) {
        float2 d2 = (float2(u, v) - 0.5) * 2.0;
        float d = length(d2) / 1.4142135;
        c *= 1.0 - 0.75 * effects.z * fk::sstep(0.3, 1.0, d);
    }
    if (effects.w != 0.0) {
        uint cx = uint(int(floor(u * canvas.x * canvas.z)));
        uint cy = uint(int(floor(v * canvas.y * canvas.z)));
        uint s = uint(seed.x) | (uint(seed.y) << 16);
        uint h = cx * 374761393u + cy * 668265263u + s * 1442695041u;
        h = (h ^ (h >> 13)) * 1274126177u;
        h = h ^ (h >> 16);
        float noise = float(h & 16777215u) / 16777215.0 - 0.5;
        c += 0.12 * effects.w * noise;
    }
    c = fk::quantize(c);
    return float4(c * p.a, p.a);
}

}
}
