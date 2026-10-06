package com.naury.framekit.image.decode

import android.app.ActivityManager
import android.content.Context

/** Long edge, in pixels, of the bitmap decoded for on-screen preview. */
public object PreviewResolution {
    public const val DEFAULT_LONG_EDGE: Int = 2048
    public const val LOW_MEMORY_LONG_EDGE: Int = 1280

    public fun longEdge(context: Context): Int {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        return if (activityManager?.isLowRamDevice == true) LOW_MEMORY_LONG_EDGE else DEFAULT_LONG_EDGE
    }
}
