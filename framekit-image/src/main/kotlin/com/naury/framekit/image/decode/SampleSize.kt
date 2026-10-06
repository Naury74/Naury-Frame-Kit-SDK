package com.naury.framekit.image.decode

import com.naury.framekit.core.model.PixelSize

/** 디코딩에 쓸 2의 거듭제곱 subsampling 배율을 고른다. */
public object SampleSize {

    /**
     * 디코딩된 긴 변이 [minimumLongEdge] 이상으로 유지되는 가장 큰 2의 거듭제곱을 반환한다.
     * 원본이 이미 더 작으면 1을 반환하므로 저장된 크기보다 크게 디코딩되는 일은 없다.
     */
    public fun forMinimumLongEdge(size: PixelSize, minimumLongEdge: Int): Int {
        val longEdge = maxOf(size.width, size.height)
        var sample = 1
        while (longEdge / (sample * 2) >= minimumLongEdge) sample *= 2
        return sample
    }

    /** 디코딩 크기가 가로·세로 모두 [minimum]을 덮는 가장 큰 2의 거듭제곱을 반환한다. */
    public fun forMinimumSize(size: PixelSize, minimum: PixelSize): Int {
        var sample = 1
        while (size.width / (sample * 2) >= minimum.width && size.height / (sample * 2) >= minimum.height) sample *= 2
        return sample
    }
}
