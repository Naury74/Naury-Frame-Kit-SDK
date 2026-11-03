package com.naury.framekit.core.util

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale
import java.util.Random

// JVM 구현과 결과가 같은지 확인한다.
class NumbersJvmTest {

    @Test
    fun `seeded random repeats java util Random`() {
        for (seed in listOf(0L, 17L, 42L, -5L, Long.MAX_VALUE)) {
            val expected = Random(seed)
            val actual = SeededRandom(seed)
            for (bound in listOf(1, 2, 3, 7, 64, 100, 1000, 1 shl 20, 123_456_789, Int.MAX_VALUE)) {
                repeat(50) { assertThat(actual.nextInt(bound)).isEqualTo(expected.nextInt(bound)) }
            }
        }
    }

    @Test
    fun `fixed formatting matches String format`() {
        val random = Random(3)
        repeat(20_000) {
            val value = (random.nextDouble() - 0.5) * Math.pow(10.0, random.nextInt(7).toDouble())
            for (decimals in listOf(2, 3)) {
                // JVM의 "-0.00"은 부호 없는 0으로 본다.
                val expected = String.format(Locale.ROOT, "%.${decimals}f", value).let { if (it.matches(Regex("-0\\.0+"))) it.drop(1) else it }
                assertThat(formatFixed(value, decimals)).isEqualTo(expected)
            }
        }
        assertThat(formatFixed(595.2755905511812, 3)).isEqualTo("595.276")
        assertThat(hex(0xAC00, 4)).isEqualTo("AC00")
        assertThat(hex(7, 2)).isEqualTo("07")
    }
}
