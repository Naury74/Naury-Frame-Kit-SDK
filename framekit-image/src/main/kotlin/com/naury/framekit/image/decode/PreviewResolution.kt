package com.naury.framekit.image.decode

import android.app.ActivityManager
import android.content.Context

/** 화면 미리보기용으로 디코딩하는 bitmap의 긴 변 길이(px)다. */
public object PreviewResolution {
    public const val DEFAULT_LONG_EDGE: Int = 2048
    public const val LOW_MEMORY_LONG_EDGE: Int = 1280

    public fun longEdge(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return if (activityManager?.isLowRamDevice == true) LOW_MEMORY_LONG_EDGE else DEFAULT_LONG_EDGE
    }
}
