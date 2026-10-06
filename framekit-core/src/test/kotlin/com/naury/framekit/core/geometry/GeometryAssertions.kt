package com.naury.framekit.core.geometry

import com.google.common.truth.Truth.assertWithMessage

internal fun assertPoint(actual: PointN, expectedX: Double, expectedY: Double, tolerance: Double = 1e-6) {
    assertWithMessage("x of $actual").that(actual.x).isWithin(tolerance).of(expectedX)
    assertWithMessage("y of $actual").that(actual.y).isWithin(tolerance).of(expectedY)
}

internal fun assertRect(actual: RectN, expected: RectN, tolerance: Double = 1e-6) {
    assertWithMessage("left of $actual").that(actual.left).isWithin(tolerance).of(expected.left)
    assertWithMessage("top of $actual").that(actual.top).isWithin(tolerance).of(expected.top)
    assertWithMessage("right of $actual").that(actual.right).isWithin(tolerance).of(expected.right)
    assertWithMessage("bottom of $actual").that(actual.bottom).isWithin(tolerance).of(expected.bottom)
}
