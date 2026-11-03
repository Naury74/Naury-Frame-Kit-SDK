package com.naury.framekit.core.util

import kotlin.math.abs
import kotlin.math.floor

/**
 * [value]를 소수점 아래 [decimals]자리(0..9)로 반올림한 문자열. 0.5는 0에서 먼 쪽으로, 소수점은 지역 설정과 관계없이 `.`.
 * NaN·무한대이거나 절댓값이 1e9 이상이면 `toString()` 결과를 돌려준다.
 */
internal fun formatFixed(value: Double, decimals: Int): String {
    require(decimals in 0..9) { "decimals must be 0..9" }
    if (!value.isFinite() || abs(value) >= MAX_FIXED) return value.toString()
    var scale = 1L
    repeat(decimals) { scale *= 10 }
    val rounded = floor(abs(value) * scale + 0.5).toLong()
    val sign = if (value < 0 && rounded != 0L) "-" else ""
    val whole = rounded / scale
    if (decimals == 0) return "$sign$whole"
    return "$sign$whole.${(rounded % scale).toString().padStart(decimals, '0')}"
}

// 10^9배해도 Long 범위 안인 상한.
private const val MAX_FIXED = 1e9

internal fun formatFixed(value: Float, decimals: Int): String = formatFixed(value.toDouble(), decimals)

/** [value]를 대문자 16진수 [digits]자리로 만든다. 모자란 자리는 0으로 채운다. */
internal fun hex(value: Int, digits: Int): String = value.toString(16).uppercase().padStart(digits, '0')

/** `java.util.Random`과 같은 수열을 내는 생성기. 문서 감지 결과를 플랫폼 간에 같게 한다. */
internal class SeededRandom(seed: Long) {
    private var state = (seed xor MULTIPLIER) and MASK

    private fun next(bits: Int): Int {
        state = (state * MULTIPLIER + ADDEND) and MASK
        return (state ushr (48 - bits)).toInt()
    }

    /** `0`부터 [bound] 미만의 정수. [bound]는 양수. */
    fun nextInt(bound: Int): Int {
        require(bound > 0) { "bound must be positive" }
        var r = next(31)
        val m = bound - 1
        if (bound and m == 0) return ((bound.toLong() * r) shr 31).toInt()
        var u = r
        r = u % bound
        while (u - r + m < 0) {
            u = next(31)
            r = u % bound
        }
        return r
    }

    private companion object {
        const val MULTIPLIER = 0x5DEECE66DL
        const val ADDEND = 0xBL
        const val MASK = (1L shl 48) - 1
    }
}
