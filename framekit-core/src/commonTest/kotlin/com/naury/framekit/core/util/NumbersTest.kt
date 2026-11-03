package com.naury.framekit.core.util

import kotlin.test.Test
import kotlin.test.assertEquals

// 기댓값은 java.util.Random·String.format 결과.
class NumbersTest {

    @Test
    fun seededRandomMatchesJvmSequence() {
        val random = SeededRandom(17L)
        val actual = listOf(7, 100, 1000, 64, 3, 1 shl 20, 123_456_789, 2).map { random.nextInt(it) }
        assertEquals(listOf(4, 20, 694, 37, 1, 420770, 24459358, 1), actual)
    }

    @Test
    fun fixedFormattingIsLocaleFree() {
        assertEquals("595.276", formatFixed(595.2755905511812, 3))
        assertEquals("-12.35", formatFixed(-12.345, 2))
        assertEquals("0.000", formatFixed(-0.0001, 3))
        assertEquals("100.00", formatFixed(99.999, 2))
        assertEquals("AC00", hex(0xAC00, 4))
    }
}
